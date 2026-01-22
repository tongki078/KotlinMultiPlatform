package com.nas.musicplayer.network

import kotlinx.serialization.Serializable

@Serializable
data class LrcResponse(
    val id: Long? = null,
    val name: String? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null // LRC 형식의 동기화 가사
)
