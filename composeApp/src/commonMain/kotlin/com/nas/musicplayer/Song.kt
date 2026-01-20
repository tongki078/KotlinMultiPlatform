package com.nas.musicplayer

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "playlist_table")
data class Song(
    @PrimaryKey(autoGenerate = true)
    val dbId: Int = 0,
    @SerialName("id") val id: Long = 0L,
    @SerialName("name") val name: String? = null,
    @SerialName("path") val path: String? = null,
    @SerialName("is_dir") val isDir: Boolean = false,
    @SerialName("size") val size: Long = 0L,
    @SerialName("category") val category: String? = null,
    @SerialName("stream_url") val streamUrl: String? = null,
    @SerialName("mtime") val mtime: String? = null,
    @SerialName("parent_path") val parentPath: String? = null,
    @SerialName("mtime_ts") val mtimeTs: Double? = null,
    @SerialName("meta_id") val metaId: String? = null,
    @SerialName("meta_poster") val metaPoster: String? = null,
    val artist: String = "Unknown Artist",
    val albumName: String = "Unknown Album",
    val albumArtRes: Int? = null,
    val albumInfo: String? = null 
)
