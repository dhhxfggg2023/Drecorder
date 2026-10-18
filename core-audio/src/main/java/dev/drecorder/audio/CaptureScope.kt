package dev.drecorder.audio

/**
 * What a capture session listens to.
 *
 * `AudioPlaybackCaptureConfiguration` forbids combining `addMatchingUsage` with
 * `excludeUsage`, and `addMatchingUid` with `excludeUid` — so the scope is a
 * closed choice of exactly two shapes rather than a free-form filter set
 * (docs/DESIGN.md §2.3).
 */
sealed interface CaptureScope {

    /** Everything the system is willing to let us capture: USAGE_MEDIA / GAME / UNKNOWN. */
    data object AllMedia : CaptureScope

    /**
     * Only the given application uids. Several uids may be given because one
     * package can play through more than one uid (isolated processes).
     */
    data class OnlyApps(val uids: List<Int>) : CaptureScope {
        init {
            require(uids.isNotEmpty()) { "OnlyApps requires at least one uid" }
            require(uids.all { it >= 0 }) { "uids must be non-negative" }
        }

        val distinctUids: List<Int> get() = uids.distinct()
    }
}
