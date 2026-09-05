package com.meta.wearable.dat.display

import com.meta.wearable.dat.display.types.DisplayState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class Display {
    val state: StateFlow<DisplayState> = MutableStateFlow(DisplayState.STOPPED).asStateFlow()
    fun stop() = Unit
}
