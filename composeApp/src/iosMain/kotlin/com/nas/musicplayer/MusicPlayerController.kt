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
import platform.MediaPlayer.*
import platform.UIKit.*
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
actual class MusicPlayerController {
    private var player: AVPlayer? = null
    private var timeObserver: Any? = null
    private var playerItemEndOfTimeObserver: Any? = null
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // 현재 곡의 아트워크 캐시
    private var cachedArtwork: MPMediaItemArtwork? = null
    private var currentArtworkUrl: String? = null

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

    init {
        setupAudioSession()
        setupRemoteCommandCenter()
    }

    private fun setupAudioSession() {
        try {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(AVAudioSessionCategoryPlayback, error = null)
            session.setActive(true, error = null)
        } catch (e: Exception) {
            println("iOS AVAudioSession Setup Error: ${e.message}")
        }
    }

    actual fun playSong(song: Song, playlist: List<Song>) {
        val urlString = song.streamUrl ?: return
        
        var targetUrl = if (urlString.contains("music.yommi.mywire.org")) {
            urlString.replace("https://", "http://")
        } else {
            urlString
        }
        targetUrl = targetUrl.replace(" ", "%20")

        if (targetUrl.startsWith("http") && !targetUrl.contains("#")) {
            targetUrl += "#.aac"
        }

        val url = NSURL.URLWithString(targetUrl) ?: return

        setupAudioSession()
        
        removeTimeObserver()
        removeEndOfTimeObserver()
        player?.pause()
        
        val playerItem = AVPlayerItem.playerItemWithURL(url)
        playerItem.preferredForwardBufferDuration = 5.0
        
        playerItemEndOfTimeObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = playerItem,
            queue = NSOperationQueue.mainQueue,
            usingBlock = { _ -> playNext() }
        )
        
        if (player == null) {
            player = AVPlayer.playerWithPlayerItem(playerItem)
        } else {
            player?.replaceCurrentItemWithPlayerItem(playerItem)
        }
        
        player?.automaticallyWaitsToMinimizeStalling = true
        player?.volume = _volume.value

        _currentSong.value = song
        _currentPlaylist.value = playlist
        _currentIndex.value = playlist.indexOfFirst { it.id == song.id }.coerceAtLeast(0)

        player?.play()
        _isPlaying.value = true
        
        // 새로운 곡 시작 시 캐시 초기화 및 정보 업데이트
        cachedArtwork = null
        currentArtworkUrl = null
        updateNowPlayingInfo(song)
        setupTimeObserver()
    }

    private fun setupRemoteCommandCenter() {
        val commandCenter = MPRemoteCommandCenter.sharedCommandCenter()
        
        commandCenter.playCommand.setEnabled(true)
        commandCenter.playCommand.addTargetWithHandler { _ ->
            if (!_isPlaying.value) togglePlayPause()
            MPRemoteCommandHandlerStatusSuccess
        }
        
        commandCenter.pauseCommand.setEnabled(true)
        commandCenter.pauseCommand.addTargetWithHandler { _ ->
            if (_isPlaying.value) togglePlayPause()
            MPRemoteCommandHandlerStatusSuccess
        }
        
        commandCenter.nextTrackCommand.setEnabled(true)
        commandCenter.nextTrackCommand.addTargetWithHandler { _ ->
            playNext()
            MPRemoteCommandHandlerStatusSuccess
        }
        
        commandCenter.previousTrackCommand.setEnabled(true)
        commandCenter.previousTrackCommand.addTargetWithHandler { _ ->
            playPrevious()
            MPRemoteCommandHandlerStatusSuccess
        }
    }

    private fun updateNowPlayingInfo(song: Song) {
        val infoCenter = MPNowPlayingInfoCenter.defaultCenter()
        val info = mutableMapOf<Any?, Any?>()
        
        info[MPMediaItemPropertyTitle] = song.name ?: "Unknown"
        info[MPMediaItemPropertyArtist] = song.artist
        info[MPMediaItemPropertyAlbumTitle] = song.albumName
        info[MPNowPlayingInfoPropertyPlaybackRate] = if (_isPlaying.value) 1.0 else 0.0
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = _currentPosition.value / 1000.0
        
        // 캐시된 이미지가 있으면 즉시 사용 (깜빡임 방지)
        cachedArtwork?.let {
            info[MPMediaItemPropertyArtwork] = it
        }

        infoCenter.setNowPlayingInfo(info)

        // 이미지 로드 로직 (캐시가 없거나 URL이 바뀐 경우에만)
        val imageUrlString = song.metaPoster ?: song.streamUrl
        if (imageUrlString != null && imageUrlString != currentArtworkUrl) {
            mainScope.launch {
                val artwork = loadArtwork(imageUrlString)
                if (artwork != null) {
                    cachedArtwork = artwork
                    currentArtworkUrl = imageUrlString
                    // 이미지가 로드되면 다시 한 번 업데이트
                    info[MPMediaItemPropertyArtwork] = artwork
                    infoCenter.setNowPlayingInfo(info)
                }
            }
        }
    }

    private suspend fun loadArtwork(url: String): MPMediaItemArtwork? = withContext(Dispatchers.Default) {
        try {
            val nsUrl = if (url.startsWith("/")) NSURL.fileURLWithPath(url) else NSURL.URLWithString(url)
            val data = NSData.dataWithContentsOfURL(nsUrl!!)
            if (data != null) {
                val image = UIImage.imageWithData(data)
                if (image != null) {
                    return@withContext MPMediaItemArtwork(boundsSize = image.size) { _ -> image }
                }
            }
        } catch (e: Exception) {
            println("iOS Artwork Load Error: ${e.message}")
        }
        return@withContext null
    }

    actual fun togglePlayPause() {
        if (_isPlaying.value) {
            player?.pause()
        } else {
            player?.play()
        }
        _isPlaying.value = !_isPlaying.value
        _currentSong.value?.let { updateNowPlayingInfo(it) }
    }

    private fun setupTimeObserver() {
        val interval = CMTimeMakeWithSeconds(1.0, 1000)
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
            
            // 재생 시간 업데이트 시에는 이미지를 다시 로드하지 않도록 하여 깜빡임 방지
            val infoCenter = MPNowPlayingInfoCenter.defaultCenter()
            val currentInfo = infoCenter.nowPlayingInfo()?.toMutableMap() ?: mutableMapOf()
            currentInfo[MPNowPlayingInfoPropertyElapsedPlaybackTime] = _currentPosition.value / 1000.0
            infoCenter.setNowPlayingInfo(currentInfo)
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
            _isPlaying.value = false
            _currentSong.value?.let { updateNowPlayingInfo(it) }
        }
    }

    actual fun playPrevious() {
        val prevIndex = _currentIndex.value - 1
        if (prevIndex >= 0) {
            playSong(_currentPlaylist.value[prevIndex], _currentPlaylist.value)
        }
    }

    actual fun seekTo(position: Long) {
        val time = CMTimeMakeWithSeconds(position / 1000.0, 1000)
        player?.seekToTime(time)
        _currentPosition.value = position
        _currentSong.value?.let { updateNowPlayingInfo(it) }
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
