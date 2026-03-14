package com.nas.musicplayer.network

import com.nas.musicplayer.Song
import kotlinx.serialization.Serializable

@Serializable
data class Artist(
    val name: String,
    val cover: String?
)

interface MusicApiService {
    suspend fun getTop100(): List<Song>
    suspend fun search(searchQuery: String): List<Song>
    suspend fun getLyrics(artist: String, title: String): LrcResponse?
    suspend fun getArtists(folderType: String): List<Artist>
}
