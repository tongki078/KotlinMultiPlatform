package com.nas.musicplayer

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class MainViewModel : BaseViewModel() {

    private val musicPlayerState: MusicPlayerState

    val outputText: StateFlow<String>

    init {
        val apiKey = ApiKeyProvider.getGeminiApiKey()
        val geminiService = GeminiService(apiKey)
        musicPlayerState = MusicPlayerState(geminiService)
        outputText = musicPlayerState.outputText.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = ""
        )
    }

    // Factory 추가
    companion object {
        fun Factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MainViewModel()
            }
        }
    }

    fun generateText(prompt: String) {
        musicPlayerState.generateText(prompt)
    }
}
