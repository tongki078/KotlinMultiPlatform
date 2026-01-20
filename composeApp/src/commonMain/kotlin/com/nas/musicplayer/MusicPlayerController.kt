package com.nas.musicplayer

import kotlinx.coroutines.flow.StateFlow

expect class MusicPlayerController {
    val currentSong: StateFlow<Song?>
    val isPlaying: StateFlow<Boolean>
    val currentPosition: StateFlow<Long>
    val duration: StateFlow<Long>
    val currentPlaylist: StateFlow<List<Song>>
    val currentIndex: StateFlow<Int>
    val volume: StateFlow<Float>

    fun playSong(song: Song, playlist: List<Song>)
    fun togglePlayPause()
    fun playNext()
    fun playPrevious()
    fun seekTo(position: Long)
    fun skipToIndex(index: Int)
    fun setVolume(volume: Float)
}
