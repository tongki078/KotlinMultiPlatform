package com.nas.musicplayer

import androidx.lifecycle.viewModelScope
import com.nas.musicplayer.db.PlaylistEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class MusicPlayerViewModel(
    private val musicPlayerController: MusicPlayerController,
    private val repository: MusicRepository
) : BaseViewModel() {

    val currentSong: StateFlow<Song?> = musicPlayerController.currentSong
    val isPlaying: StateFlow<Boolean> = musicPlayerController.isPlaying
    val currentPosition: StateFlow<Long> = musicPlayerController.currentPosition
    val duration: StateFlow<Long> = musicPlayerController.duration
    val currentPlaylist: StateFlow<List<Song>> = musicPlayerController.currentPlaylist
    val currentIndex: StateFlow<Int> = musicPlayerController.currentIndex
    val volume: StateFlow<Float> = musicPlayerController.volume

    val playlistItems: StateFlow<List<PlaylistEntity>> = repository.allPlaylists
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun playSong(song: Song, playlist: List<Song>) {
        musicPlayerController.playSong(song, playlist)
    }

    fun togglePlayPause() {
        musicPlayerController.togglePlayPause()
    }

    fun playNext() {
        musicPlayerController.playNext()
    }

    fun playPrevious() {
        musicPlayerController.playPrevious()
    }

    fun seekTo(position: Long) {
        musicPlayerController.seekTo(position)
    }

    fun skipToIndex(index: Int) {
        musicPlayerController.skipToIndex(index)
    }

    fun setVolume(volume: Float) {
        musicPlayerController.setVolume(volume)
    }
}
