package com.nas.musicplayer

data class Album(
    val name: String,
    val artist: String,
    val albumArtRes: Int? = null,
    val year: Int = 0,
    val songs: List<Song> = emptyList(),
    val imageUrl: String? = null
)
