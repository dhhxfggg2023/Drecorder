package dev.drecorder.capture

/**
 * Whether any Drecorder screen is currently visible.
 *
 * The recording overlay exists for the background case, so it has to know when to step
 * aside: one process-wide flag written by the activity's lifecycle and read by the
 * overlay ticker is enough, and it avoids binding the service to the UI.
 */
object AppVisibility {
    @Volatile
    var visible: Boolean = false
}
