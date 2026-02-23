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
        val streamUrl = song.streamUrl ?: return
        println("iOS PlayRequest - Song: ${song.name}, Artist: ${song.artist}")
        
        val fileManager = NSFileManager.defaultManager
        val url: NSURL? = if (streamUrl.startsWith("/") || streamUrl.contains("/Documents/")) {
            // [개선] 가장 강력한 로컬 파일 탐색 로직
            val documentsPath = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
            
            // 1. 비교용 키 생성 함수: 오직 글자와 숫자만 남김 (공백, 괄호 등 제거)
            fun makeSimpleKey(text: String): String = text.lowercase()
                .filter { it.isLetterOrDigit() }

            val targetTitleKey = makeSimpleKey(song.name ?: "")
            
            // 2. Documents 폴더 전수 조사
            val allFiles = fileManager.contentsOfDirectoryAtPath(documentsPath, null) as? List<String>
            
            // 3. 파일 목록 중 음악 파일이면서 제목 키와 매칭되는 것 찾기
            val matchedFileName = allFiles?.filter { f ->
                val lower = f.lowercase()
                lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".wav")
            }?.find { f ->
                val fileTitleKey = makeSimpleKey(f.substringBeforeLast("."))
                // 파일명이 제목을 포함하거나, 제목이 파일명을 포함하는지 확인
                fileTitleKey.contains(targetTitleKey) || targetTitleKey.contains(fileTitleKey)
            }

            val finalPath = if (matchedFileName != null) {
                "$documentsPath/$matchedFileName"
            } else {
                // 매칭 실패 시 원본 streamUrl 시도 (하지만 샌드박스 경로 변경 이슈 대응을 위해 현재 docs 경로와 결합)
                val fileName = (streamUrl as NSString).lastPathComponent
                val fallbackPath = "$documentsPath/$fileName"
                if (fileManager.fileExistsAtPath(fallbackPath)) fallbackPath else null
            }

            if (finalPath == null) {
                println("iOS Player Error: Local file NOT found for ${song.name}")
                return
            }
            
            println("iOS Player: Final matched path -> $finalPath")
            NSURL.fileURLWithPath(finalPath)
        } else {
            // 2. 원격 URL 처리
            var urlString = if (streamUrl.contains("music.yommi.mywire.org")) {
                streamUrl.replace("https://", "http://")
            } else {
                streamUrl
            }
            val decoded = (urlString as NSString).stringByRemovingPercentEncoding() ?: urlString
            val allowedChars = NSCharacterSet.URLQueryAllowedCharacterSet().mutableCopy() as NSMutableCharacterSet
            allowedChars.addCharactersInString(":#") 
            val encodedUrl = (decoded as NSString).stringByAddingPercentEncodingWithAllowedCharacters(allowedChars) ?: decoded
            NSURL.URLWithString(encodedUrl)
        }

        if (url == null) return

        setupAudioSession()
        removeTimeObserver()
        removeEndOfTimeObserver()
        player?.pause()
        
        // AVURLAsset을 사용하여 더 안정적으로 로드
        val asset = AVURLAsset.URLAssetWithURL(url, null)
        val playerItem = AVPlayerItem.playerItemWithAsset(asset)
        
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
        cachedArtwork?.let { info[MPMediaItemPropertyArtwork] = it }
        infoCenter.setNowPlayingInfo(info)

        val imageUrlString = song.metaPoster ?: song.streamUrl
        if (imageUrlString != null && imageUrlString != currentArtworkUrl) {
            mainScope.launch {
                try {
                    val artwork = loadArtwork(imageUrlString)
                    if (artwork != null) {
                        cachedArtwork = artwork
                        currentArtworkUrl = imageUrlString
                        info[MPMediaItemPropertyArtwork] = artwork
                        infoCenter.setNowPlayingInfo(info)
                    }
                } catch (e: Exception) {
                    println("NowPlayingInfo Image Update Error: ${e.message}")
                }
            }
        }
    }

    private suspend fun loadArtwork(url: String): MPMediaItemArtwork? = withContext(Dispatchers.Default) {
        try {
            val nsUrl = if (url.startsWith("/")) NSURL.fileURLWithPath(url) else NSURL.URLWithString(url)
            nsUrl?.let { validUrl ->
                val data = NSData.dataWithContentsOfURL(validUrl)
                data?.let { validData ->
                    val image = UIImage.imageWithData(validData)
                    image?.let { validImage ->
                        return@withContext MPMediaItemArtwork(boundsSize = validImage.size) { _ -> validImage }
                    }
                }
            }
        } catch (e: Exception) {
            println("iOS Artwork Load Error: ${e.message}")
        }
        return@withContext null
    }

    actual fun togglePlayPause() {
        if (_isPlaying.value) player?.pause() else player?.play()
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
                if (!durationSeconds.isNaN()) _duration.value = (durationSeconds * 1000).toLong()
            }
            val infoCenter = MPNowPlayingInfoCenter.defaultCenter()
            val currentInfo = infoCenter.nowPlayingInfo()?.toMutableMap() ?: mutableMapOf()
            currentInfo[MPNowPlayingInfoPropertyElapsedPlaybackTime] = _currentPosition.value / 1000.0
            infoCenter.setNowPlayingInfo(currentInfo)
        }
    }

    private fun removeTimeObserver() {
        timeObserver?.let { player?.removeTimeObserver(it); timeObserver = null }
    }

    private fun removeEndOfTimeObserver() {
        playerItemEndOfTimeObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it); playerItemEndOfTimeObserver = null }
    }

    actual fun playNext() {
        val nextIndex = _currentIndex.value + 1
        if (nextIndex < _currentPlaylist.value.size) playSong(_currentPlaylist.value[nextIndex], _currentPlaylist.value)
        else { _isPlaying.value = false; _currentSong.value?.let { updateNowPlayingInfo(it) } }
    }

    actual fun playPrevious() {
        val prevIndex = _currentIndex.value - 1
        if (prevIndex >= 0) playSong(_currentPlaylist.value[prevIndex], _currentPlaylist.value)
    }

    actual fun seekTo(position: Long) {
        val time = CMTimeMakeWithSeconds(position / 1000.0, 1000)
        player?.seekToTime(time)
        _currentPosition.value = position
        _currentSong.value?.let { updateNowPlayingInfo(it) }
    }

    actual fun skipToIndex(index: Int) {
        if (index in _currentPlaylist.value.indices) playSong(_currentPlaylist.value[index], _currentPlaylist.value)
    }

    actual fun setVolume(volume: Float) {
        _volume.value = volume
        player?.volume = volume
    }
}
