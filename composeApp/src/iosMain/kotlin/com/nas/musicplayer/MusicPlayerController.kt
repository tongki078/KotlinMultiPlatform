package com.nas.musicplayer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual class MusicPlayerController {
    private val _currentSong = MutableStateFlow<Song?>(null)
    actual val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    actual val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    actual val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    actual val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _currentPlaylist = MutableStateFlow<List<Song>>(emptyList())
    actual val currentPlaylist: StateFlow<List<Song>> = _currentPlaylist.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    actual val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _volume = MutableStateFlow(1.0f)
    actual val volume: StateFlow<Float> = _volume.asStateFlow()

    actual fun playSong(song: Song, playlist: List<Song>) {
        _currentSong.value = song
        _currentPlaylist.value = playlist
    }

    actual fun togglePlayPause() {
        _isPlaying.value = !_isPlaying.value
    }

    actual fun playNext() {}
    actual fun playPrevious() {}
    actual fun seekTo(position: Long) {}
    actual fun skipToIndex(index: Int) {}
    actual fun setVolume(volume: Float) {
        _volume.value = volume
    }
}
