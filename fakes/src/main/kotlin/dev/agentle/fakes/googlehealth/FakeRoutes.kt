package dev.agentle.fakes.googlehealth

/** The endpoints of docs/research/05 §8.2, with the gRPC method each one names in error metadata. */
public enum class RouteKind(internal val rpc: String) {
    IDENTITY("HealthProfileService.GetIdentity"),
    SETTINGS("HealthProfileService.GetSettings"),
    PROFILE("HealthProfileService.GetProfile"),
    PAIRED_DEVICES("PairedDevicesService.ListPairedDevices"),
    PAIRED_DEVICE("PairedDevicesService.GetPairedDevice"),
    LIST("DataPointsService.ListDataPoints"),
    RECONCILE("DataPointsService.ReconcileDataPoints"),
    ROLL_UP("DataPointsService.RollUpDataPoints"),
    DAILY_ROLL_UP("DataPointsService.DailyRollUpDataPoints"),
    GET("DataPointsService.GetDataPoint"),
    EXPORT_TCX("DataPointsService.ExportExerciseTcx"),

    /** `GET` on `:rollUp` or `:dailyRollUp`: routed (a probe got 401, not 404), then E404-HTML after auth (MODELED, U25). */
    WRONG_VERB("DataPointsService.ListDataPoints"),
    ;

    internal val fullRpc: String get() = "google.devicesandservices.health.v4.$rpc"
}

/** A routed request: the endpoint, plus the path's type id, data point id or device id. */
public data class FakeRoute(val kind: RouteKind, val typeId: String? = null, val itemId: String? = null) {
    val type: GhDataType? get() = typeId?.let { GhDataTypes.byId(it) }

    /** The scope suffixes the route needs, all of them; identity needs any one read scope instead (§8.1). */
    internal fun requiredScopes(): Set<String>? = when (kind) {
        RouteKind.IDENTITY -> null
        RouteKind.SETTINGS, RouteKind.PAIRED_DEVICES, RouteKind.PAIRED_DEVICE -> setOf(GhScopes.SETTINGS)
        RouteKind.PROFILE -> setOf(GhScopes.PROFILE)
        RouteKind.EXPORT_TCX -> setOf(GhScopes.ACTIVITY, GhScopes.LOCATION)
        else -> type?.let { setOf(it.scope) } ?: emptySet()
    }

    /** Whether the route reads health data (everything except identity, settings and profile). */
    internal val isData: Boolean
        get() = kind != RouteKind.IDENTITY && kind != RouteKind.SETTINGS && kind != RouteKind.PROFILE
}

/** Route matching on method and path template (§8.2). Unrouted requests get E404-HTML before any other check. */
internal object FakeRoutes {
    private const val DATA_POINTS = "dataPoints"

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun match(method: String, path: String, healthUserId: String): FakeRoute? {
        val s = path.trim('/').split('/')
        if (s.size < 4 || s[0] != "v4" || s[1] != "users") return null
        if (s[2] != "me" && s[2] != healthUserId) return null
        val get = method == "GET"
        return when (s[3]) {
            "identity" -> if (get && s.size == 4) FakeRoute(RouteKind.IDENTITY) else null

            "settings" -> if (get && s.size == 4) FakeRoute(RouteKind.SETTINGS) else null

            "profile" -> if (get && s.size == 4) FakeRoute(RouteKind.PROFILE) else null

            "pairedDevices" -> when {
                !get -> null
                s.size == 4 -> FakeRoute(RouteKind.PAIRED_DEVICES)
                s.size == 5 -> FakeRoute(RouteKind.PAIRED_DEVICE, itemId = s[4])
                else -> null
            }

            "dataTypes" -> dataPoints(method, s)

            else -> null
        }
    }

    private fun dataPoints(method: String, s: List<String>): FakeRoute? {
        if (s.size !in 6..7) return null
        val type = s[4]
        val last = s[5]
        if (s.size == 7) {
            if (last != DATA_POINTS || method != "GET") return null
            val id = s[6]
            return if (id.endsWith(":exportExerciseTcx")) {
                FakeRoute(RouteKind.EXPORT_TCX, type, id.removeSuffix(":exportExerciseTcx"))
            } else if (':' in id) {
                null
            } else {
                FakeRoute(RouteKind.GET, type, id)
            }
        }
        return when {
            last == DATA_POINTS && method == "GET" -> FakeRoute(RouteKind.LIST, type)

            last == "$DATA_POINTS:reconcile" && method == "GET" -> FakeRoute(RouteKind.RECONCILE, type)

            last == "$DATA_POINTS:rollUp" -> if (method == "POST") FakeRoute(RouteKind.ROLL_UP, type) else wrongVerb(method, type)

            last == "$DATA_POINTS:dailyRollUp" -> if (method ==
                "POST"
            ) {
                FakeRoute(RouteKind.DAILY_ROLL_UP, type)
            } else {
                wrongVerb(method, type)
            }

            else -> null
        }
    }

    private fun wrongVerb(method: String, type: String): FakeRoute? = if (method == "GET") FakeRoute(RouteKind.WRONG_VERB, type) else null
}
