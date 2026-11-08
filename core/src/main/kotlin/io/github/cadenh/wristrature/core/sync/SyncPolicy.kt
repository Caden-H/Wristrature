package io.github.cadenh.wristrature.core.sync

/** What the watch knows about the place in a book. */
data class LocalPlace(
    val progress: String,
    val percentage: Double,
    /** When the place last changed on this watch, in milliseconds. 0 = not known. */
    val changedAtMs: Long = 0,
)

@kotlinx.serialization.Serializable
enum class JumpMode { ASK, AUTO }

sealed interface PullDecision {
    /** Nothing to do: no remote place, it is ours, it is the same, or it is behind us. */
    data object None : PullDecision

    /** Another device is further ahead. Show a card and let the user choose. */
    data class Offer(val remote: KoProgress) : PullDecision

    /** Another device is further ahead and the user turned on automatic jumping. */
    data class Jump(val remote: KoProgress) : PullDecision
}

/**
 * The rules for using a position pulled from the server.
 *
 * * A place this watch wrote itself (same device ID) is never acted on.
 * * A place from another device is news when it is further ahead, or when it was written after the last time this watch's
 *   place changed. The second rule matters because devices measure the percent differently (an e-ink reader counts pages,
 *   this watch counts characters): the same spot can be a smaller percent on the other device. Such a place is only ever
 *   offered, never jumped to on its own, because it may be behind.
 * * The reading position never moves backward automatically.
 * * Automatic jumping only happens while the user has not started to scroll since the book opened. Otherwise the
 *   jump becomes an offer.
 */
object SyncPolicy {
    /** Percentages closer than this are treated as equal (KOReader's percentage is rounded). */
    const val EPSILON = 0.001

    /** The server's clock and the watch's clock may differ a little. */
    const val CLOCK_SLACK_MS = 2_000L

    fun decidePull(
        local: LocalPlace?,
        remote: KoProgress?,
        myDeviceId: String,
        mode: JumpMode,
        userHasScrolled: Boolean,
    ): PullDecision {
        if (remote == null) return PullDecision.None
        if (remote.deviceId.isNotEmpty() && remote.deviceId == myDeviceId) return PullDecision.None
        if (local != null && remote.progress.isNotEmpty() && remote.progress == local.progress) return PullDecision.None
        val localPct = local?.percentage ?: 0.0
        val ahead = remote.percentage > localPct + EPSILON
        val newer = remote.timestamp != null && local != null && local.changedAtMs > 0 &&
            remote.timestamp * 1000 > local.changedAtMs + CLOCK_SLACK_MS
        if (!ahead && !newer) return PullDecision.None
        return if (ahead && mode == JumpMode.AUTO && !userHasScrolled) PullDecision.Jump(remote) else PullDecision.Offer(remote)
    }
}

/**
 * Decides when a place is sent while reading. Closing the book or turning the screen off always sends (call
 * [forced]). While reading, a send needs enough movement and enough time since the last one, so the radio is not
 * woken for every page.
 */
class PushThrottle(
    private val minScreens: Int = 5,
    private val minIntervalMs: Long = 60_000,
    private val clock: () -> Long,
) {
    private var lastPushAt: Long = Long.MIN_VALUE
    private var screensSince = 0

    /** Call when the user has scrolled by about one screen. Returns true when a send is due now. */
    fun onScreenScrolled(): Boolean {
        screensSince++
        return screensSince >= minScreens && (lastPushAt == Long.MIN_VALUE || clock() - lastPushAt >= minIntervalMs)
    }

    fun onPushed() {
        lastPushAt = clock()
        screensSince = 0
    }

    /** True if something changed since the last send, for close and screen off. */
    fun hasUnsentMovement(): Boolean = screensSince > 0
}
