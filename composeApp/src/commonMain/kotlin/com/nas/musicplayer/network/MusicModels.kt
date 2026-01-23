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
    val baseUrl = "https://music.yommi.mywire.org/gds_dviewer/normal/explorer/"
    return this.list.map { item ->
        // streamUrl이 상대 경로인 경우를 대비하여 절대 경로로 보정 (iOS 필수 조치)
        val correctedStreamUrl = item.streamUrl?.let { url ->
            if (!url.startsWith("http")) {
                baseUrl + url.removePrefix("/")
            } else {
                url
            }
        }
        
        item.copy(
            id = if (item.id == 0L) item.path?.hashCode()?.toLong() ?: 0L else item.id,
            artist = if (item.isDir) "폴더" else item.artist,
            albumName = item.parentPath ?: item.albumName,
            streamUrl = correctedStreamUrl
        )
    }
}
