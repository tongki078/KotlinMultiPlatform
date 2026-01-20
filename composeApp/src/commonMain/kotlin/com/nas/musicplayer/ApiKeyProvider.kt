package com.nas.musicplayer

expect object ApiKeyProvider {
    fun getGeminiApiKey(): String
}
