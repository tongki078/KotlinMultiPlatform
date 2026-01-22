package com.nas.musicplayer

import android.content.ComponentName
import android.content.Context
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

actual class MusicPlayerController(private val context: Context) {

    private var player: Player? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    private val _currentSong = MutableStateFlow<Song?>(null)
    actual val currentSong = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    actual val isPlaying = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    actual val currentPosition = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    actual val duration = _duration.asStateFlow()

    private val _currentPlaylist = MutableStateFlow<List<Song>>(emptyList())
    actual val currentPlaylist = _currentPlaylist.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    actual val currentIndex = _currentIndex.asStateFlow()

    private val _volume = MutableStateFlow(1.0f)
    actual val volume = _volume.asStateFlow()

    init {
        initializeController()
        
        // 가사 싱크를 위해 16ms(60fps) 주기로 위치 추적
        coroutineScope.launch {
            while (true) {
                player?.let { p ->
                    if (p.isPlaying) {
                        _currentPosition.value = p.currentPosition
                        _duration.value = p.duration
                    }
                }
                delay(16) 
            }
        }
    }

    private fun initializeController() {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                val controller = controllerFuture?.get()
                player = controller
                player?.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlayingValue: Boolean) {
                        _isPlaying.value = isPlayingValue
                    }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        val index = player?.currentMediaItemIndex ?: 0
                        if (index in _currentPlaylist.value.indices) {
                            _currentIndex.value = index
                            _currentSong.value = _currentPlaylist.value[index]
                        }
                    }
                    override fun onAudioSessionIdChanged(audioSessionId: Int) {
                        setupLoudnessEnhancer(audioSessionId)
                    }
                })
                player?.volume = 1.0f
            } catch (e: Exception) {
                Log.e("MusicPlayerController", "Failed to get MediaController", e)
            }
        }, MoreExecutors.directExecutor())
    }

    private fun setupLoudnessEnhancer(sessionId: Int) {
        try {
            loudnessEnhancer?.release()
            if (sessionId != 0) {
                loudnessEnhancer = LoudnessEnhancer(sessionId).apply {
                    // 소리 증폭 값을 대폭 상향 (10000 -> 30000: 30dB 증폭)
                    // 에뮬레이터의 작은 소리를 확실하게 보강합니다.
                    setTargetGain(30000)
                    enabled = true
                }
            }
        } catch (e: Exception) {
            Log.e("MusicPlayerController", "LoudnessEnhancer failed", e)
        }
    }

    actual fun playSong(song: Song, playlist: List<Song>) {
        if (playlist.isEmpty()) return
        val indexInList = playlist.indexOfFirst { it.id == song.id }
        val startIndex = if (indexInList != -1) indexInList else 0
        _currentPlaylist.value = playlist
        _currentIndex.value = startIndex
        _currentSong.value = song
        
        player?.let { p ->
            p.stop()
            p.clearMediaItems()
            
            playlist.forEach { s ->
                val uriString = s.streamUrl ?: ""
                val uri = if (uriString.startsWith("/") || uriString.startsWith("content://")) {
                    Uri.fromFile(File(uriString))
                } else {
                    Uri.parse(uriString)
                }
                p.addMediaItem(MediaItem.fromUri(uri))
            }

            p.prepare()
            p.seekTo(startIndex, 0L)
            p.play()
        }
    }

    actual fun togglePlayPause() {
        if (player?.isPlaying == true) player?.pause() else player?.play()
    }

    actual fun playNext() {
        player?.seekToNextMediaItem()
    }

    actual fun playPrevious() {
        if ((player?.currentPosition ?: 0) > 5000) player?.seekTo(0)
        else player?.seekToPreviousMediaItem()
    }

    actual fun seekTo(position: Long) {
        player?.seekTo(position)
    }

    actual fun skipToIndex(index: Int) {
        if (index in _currentPlaylist.value.indices) {
            player?.seekTo(index, 0L)
            if (player?.isPlaying == false) player?.play()
        }
    }
    
    actual fun setVolume(volume: Float) {
        val level = volume.coerceIn(0f, 1f)
        _volume.value = level
        player?.volume = level
    }
}
