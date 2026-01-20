package com.nas.musicplayer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MusicPlayerState(private val geminiService: GeminiService) {

    private val _outputText = MutableStateFlow<String>("")
    val outputText: StateFlow<String> = _outputText

    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    fun generateText(prompt: String) {
        coroutineScope.launch {
            val result = geminiService.generateText(prompt)
            _outputText.value = result ?: "An error occurred. Please try again later."
        }
    }
}