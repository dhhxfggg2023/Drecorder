package dev.drecorder.audio.edit

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lets one decode at a time run, and stops the previous one.
 *
 * The editor decodes the visible window again every time the window moves.
 * A pinch gesture moves the window many times a second, and a decode that is
 * already running does **not** stop when its coroutine is cancelled: the
 * decoder sits in a blocking call and only notices the cancellation when it
 * returns. Without a gate, every step of the gesture would leave another
 * decode running, and the phone would be busy decoding windows nobody is
 * looking at any more (DESIGN §12.4).
 *
 * The gate therefore hands out one flag per run and stops the previous run
 * when a new one starts.
 *
 * Pure and Android-free, so it can be unit-tested off-device.
 */
class PeakDecodeGate {

    private val lock = Any()
    private var current: AtomicBoolean? = null

    /**
     * Starts a new run and stops the one before it.
     *
     * @return the flag the new run has to watch; setting it stops that run
     */
    fun start(): AtomicBoolean {
        val flag = AtomicBoolean(false)
        synchronized(lock) {
            current?.set(true)
            current = flag
        }
        return flag
    }

    /**
     * Stops the run that is in flight, if any.
     *
     * Called when the screen goes away: a decode that keeps running after
     * the editor is closed is a decode nobody waits for.
     */
    fun stop() {
        synchronized(lock) {
            current?.set(true)
            current = null
        }
    }

    /** Whether a run is in flight. */
    fun isRunning(): Boolean = synchronized(lock) { current != null }
}
