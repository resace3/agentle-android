package dev.agentle.jitai.engine.testing

/** Where the fakes simulate process death (R10 §12.P; jitai-correctness item 15). */
public enum class CrashPoint {
    /** The process dies inside the transaction that would insert decision rows: nothing of it is stored. */
    BEFORE_COMMIT,

    /** A commit that inserted decision rows (DECIDED among them) is stored; the process dies before delivering. */
    AFTER_COMMIT,

    /** The process dies while content is rendered and media prepared: the row is still DECIDED ([FakeDeliveryPort]). */
    DURING_RENDER,

    /** The claim (DECIDED -> DELIVERING) is stored; the process dies before posting. */
    AFTER_CLAIM,

    /** The notification was posted; the process dies before DELIVERING -> DELIVERED ([FakeDeliveryPort]). */
    AFTER_POST,

    /** DELIVERING -> DELIVERED is stored; the process dies right after. */
    AFTER_MARK,
}

/** Thrown when the simulated process dies. An [Error], so no engine code (which maps only exceptions) catches it. */
public class SimulatedCrash(public val point: CrashPoint?) : Error("simulated crash")

/**
 * Process death shared by the fakes of one simulated device. [arm] a [CrashPoint]; when a fake reaches it the process
 * dies: [SimulatedCrash] is thrown and every later call into a fake throws as well until [restart], so nothing the dying
 * process would still run (cancellation handlers, `NonCancellable` blocks) reaches storage or the notification shade.
 */
public class ProcessDeath {
    @Volatile
    private var armed: CrashPoint? = null

    @Volatile
    public var dead: Boolean = false
        private set

    /** The point the last crash happened at. */
    @Volatile
    public var firedAt: CrashPoint? = null
        private set

    public fun arm(point: CrashPoint) {
        armed = point
    }

    /** Dies here when [point] is armed (once). */
    public fun fireIf(point: CrashPoint) {
        if (armed != point) return
        armed = null
        dead = true
        firedAt = point
        throw SimulatedCrash(point)
    }

    /** Fails every call made after the process died. */
    public fun check() {
        if (dead) throw SimulatedCrash(null)
    }

    /** A new process starts on the same stored state. */
    public fun restart() {
        dead = false
    }
}

/** Runs [block] and returns the [SimulatedCrash] it must throw; fails when it returns normally. */
public suspend fun expectCrash(block: suspend () -> Unit): SimulatedCrash {
    try {
        block()
    } catch (crash: SimulatedCrash) {
        return crash
    }
    throw AssertionError("expected a simulated crash")
}
