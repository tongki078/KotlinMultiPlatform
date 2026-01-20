package com.nas.musicplayer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope

open class BaseViewModel : ViewModel() {
    val coroutineScope: CoroutineScope get() = viewModelScope
}
