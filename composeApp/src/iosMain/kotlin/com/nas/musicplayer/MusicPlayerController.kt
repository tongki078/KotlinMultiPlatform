package com.nas.musicplayer

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFoundation.*
import platform.AVFAudio.*
import platform.CoreMedia.*
import platform.Foundation.NSURL
import platform.darwin.NSObject

actual class MusicPlayerController {
    private var player: AVPlayer? = null
    private var timeObserver: Any? = null

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

    @OptIn(ExperimentalForeignApi::class)
    actual fun playSong(song: Song, playlist: List<Song>) {
        val urlString = song.streamUrl ?: return
        
        // URL 처리 보완: 로컬 파일 경로와 네트워크 URL 구분
        val url = if (urlString.startsWith("/")) {
            NSURL.fileURLWithPath(urlString)
        } else {
            NSURL.URLWithString(urlString)
        } ?: return

        // Audio Session 설정 (소리 재생 권한 활성화)
        try {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(AVAudioSessionCategoryPlayback, error = null)
            session.setActive(true, error = null)
        } catch (e: Exception) {
            println("iOS AVAudioSession Error: ${e.message}")
        }
        
        removeTimeObserver()
        player?.pause()

        _currentSong.value = song
        _currentPlaylist.value = playlist
        _currentIndex.value = playlist.indexOf(song).coerceAtLeast(0)

        // AVPlayer 인스턴스 생성 및 재생
        player = AVPlayer(url)
        player?.play()
        _isPlaying.value = true
        
        setupTimeObserver()
    }

    actual fun togglePlayPause() {
        if (_isPlaying.value) {
            player?.pause()
        } else {
            player?.play()
        }
        _isPlaying.value = !_isPlaying.value
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun setupTimeObserver() {
        val interval = CMTimeMakeWithSeconds(0.5, 1000)
        timeObserver = player?.addPeriodicTimeObserverForInterval(interval, null) { time ->
            val seconds = CMTimeGetSeconds(time)
            _currentPosition.value = (seconds * 1000).toLong()
            
            val durationTime = player?.currentItem?.duration
            if (durationTime != null) {
                val durationSeconds = CMTimeGetSeconds(durationTime)
                if (!durationSeconds.isNaN()) {
                    _duration.value = (durationSeconds * 1000).toLong()
                }
            }
        }
    }

    private fun removeTimeObserver() {
        timeObserver?.let {
            player?.removeTimeObserver(it)
            timeObserver = null
        }
    }

    actual fun playNext() {
        val nextIndex = _currentIndex.value + 1
        if (nextIndex < _currentPlaylist.value.size) {
            playSong(_currentPlaylist.value[nextIndex], _currentPlaylist.value)
        }
    }

    actual fun playPrevious() {
        val prevIndex = _currentIndex.value - 1
        if (prevIndex >= 0) {
            playSong(_currentPlaylist.value[prevIndex], _currentPlaylist.value)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun seekTo(position: Long) {
        val time = CMTimeMakeWithSeconds(position / 1000.0, 1000)
        player?.seekToTime(time)
    }

    actual fun skipToIndex(index: Int) {
        if (index in _currentPlaylist.value.indices) {
            playSong(_currentPlaylist.value[index], _currentPlaylist.value)
        }
    }

    actual fun setVolume(volume: Float) {
        _volume.value = volume
        player?.volume = volume
    }
}
