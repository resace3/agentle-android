package dev.agentle.connectors.android.permissions

import android.app.Activity
import android.app.AppOpsManager
import android.app.Application
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import dev.agentle.connectors.android.core.RuntimeReceiver
import dev.agentle.connectors.android.core.SettingsSource
import dev.agentle.connectors.api.ActiveJitaiSignal
import dev.agentle.connectors.api.CapabilityIds
import dev.agentle.connectors.api.CapabilityRegistry
import dev.agentle.connectors.api.CapabilityStatusProvider
import dev.agentle.connectors.api.CollectionSettings
import dev.agentle.connectors.api.NotificationDeliveryGate
import dev.agentle.connectors.api.PermissionRequestStore
import dev.agentle.connectors.api.UnusedAppRestrictions
import dev.agentle.connectors.api.UnusedAppRestrictionsStatus
import dev.agentle.core.common.Logger
import dev.agentle.core.model.Blocker
import dev.agentle.core.model.CapabilityStatus
import dev.agentle.core.model.DataCapability
import dev.agentle.core.model.PermissionState
import dev.agentle.core.model.PlannedStatus
import dev.agentle.core.time.AgentleClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/**
 * The Permission Center backend (docs/ARCHITECTURE.md §6.3, docs/research/01 §5): the live state of every registry
 * capability, resolved per mechanism with public APIs only.
 *
 * Passes (red team testing-build-15):
 * - background passes ([refresh], before every collection run, on push signals) compute context-free signals and keep
 *   the last UI-derived DENIED vs DENIED_PERMANENTLY verdict ([LastUiVerdicts]);
 * - UI passes ([onResumed], [onPermissionResult]) refine requested, denied runtime permissions with
 *   `shouldShowRequestPermissionRationale` on the resumed Activity and store the verdicts.
 *
 * Re-evaluation (§5.4): every Activity resume, usage-access app-op changes, notification listener connect and
 * disconnect, app and channel block changes, location mode and Bluetooth adapter changes, settings changes, and before
 * every collection run ([refresh] with ids).
 *
 * It also answers [NotificationDeliveryGate] (red team jitai-correctness-13) and reports app hibernation / unused-app
 * restrictions as [unusedAppRestrictions], offered only while a JITAI is active.
 */
@Suppress("LongParameterList")
public class PermissionCenter(
    private val context: Context,
    private val registry: CapabilityRegistry,
    private val platform: PlatformState,
    private val requests: PermissionRequestStore,
    private val settings: SettingsSource,
    private val healthConnect: HealthConnectProbe,
    private val playServices: PlayServicesProbe,
    public val listener: ListenerConnection,
    private val listenerComponent: ComponentName,
    private val clock: AgentleClock,
    private val logger: Logger,
    private val scope: CoroutineScope,
    private val activeJitai: ActiveJitaiSignal? = null,
    private val debuggable: Boolean = platform.isDebuggable(),
) : CapabilityStatusProvider,
    NotificationDeliveryGate {
    private val mutex = Mutex()
    private val state = MutableStateFlow<Map<String, CapabilityStatus>>(emptyMap())
    private val unused = MutableStateFlow<UnusedAppRestrictionsStatus?>(null)
    private val jitaiActive = MutableStateFlow(false)
    private val foregroundState = MutableStateFlow(false)
    private val processStart: Instant = clock.now()
    private var resumed: WeakReference<Activity>? = null
    private var started = false
    private var appOpsListener: AppOpsManager.OnOpChangedListener? = null
    private var pushReceiver: RuntimeReceiver? = null

    @Volatile private var lastSettings: CollectionSettings? = null

    override val statuses: StateFlow<Map<String, CapabilityStatus>> = state.asStateFlow()

    override val unusedAppRestrictions: StateFlow<UnusedAppRestrictionsStatus?> = unused.asStateFlow()

    /** Whether an Agentle Activity is resumed (foreground-only collectors such as location read it). */
    public val foreground: StateFlow<Boolean> = foregroundState.asStateFlow()

    /** The resumed Activity, if any (UI passes only). */
    public val resumedActivity: Activity? get() = resumed?.get()

    /** Registers every push signal and runs the first pass; idempotent. Call from `Application.onCreate`. */
    @Synchronized
    public fun start() {
        if (started) return
        started = true
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(lifecycle)
        watchUsageAccess()
        pushReceiver = RuntimeReceiver(context, PUSH_ACTIONS, exported = true, clock = clock) { action, _, _ ->
            scope.launch { refresh(idsFor(action)) }
        }.also { it.register() }
        scope.launch {
            listener.connected.drop(1).collect {
                refresh(listOf(CapabilityIds.NOTIFICATION_EVENTS_METADATA, CapabilityIds.NOTIFICATION_CONTENT))
            }
        }
        scope.launch { settings.flow.distinctUntilChanged().drop(1).collect { refresh() } }
        activeJitai?.let { signal ->
            scope.launch {
                signal.hasActiveJitai().distinctUntilChanged().collect { active ->
                    jitaiActive.value = active
                    republishUnused()
                }
            }
        }
        scope.launch { refresh() }
    }

    /** Unregisters push signals (tests). */
    @Synchronized
    public fun stop() {
        if (!started) return
        started = false
        (context.applicationContext as? Application)?.unregisterActivityLifecycleCallbacks(lifecycle)
        appOpsListener?.let { listenerToRemove ->
            runCatching { context.getSystemService(AppOpsManager::class.java)?.stopWatchingMode(listenerToRemove) }
        }
        appOpsListener = null
        pushReceiver?.unregister()
        pushReceiver = null
    }

    override suspend fun refresh(): Map<String, CapabilityStatus> = evaluate(registry.ids, activity = null, full = true)

    override suspend fun refresh(capabilityIds: Collection<String>): Map<String, CapabilityStatus> =
        evaluate(capabilityIds.filter { it in registry }, activity = null, full = false)

    /** A UI pass: every capability, refined with [activity]'s rationale state. */
    public suspend fun onResumed(activity: Activity): Map<String, CapabilityStatus> {
        resumed = WeakReference(activity)
        foregroundState.value = true
        return evaluate(registry.ids, activity, full = true)
    }

    override suspend fun onPermissionResult(results: Map<String, Boolean>) {
        if (results.isEmpty()) return
        guard("mark_requested") { requests.markRequested(results.keys) }
        evaluate(registry.ids, resumedActivity, full = true)
    }

    override suspend fun onReturnedFromSettings(specialAccess: String) {
        guard("mark_settings_visited") { requests.markSettingsVisited(specialAccess) }
        evaluate(registry.ids, resumedActivity, full = true)
    }

    override fun requestablePermissions(capabilityId: String): List<String> {
        val capability = registry[capabilityId] ?: return emptyList()
        if (!inThisBuild(capability)) return emptyList()
        val sdk = platform.sdkInt
        return when (capabilityId) {
            CapabilityIds.CALL_STATE -> if (sdk >= Build.VERSION_CODES.S &&
                callEnabled()
            ) {
                listOf(Permissions.READ_PHONE_STATE)
            } else {
                emptyList()
            }

            CapabilityIds.POST_NOTIFICATIONS_JITAI -> if (sdk >=
                Build.VERSION_CODES.TIRAMISU
            ) {
                listOf(Permissions.POST_NOTIFICATIONS)
            } else {
                emptyList()
            }

            CapabilityIds.BLUETOOTH_CONNECTED_DEVICES -> if (sdk >=
                Build.VERSION_CODES.S
            ) {
                listOf(Permissions.BLUETOOTH_CONNECT)
            } else {
                emptyList()
            }

            CapabilityIds.LOCATION_FOREGROUND -> if (preciseLocation()) {
                listOf(Permissions.ACCESS_COARSE_LOCATION, Permissions.ACCESS_FINE_LOCATION)
            } else {
                listOf(Permissions.ACCESS_COARSE_LOCATION)
            }

            CapabilityIds.HEALTH_CONNECT_RECORDS -> HealthPermissions.RECORDS

            CapabilityIds.HEALTH_CONNECT_BACKGROUND_READ -> listOf(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)

            CapabilityIds.HEALTH_CONNECT_HISTORY_READ -> listOf(HealthPermissions.READ_HEALTH_DATA_HISTORY)

            CapabilityIds.HEALTH_CONNECT_ON_DEVICE_STEPS -> listOf(HealthPermissions.READ_STEPS)

            else -> capability.runtimePermissions
        }
    }

    /**
     * True only when notifications are enabled for Agentle (POST_NOTIFICATIONS on 33+ included), [channelId] is not
     * blocked (importance NONE or a blocked group) and notifications are not paused. Live platform reads, no cache.
     */
    override fun canDeliver(channelId: String): Boolean {
        val permissionHeld = platform.sdkInt < Build.VERSION_CODES.TIRAMISU || platform.isGranted(Permissions.POST_NOTIFICATIONS)
        return permissionHeld && platform.notificationsEnabled() && !platform.notificationsPaused() &&
            channelId !in platform.blockedChannelIds()
    }

    private fun inThisBuild(capability: DataCapability): Boolean = when (capability.plannedStatus) {
        PlannedStatus.IMPLEMENT -> true
        PlannedStatus.IMPLEMENT_DEBUG_ONLY -> debuggable
        PlannedStatus.DEFER, PlannedStatus.DOCUMENT_UNAVAILABLE -> false
    }

    /** The opt-in flag as of the last pass (READ_PHONE_STATE is never requested before the user enabled `call_state`). */
    private fun callEnabled(): Boolean = lastSettings?.enabledOptInConnectors?.contains(CallStateResolver.CALL_CONNECTOR_ID) == true

    private fun preciseLocation(): Boolean = lastSettings?.preciseLocation == true

    private suspend fun evaluate(ids: Collection<String>, activity: Activity?, full: Boolean): Map<String, CapabilityStatus> =
        mutex.withLock {
            val now = clock.now()
            val currentSettings = settings.current().also { lastSettings = it }
            val context = ResolverContext(
                platform = platform,
                settings = currentSettings,
                requestedPermissions = read("requested") { requests.requestedPermissions() }.orEmpty(),
                settingsVisited = read("settings_visited") { requests.settingsVisited() }.orEmpty(),
                listenerConnected = listener.isConnected,
                healthConnect = MemoHealthConnect(healthConnect),
                playServices = MemoPlayServices(playServices),
            )
            val signals = LinkedHashMap<String, StateBuilder>()
            ids.forEach { id -> registry[id]?.let { signals[id] = resolveSafely(it, context) } }
            val refinement = if (activity != null) {
                RationaleRefinement(activity, platform).also { recordVerdicts(it, signals.values, context) }
            } else {
                LastUiVerdicts(read("verdicts") { requests.permanentlyDenied() }.orEmpty())
            }
            val resolved = signals.mapValues { (id, builder) -> builder.resolve(id, now, refinement) }
            state.update { it + resolved }
            if (CapabilityIds.NOTIFICATION_EVENTS_METADATA in signals) {
                listener.rebindIfDue(platform.notificationListenerGranted(listenerComponent), now, processStart)
            }
            if (full) evaluateUnused(context, now)
            resolved
        }

    /** Stores the UI-derived verdict of every pending denial; permissions now granted lose their verdict. */
    private suspend fun recordVerdicts(refinement: DenialRefinement, signals: Collection<StateBuilder>, context: ResolverContext) {
        val pending = signals.flatMap { it.pendingDenials }.toSet()
        val verdicts = pending.associateWith { refinement.isPermanentlyDenied(it) }
        val granted = context.requestedPermissions.filter { it !in pending && platform.isGranted(it) }.associateWith { false }
        guard("record_verdicts") { requests.recordUiVerdicts(verdicts + granted) }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun resolveSafely(capability: DataCapability, context: ResolverContext): StateBuilder = try {
        CapabilityResolvers.forCapability(capability, listenerComponent, debuggable).resolve(capability, context)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.w(COMPONENT, "Resolver failed", fields = mapOf("capability" to capability.id, "error" to e::class.simpleName))
        StateBuilder().add(PermissionState.UNAVAILABLE)
    }

    private suspend fun evaluateUnused(context: ResolverContext, now: Instant) {
        val restrictions = context.unusedAppRestrictions()
        val builder = when (restrictions) {
            UnusedAppRestrictions.NOT_AVAILABLE -> StateBuilder().add(PermissionState.UNSUPPORTED_ON_DEVICE)
            UnusedAppRestrictions.DISABLED -> StateBuilder()
            UnusedAppRestrictions.UNKNOWN -> StateBuilder().add(PermissionState.UNAVAILABLE)
            else -> StateBuilder().add(PermissionState.REQUIRES_SETTINGS, Blocker.HIBERNATION_ENABLED)
        }
        val status = builder.build(UnusedAppRestrictionsStatus.CONDITION_ID, now)
        unused.value = UnusedAppRestrictionsStatus(restrictions, status, offered = restrictions.restrictsApp && jitaiActive.value)
    }

    private fun republishUnused() {
        unused.update { current -> current?.copy(offered = current.restrictions.restrictsApp && jitaiActive.value) }
    }

    private fun watchUsageAccess() {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return
        val callback = AppOpsManager.OnOpChangedListener { op, packageName ->
            if (op == AppOpsManager.OPSTR_GET_USAGE_STATS && packageName == context.packageName) scope.launch { refresh(USAGE_IDS) }
        }
        runCatching { appOps.startWatchingMode(AppOpsManager.OPSTR_GET_USAGE_STATS, context.packageName, callback) }
            .onSuccess { appOpsListener = callback }
    }

    private suspend fun <T> read(name: String, block: suspend () -> T): T? = guard(name, block)

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> guard(name: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.w(COMPONENT, "Permission store unavailable", fields = mapOf("operation" to name, "error" to e::class.simpleName))
        null
    }

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            resumed = WeakReference(activity)
            foregroundState.value = true
            scope.launch { onResumed(activity) }
        }

        override fun onActivityPaused(activity: Activity) {
            if (resumed?.get() === activity) {
                resumed = null
                foregroundState.value = false
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    /** Memoizes Health Connect reads for one pass. */
    private class MemoHealthConnect(private val delegate: HealthConnectProbe) : HealthConnectProbe {
        private val status by lazy { delegate.sdkStatus() }
        private var granted: Set<String>? = null
        private val features = HashMap<Int, Boolean>()

        override fun sdkStatus(): Int = status

        override suspend fun grantedPermissions(): Set<String> = granted ?: delegate.grantedPermissions().also { granted = it }

        override fun featureAvailable(feature: Int): Boolean = features.getOrPut(feature) { delegate.featureAvailable(feature) }
    }

    /** Memoizes Play services reads for one pass. */
    private class MemoPlayServices(private val delegate: PlayServicesProbe) : PlayServicesProbe {
        private val results = HashMap<PlayServicesRequirement, Boolean>()

        override fun isAvailable(requirement: PlayServicesRequirement): Boolean = results.getOrPut(requirement) {
            delegate.isAvailable(requirement)
        }
    }

    public companion object {
        private const val COMPONENT = "collectors.permissions"

        private val USAGE_IDS = listOf(
            CapabilityIds.APP_USAGE_EVENTS,
            CapabilityIds.APP_USAGE_AGGREGATES,
            CapabilityIds.NETWORK_DATA_USAGE,
            CapabilityIds.SCREEN_INTERACTIVE_EVENTS,
            CapabilityIds.UNLOCK_KEYGUARD_EVENTS,
            CapabilityIds.BOOT_SHUTDOWN_EVENTS,
            CapabilityIds.APP_STANDBY_BUCKET,
        )

        /** Broadcasts that change a capability state (§5.4 push signals); all protected, so exporting is safe. */
        private val PUSH_ACTIONS = setOf(
            NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED,
            NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED,
            NotificationManager.ACTION_NOTIFICATION_CHANNEL_GROUP_BLOCK_STATE_CHANGED,
            LocationManager.MODE_CHANGED_ACTION,
            BluetoothAdapter.ACTION_STATE_CHANGED,
        )

        private fun idsFor(action: String): List<String> = when (action) {
            LocationManager.MODE_CHANGED_ACTION -> listOf(CapabilityIds.LOCATION_FOREGROUND)

            BluetoothAdapter.ACTION_STATE_CHANGED -> listOf(
                CapabilityIds.BLUETOOTH_ADAPTER_STATE,
                CapabilityIds.BLUETOOTH_CONNECTED_DEVICES,
            )

            else -> listOf(CapabilityIds.POST_NOTIFICATIONS_JITAI)
        }
    }
}
