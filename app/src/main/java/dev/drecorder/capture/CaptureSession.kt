package dev.drecorder.capture

import dev.drecorder.audio.CaptureState
import dev.drecorder.audio.RecordingHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process bridge between [CaptureService] (which owns the engine) and the
 * Compose UI. Single source of truth for "what is the recorder doing right now".
 */
object CaptureSession {

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _targetSilent = MutableStateFlow(false)
    val targetSilent: StateFlow<Boolean> = _targetSilent.asStateFlow()

    private val _lastHandle = MutableStateFlow<RecordingHandle?>(null)
    val lastHandle: StateFlow<RecordingHandle?> = _lastHandle.asStateFlow()

    internal fun onState(state: CaptureState) {
        _state.value = state
    }

    internal fun onLevel(level: Float) {
        _level.value = level
    }

    internal fun onTargetSilent(silent: Boolean) {
        _targetSilent.value = silent
    }

    internal fun onFinished(handle: RecordingHandle?) {
        if (handle != null) _lastHandle.value = handle
        _level.value = 0f
    }

    internal fun fail(message: String) {
        _state.value = CaptureState.Failed(message)
        _level.value = 0f
    }

    /** Called when the user starts a new session so stale results do not linger. */
    internal fun resetForNewSession() {
        _state.value = CaptureState.Preparing
        _level.value = 0f
        _targetSilent.value = false
    }
}
