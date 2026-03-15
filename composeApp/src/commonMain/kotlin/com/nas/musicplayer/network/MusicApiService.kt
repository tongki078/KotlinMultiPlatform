package com.nas.musicplayer.network

import com.nas.musicplayer.Song
import kotlinx.serialization.Serializable

@Serializable
data class Artist(
    val name: String,
    val cover: String?
)

@Serializable
data class BrowseItem(
    val id: Long? = null,
    val name: String,
    val path: String,
    val is_dir: Boolean,
    val cover: String? = null,
    val stream_url: String? = null,
    val artist: String? = null,
    val albumName: String? = null,
    val meta_poster: String? = null
)

interface MusicApiService {
    suspend fun getTop100(): List<Song>
    suspend fun search(searchQuery: String): List<Song>
    suspend fun getLyrics(artist: String, title: String): LrcResponse?
    suspend fun getArtists(folderType: String): List<Artist>
    suspend fun browseLibrary(path: String): List<BrowseItem>
}
