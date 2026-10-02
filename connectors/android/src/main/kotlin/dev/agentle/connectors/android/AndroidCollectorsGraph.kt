package dev.agentle.connectors.android

import android.app.AlarmManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dagger.hilt.android.EntryPointAccessors
import dev.agentle.connectors.android.collectors.activity.ActivityTransitionGateway
import dev.agentle.connectors.android.collectors.activity.ActivityTransitionsConnector
import dev.agentle.connectors.android.collectors.activity.PlayServicesActivityTransitionGateway
import dev.agentle.connectors.android.collectors.calendar.CalendarConnector
import dev.agentle.connectors.android.collectors.calendar.CalendarSource
import dev.agentle.connectors.android.collectors.calendar.ContentResolverCalendarSource
import dev.agentle.connectors.android.collectors.device.AudioConnector
import dev.agentle.connectors.android.collectors.device.AudioRecorder
import dev.agentle.connectors.android.collectors.device.BatteryConnector
import dev.agentle.connectors.android.collectors.device.BluetoothConnector
import dev.agentle.connectors.android.collectors.device.BluetoothRecorder
import dev.agentle.connectors.android.collectors.device.CallConnector
import dev.agentle.connectors.android.collectors.device.DeviceRecorder
import dev.agentle.connectors.android.collectors.device.DeviceState
import dev.agentle.connectors.android.collectors.device.DeviceStateConnector
import dev.agentle.connectors.android.collectors.device.NetworkConnector
import dev.agentle.connectors.android.collectors.device.NetworkRecorder
import dev.agentle.connectors.android.collectors.device.PowerRecorder
import dev.agentle.connectors.android.collectors.device.ScreenConnector
import dev.agentle.connectors.android.collectors.device.SystemConnector
import dev.agentle.connectors.android.collectors.device.SystemRecorder
import dev.agentle.connectors.android.collectors.healthconnect.ClientHealthConnectGateway
import dev.agentle.connectors.android.collectors.healthconnect.HealthConnectConnector
import dev.agentle.connectors.android.collectors.healthconnect.HealthConnectGateway
import dev.agentle.connectors.android.collectors.live.AudioLiveSource
import dev.agentle.connectors.android.collectors.live.BluetoothLiveSource
import dev.agentle.connectors.android.collectors.live.CallStateLiveSource
import dev.agentle.connectors.android.collectors.live.DndLiveSource
import dev.agentle.connectors.android.collectors.live.NetworkLiveSource
import dev.agentle.connectors.android.collectors.live.PowerLiveSource
import dev.agentle.connectors.android.collectors.live.ScreenLiveSource
import dev.agentle.connectors.android.collectors.location.FusedLocationSource
import dev.agentle.connectors.android.collectors.location.LocationConnector
import dev.agentle.connectors.android.collectors.location.LocationSource
import dev.agentle.connectors.android.collectors.notifications.AgentleNotificationListener
import dev.agentle.connectors.android.collectors.notifications.DefaultHandlers
import dev.agentle.connectors.android.collectors.notifications.NotificationCollector
import dev.agentle.connectors.android.collectors.notifications.NotificationsConnector
import dev.agentle.connectors.android.collectors.notifications.PlatformDefaultHandlers
import dev.agentle.connectors.android.collectors.steps.RecordingApiStepsGateway
import dev.agentle.connectors.android.collectors.steps.StepsConnector
import dev.agentle.connectors.android.collectors.steps.StepsRecordingGateway
import dev.agentle.connectors.android.collectors.usage.AppCategorySource
import dev.agentle.connectors.android.collectors.usage.BootCountSource
import dev.agentle.connectors.android.collectors.usage.PlatformAppCategorySource
import dev.agentle.connectors.android.collectors.usage.PlatformBootCountSource
import dev.agentle.connectors.android.collectors.usage.PlatformUsageEventSource
import dev.agentle.connectors.android.collectors.usage.UsageConnector
import dev.agentle.connectors.android.collectors.usage.UsageEventSource
import dev.agentle.connectors.android.core.AndroidConnector
import dev.agentle.connectors.android.core.CollectorRuntime
import dev.agentle.connectors.android.core.CoverageBootstrap
import dev.agentle.connectors.android.core.EventFactory
import dev.agentle.connectors.android.core.EventSinkWriter
import dev.agentle.connectors.android.core.ExitInfoSource
import dev.agentle.connectors.android.core.IdentifierHasher
import dev.agentle.connectors.android.core.InstallSalt
import dev.agentle.connectors.android.core.LiveController
import dev.agentle.connectors.android.core.LiveSource
import dev.agentle.connectors.android.core.LiveWriter
import dev.agentle.connectors.android.core.PlatformExitInfoSource
import dev.agentle.connectors.android.core.RateLimit
import dev.agentle.connectors.android.core.SafeCoverage
import dev.agentle.connectors.android.core.SettingsSource
import dev.agentle.connectors.android.core.UnavailableWriter
import dev.agentle.connectors.android.di.AndroidCollectorsEntryPoint
import dev.agentle.connectors.android.permissions.GmsPlayServicesProbe
import dev.agentle.connectors.android.permissions.ListenerConnection
import dev.agentle.connectors.android.permissions.PendingIntentFactory
import dev.agentle.connectors.android.permissions.PermissionCenter
import dev.agentle.connectors.android.permissions.PlatformState
import dev.agentle.connectors.android.permissions.PlayServicesProbe
import dev.agentle.connectors.android.permissions.PreferencesPermissionRequestStore
import dev.agentle.connectors.android.permissions.SettingsIntentFactory
import dev.agentle.connectors.android.receivers.SystemChangeDispatcher
import dev.agentle.connectors.api.ActiveJitaiSignal
import dev.agentle.connectors.api.CapabilityRegistry
import dev.agentle.connectors.api.CollectionSettingsStore
import dev.agentle.connectors.api.CollectorEventWriter
import dev.agentle.connectors.api.CoverageRecorder
import dev.agentle.connectors.api.EventSink
import dev.agentle.connectors.api.LiveCollectionControl
import dev.agentle.connectors.api.NotificationContentPurger
import dev.agentle.connectors.api.PermissionRequestStore
import dev.agentle.connectors.api.SystemChange
import dev.agentle.connectors.api.SystemChangeListener
import dev.agentle.core.common.AppDispatchers
import dev.agentle.core.common.Logger
import dev.agentle.core.time.AgentleClock
import dev.agentle.core.time.SystemAgentleClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.Instant

/**
 * Ports other modules bind (all optional). Missing ports fall back to safe defaults: no writer means every write is
 * skipped and recorded as a coverage gap; no settings store means in-memory privacy-preserving defaults; no permission
 * store means a private SharedPreferences file.
 */
public data class CollectorPorts(
    val clock: AgentleClock? = null,
    val logger: Logger? = null,
    val dispatchers: AppDispatchers? = null,
    val writer: CollectorEventWriter? = null,
    val eventSink: EventSink? = null,
    val coverage: CoverageRecorder? = null,
    val settings: CollectionSettingsStore? = null,
    val permissionRequests: PermissionRequestStore? = null,
    val systemChanges: SystemChangeListener? = null,
    val activeJitai: ActiveJitaiSignal? = null,
    val notificationPurger: NotificationContentPurger? = null,
)

/** Enables or disables one of this app's components (delete-all disables the notification listener). */
public fun interface ComponentToggle {
    public fun setEnabled(component: ComponentName, enabled: Boolean)
}

public class PackageManagerComponentToggle(private val context: Context) : ComponentToggle {
    @Suppress("TooGenericExceptionCaught")
    override fun setEnabled(component: ComponentName, enabled: Boolean) {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        try {
            context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        } catch (ignored: RuntimeException) {
            // The component stays as it was; the collector drops everything while suspended anyway.
        }
    }
}

/** Platform seams; tests replace single ones with fakes. */
public data class CollectorSeams(
    val registry: CapabilityRegistry,
    val platform: PlatformState,
    val device: DeviceState,
    val usageEvents: UsageEventSource,
    val bootCounts: BootCountSource,
    val appCategories: AppCategorySource,
    val healthConnect: HealthConnectGateway,
    val playServices: PlayServicesProbe,
    val steps: StepsRecordingGateway,
    val calendar: CalendarSource,
    val exits: ExitInfoSource,
    val defaultHandlers: DefaultHandlers,
    /** Null: the Play services gateway with this graph's PendingIntentFactory. */
    val activityTransitions: ActivityTransitionGateway? = null,
    /** Null: the fused provider (or the platform's last known fix without Play services). */
    val location: LocationSource? = null,
    /** Null: `NotificationListenerService.requestRebind`. */
    val listenerRebind: ((ComponentName) -> Unit)? = null,
    /** Null: `PackageManager.setComponentEnabledSetting`. */
    val componentToggle: ComponentToggle? = null,
) {
    public companion object {
        public fun platform(context: Context): CollectorSeams = CollectorSeams(
            registry = CapabilityRegistry.load(),
            platform = PlatformState(context),
            device = DeviceState(context),
            usageEvents = PlatformUsageEventSource(context),
            bootCounts = PlatformBootCountSource(context),
            appCategories = PlatformAppCategorySource(context),
            healthConnect = ClientHealthConnectGateway(context),
            playServices = GmsPlayServicesProbe(context),
            steps = RecordingApiStepsGateway(context),
            calendar = ContentResolverCalendarSource(context),
            exits = PlatformExitInfoSource(context),
            defaultHandlers = PlatformDefaultHandlers(context),
        )
    }
}

/**
 * Everything `:connectors:android` runs, wired once per process: the Permission Center, every connector, the live
 * sources and their controller, and the delete-all control. Hilt provides it as a singleton
 * ([dev.agentle.connectors.android.di.AndroidCollectorsModule]); receivers and the notification listener reach it through
 * [AndroidCollectors.graph].
 */
public class AndroidCollectorsGraph(
    context: Context,
    ports: CollectorPorts = CollectorPorts(),
    seams: CollectorSeams = CollectorSeams.platform(context),
    scope: CoroutineScope? = null,
) {
    private val appContext: Context = context.applicationContext ?: context

    /**
     * The bound clock (the app's, whose `elapsed()` is the time since boot). The fallback's `elapsed()` only counts from
     * process start: activity-transition times then clamp to their receipt time and boot rows are not exact.
     */
    public val clock: AgentleClock = ports.clock ?: SystemAgentleClock()
    public val logger: Logger = ports.logger ?: Logger.NONE
    private val dispatchers: AppDispatchers = ports.dispatchers ?: AppDispatchers()
    public val scope: CoroutineScope = scope ?: CoroutineScope(SupervisorJob() + dispatchers.default)
    private val settings = SettingsSource(ports.settings)
    private val coverage = SafeCoverage(ports.coverage, logger)

    public val runtime: CollectorRuntime = CollectorRuntime(
        context = appContext,
        clock = clock,
        logger = logger,
        dispatchers = dispatchers,
        writer = ports.writer ?: ports.eventSink?.let(::EventSinkWriter) ?: UnavailableWriter,
        coverage = coverage,
        settings = settings,
        events = EventFactory(clock),
        hasher = IdentifierHasher(InstallSalt.load(appContext, logger)),
        scope = this.scope,
    )

    public val registry: CapabilityRegistry = seams.registry
    public val platform: PlatformState = seams.platform
    public val listenerComponent: ComponentName = ComponentName(appContext, AgentleNotificationListener::class.java)
    public val listenerConnection: ListenerConnection =
        seams.listenerRebind?.let { ListenerConnection(listenerComponent, it) } ?: ListenerConnection(listenerComponent)
    public val pendingIntents: PendingIntentFactory = PendingIntentFactory(appContext)
    public val settingsIntents: SettingsIntentFactory = SettingsIntentFactory(appContext, seams.platform, listenerComponent)

    public val permissionCenter: PermissionCenter = PermissionCenter(
        context = appContext,
        registry = seams.registry,
        platform = seams.platform,
        requests = ports.permissionRequests ?: PreferencesPermissionRequestStore(appContext, dispatchers.io),
        settings = settings,
        healthConnect = seams.healthConnect,
        playServices = seams.playServices,
        listener = listenerConnection,
        listenerComponent = listenerComponent,
        clock = clock,
        logger = logger,
        scope = this.scope,
        activeJitai = ports.activeJitai,
    )

    private val foreground: () -> Boolean = { permissionCenter.foreground.value }

    public val usage: UsageConnector = UsageConnector(runtime, permissionCenter, seams.usageEvents, seams.bootCounts, seams.appCategories)
    public val notifications: NotificationsConnector =
        NotificationsConnector(
            runtime,
            permissionCenter,
            NotificationCollector(runtime, seams.defaultHandlers, purger = ports.notificationPurger),
        )
    public val battery: BatteryConnector = BatteryConnector(runtime, permissionCenter, seams.device, PowerRecorder(runtime))
    public val network: NetworkConnector = NetworkConnector(runtime, permissionCenter, seams.device, NetworkRecorder(runtime))
    public val bluetooth: BluetoothConnector =
        BluetoothConnector(runtime, permissionCenter, seams.device, seams.platform, BluetoothRecorder(runtime))
    public val audio: AudioConnector = AudioConnector(runtime, permissionCenter, seams.device, AudioRecorder(runtime))
    public val deviceState: DeviceStateConnector = DeviceStateConnector(runtime, permissionCenter, seams.device, DeviceRecorder(runtime))
    public val system: SystemConnector = SystemConnector(runtime, permissionCenter, seams.device, seams.platform, SystemRecorder(runtime))
    public val activity: ActivityTransitionsConnector = ActivityTransitionsConnector(
        runtime,
        permissionCenter,
        seams.activityTransitions ?: PlayServicesActivityTransitionGateway(appContext, pendingIntents),
    )
    public val steps: StepsConnector = StepsConnector(runtime, permissionCenter, seams.steps)
    public val calendar: CalendarConnector = CalendarConnector(runtime, permissionCenter, seams.calendar)
    public val healthConnect: HealthConnectConnector = HealthConnectConnector(runtime, permissionCenter, seams.healthConnect, foreground)
    public val location: LocationConnector = LocationConnector(
        runtime,
        permissionCenter,
        seams.location ?: FusedLocationSource(appContext, seams.playServices),
        seams.platform,
        foreground,
    )

    public val liveWriter: LiveWriter = LiveWriter(runtime)
    init {
        bluetooth.live = liveWriter.channel("android.bluetooth_acl", RateLimit(burst = 20, perHour = 60))
        deviceState.live = liveWriter.channel("android.dnd_listener")
        system.live = liveWriter.channel("android.system_live")
    }

    public val screenLive: ScreenLiveSource = ScreenLiveSource(runtime, seams.device, liveWriter)
    public val powerLive: PowerLiveSource = PowerLiveSource(runtime, seams.device, battery.recorder, liveWriter)
    public val networkLive: NetworkLiveSource = NetworkLiveSource(runtime, seams.device, network.recorder, liveWriter)
    public val bluetoothLive: BluetoothLiveSource = BluetoothLiveSource(runtime, seams.device, bluetooth.recorder, liveWriter)
    public val audioLive: AudioLiveSource = AudioLiveSource(runtime, seams.device, audio.recorder, liveWriter)
    public val dndLive: DndLiveSource = DndLiveSource(runtime, seams.device, deviceState.recorder, liveWriter)
    public val callLive: CallStateLiveSource = CallStateLiveSource(runtime, seams.platform, liveWriter)

    public val screen: ScreenConnector = ScreenConnector(runtime, permissionCenter) { screenLive.flush() }
    public val call: CallConnector = CallConnector(runtime, permissionCenter) { callLive.flush() }

    /** Every connector of this module (the Hilt module contributes them to `Set<Connector>`). */
    public val connectors: List<AndroidConnector> = listOf(
        usage,
        screen,
        notifications,
        battery,
        network,
        bluetooth,
        audio,
        deviceState,
        system,
        location,
        activity,
        steps,
        calendar,
        call,
        healthConnect,
    )

    public val liveSources: List<LiveSource> = listOf(screenLive, powerLive, networkLive, bluetoothLive, audioLive, dndLive, callLive)

    public val liveController: LiveController = LiveController(runtime, permissionCenter, liveSources) { connectorId, current ->
        connectors.firstOrNull { it.id == connectorId }?.isEnabled(current) ?: false
    }

    private val componentToggle: ComponentToggle = seams.componentToggle ?: PackageManagerComponentToggle(appContext)
    private val dispatcher = SystemChangeDispatcher(this.scope, ports.systemChanges, logger)
    private val bootstrap = CoverageBootstrap(coverage, seams.exits)
    private var started = false

    /**
     * Delete-all coordination (round 2 item 5): the listener component is disabled and every buffer dropped before the
     * data is wiped; nothing collected before the new data epoch is written after it.
     */
    public val liveControl: LiveCollectionControl = object : LiveCollectionControl {
        override suspend fun suspendForDeletion() {
            notifications.collector.suspendForDeletion()
            componentToggle.setEnabled(listenerComponent, false)
            liveController.suspend()
            forgetStates()
        }

        override suspend fun resumeAfterDeletion() {
            forgetStates()
            notifications.collector.resumeAfterDeletion()
            componentToggle.setEnabled(listenerComponent, true)
            listenerConnection.rebindNow(clock.now())
            liveController.resume()
        }
    }

    /**
     * Process start, from `Application.onCreate` ([AndroidCollectors.start]): the Permission Center registers its
     * signals at once (so the first resumed Activity is seen); stale coverage intervals are closed before anything
     * opens one; then the notification consumer and the live sources start, the usage high-water mark is checked and
     * activity transitions are re-registered (docs/research/03 §6.2). Idempotent.
     */
    @Synchronized
    public fun start() {
        if (started) return
        started = true
        permissionCenter.start()
        scope.launch {
            bootstrap.closeStaleIntervals()
            notifications.collector.start()
            liveController.start()
            usage.clampHighWaterMark()
            activity.ensureRegistered()
        }
    }

    /** The notification listener reported a DND change. */
    public fun onInterruptionFilterChanged(filter: Int) {
        val at = clock.now()
        scope.launch { deviceState.onInterruptionFilter(filter, at) }
    }

    /** A system broadcast from [dev.agentle.connectors.android.receivers.SystemEventReceiver] (already allow-listed). */
    public suspend fun onSystemBroadcast(action: String, at: Instant) {
        when (action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                dispatcher.dispatch(SystemChange.BOOT_COMPLETED, at)
                usage.clampHighWaterMark()
                system.onBoot(at)
                activity.ensureRegistered()
            }

            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                dispatcher.dispatch(SystemChange.PACKAGE_REPLACED, at)
                activity.ensureRegistered()
            }

            Intent.ACTION_TIME_CHANGED -> {
                usage.clampHighWaterMark()
                system.onTimeSet(at)
                dispatcher.dispatch(SystemChange.TIME_SET, at)
            }

            Intent.ACTION_TIMEZONE_CHANGED -> {
                system.onZoneChanged(at)
                dispatcher.dispatch(SystemChange.TIMEZONE_CHANGED, at)
            }

            Intent.ACTION_LOCALE_CHANGED -> {
                system.onLocaleChanged(at)
                dispatcher.dispatch(SystemChange.LOCALE_CHANGED, at)
            }

            AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED -> deviceState.onNextAlarmChanged(at)

            else -> Unit
        }
    }

    private fun forgetStates() {
        battery.recorder.forget()
        network.recorder.forget()
        bluetooth.recorder.forget()
        audio.recorder.forget()
        deviceState.recorder.forget()
        system.recorder.forget()
    }
}

/** The process-wide entry to the graph for components the system instantiates (receivers, the listener). */
public object AndroidCollectors {
    @Volatile private var installed: AndroidCollectorsGraph? = null

    /** Installs [graph] as this process's graph (the Hilt provider does; tests install their own and clear it). */
    public fun install(graph: AndroidCollectorsGraph?) {
        installed = graph
    }

    public fun installed(): AndroidCollectorsGraph? = installed

    /** The graph: the installed one, else Hilt's singleton; null when neither is available (the caller does nothing). */
    public fun graph(context: Context): AndroidCollectorsGraph? = installed ?: fromHilt(context)

    /** Call from `Application.onCreate` (the `:app` module). */
    public fun start(application: Application) {
        graph(application)?.start()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun fromHilt(context: Context): AndroidCollectorsGraph? = try {
        EntryPointAccessors.fromApplication(context.applicationContext, AndroidCollectorsEntryPoint::class.java).androidCollectorsGraph()
    } catch (ignored: RuntimeException) {
        null
    }
}
