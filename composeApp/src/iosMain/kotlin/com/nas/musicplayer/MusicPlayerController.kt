package com.nas.musicplayer

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFoundation.*
import platform.AVFAudio.*
import platform.CoreMedia.*
import platform.Foundation.*
import platform.darwin.NSObject

actual class MusicPlayerController {
    private var player: AVPlayer? = null
    private var timeObserver: Any? = null
    private var playerItemEndOfTimeObserver: Any? = null

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
        println("iOS Player: Playing ${song.name}")
        
        // 1. URL 처리: SSL 오류 우회를 위해 HTTP 전환 및 공백 인코딩
        var targetUrl = if (urlString.contains("music.yommi.mywire.org")) {
            urlString.replace("https://", "http://")
        } else {
            urlString
        }
        
        // Base64 내부의 공백 등 특수문자 처리
        targetUrl = targetUrl.replace(" ", "%20")

        // 2. AAC 포맷 힌트 추가 (Fragment 방식)
        if (targetUrl.startsWith("http") && !targetUrl.contains("#")) {
            targetUrl += "#.aac"
        }

        val url = NSURL.URLWithString(targetUrl)
        if (url == null) {
            println("iOS Player Error: NSURL creation failed for $targetUrl")
            return
        }

        // 3. 오디오 세션 활성화
        try {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(AVAudioSessionCategoryPlayback, error = null)
            session.setActive(true, error = null)
        } catch (e: Exception) {
            println("iOS AVAudioSession Error: ${e.message}")
        }
        
        removeTimeObserver()
        removeEndOfTimeObserver()
        player?.pause()
        
        // 4. AVPlayerItem 및 플레이어 생성
        val playerItem = AVPlayerItem.playerItemWithURL(url)
        playerItem.preferredForwardBufferDuration = 5.0
        
        // 다음 곡 자동 재생을 위한 옵저버 등록
        playerItemEndOfTimeObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = playerItem,
            queue = NSOperationQueue.mainQueue,
            usingBlock = { _ ->
                playNext()
            }
        )
        
        player = AVPlayer.playerWithPlayerItem(playerItem)
        player?.automaticallyWaitsToMinimizeStalling = true
        player?.volume = _volume.value

        _currentSong.value = song
        _currentPlaylist.value = playlist
        _currentIndex.value = playlist.indexOfFirst { it.id == song.id }.coerceAtLeast(0)

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

    private fun removeEndOfTimeObserver() {
        playerItemEndOfTimeObserver?.let {
            NSNotificationCenter.defaultCenter.removeObserver(it)
            playerItemEndOfTimeObserver = null
        }
    }

    actual fun playNext() {
        val nextIndex = _currentIndex.value + 1
        if (nextIndex < _currentPlaylist.value.size) {
            playSong(_currentPlaylist.value[nextIndex], _currentPlaylist.value)
        } else {
            // 리스트의 마지막 곡이면 중지하거나 처음으로 돌아갈 수 있음
            _isPlaying.value = false
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
