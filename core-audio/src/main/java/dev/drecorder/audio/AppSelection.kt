package dev.drecorder.audio

/**
 * The apps the user ticked in the picker, carried as uids.
 *
 * Kept in `core-audio` so the UI never has to know that
 * `AudioPlaybackCaptureConfiguration` filters by uid rather than package name.
 */
data class AppSelection(val uids: List<Int>) {

    val distinctUids: List<Int> get() = uids.distinct()

    /** Null when nothing is selected: the caller should fall back to AllMedia. */
    fun toCaptureScope(): CaptureScope? =
        if (distinctUids.isEmpty()) null else CaptureScope.OnlyApps(distinctUids)
}
