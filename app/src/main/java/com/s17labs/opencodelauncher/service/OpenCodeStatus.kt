package com.s17labs.opencodelauncher.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for the opencode web process state.
 * The foreground service (same process) writes; Compose screens read.
 */
object OpenCodeStatus {
    sealed interface Value {
        data object Stopped : Value
        data object Starting : Value
        data class Running(val url: String) : Value
        data class Failed(val message: String) : Value
    }

    private val _state = MutableStateFlow<Value>(Value.Stopped)
    val state: StateFlow<Value> = _state.asStateFlow()

    fun set(v: Value) {
        _state.value = v
    }
}
