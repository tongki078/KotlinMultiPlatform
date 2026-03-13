package com.nas.musicplayer.network

import kotlinx.serialization.Serializable

@Serializable
data class Artist(
    val name: String,
    val cover: String?
)

interface MusicApiService {
    suspend fun getTop100(): SearchResponse
    suspend fun search(searchQuery: String): SearchResponse
    suspend fun getLyrics(artist: String, title: String): LrcResponse?
    suspend fun getArtists(folderType: String): List<Artist>
}
