package com.nas.musicplayer.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.nas.musicplayer.Song

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val songs: List<Song> = emptyList()
)
