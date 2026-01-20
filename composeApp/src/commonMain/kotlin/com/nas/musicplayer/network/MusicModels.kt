package com.nas.musicplayer.network

import com.nas.musicplayer.Song
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchResponse(
    @SerialName("count") val count: Int = 0,
    @SerialName("list") val list: List<Song> = emptyList()
)

fun SearchResponse.toSongList(): List<Song> {
    return this.list.map { item ->
        item.copy(
            id = if (item.id == 0L) item.path?.hashCode()?.toLong() ?: 0L else item.id,
            artist = if (item.isDir) "폴더" else item.artist,
            albumName = item.parentPath ?: item.albumName
        )
    }
}
