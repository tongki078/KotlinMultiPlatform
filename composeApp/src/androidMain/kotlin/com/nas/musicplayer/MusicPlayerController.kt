package com.nas.musicplayer

import android.content.Context
import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

actual class MusicPlayerController(context: Context) {

    private var exoPlayer: ExoPlayer? = null
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
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val httpDataSourceFactory = OkHttpDataSource.Factory(OkHttpClient())
        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

        exoPlayer = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(audioAttributes, true)
            .build()

        exoPlayer?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingValue: Boolean) {
                _isPlaying.value = isPlayingValue
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val index = exoPlayer?.currentMediaItemIndex ?: 0
                if (index in _currentPlaylist.value.indices) {
                    _currentIndex.value = index
                    _currentSong.value = _currentPlaylist.value[index]
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                setupLoudnessEnhancer(audioSessionId)
            }
        })

        coroutineScope.launch {
            while (true) {
                _currentPosition.value = exoPlayer?.currentPosition ?: 0L
                _duration.value = exoPlayer?.duration ?: 0L
                delay(500)
            }
        }
    }

    private fun setupLoudnessEnhancer(sessionId: Int) {
        try {
            loudnessEnhancer?.release()
            loudnessEnhancer = LoudnessEnhancer(sessionId).apply {
                setTargetGain(1500)
                enabled = true
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
        exoPlayer?.let { player ->
            player.stop()
            player.clearMediaItems()
            playlist.forEach { s ->
                s.streamUrl?.let { url ->
                    player.addMediaItem(MediaItem.fromUri(url))
                }
            }
            player.prepare()
            player.seekTo(startIndex, 0L)
            player.play()
        }
    }

    actual fun togglePlayPause() {
        if (exoPlayer?.isPlaying == true) exoPlayer?.pause() else exoPlayer?.play()
    }

    actual fun playNext() {
        exoPlayer?.seekToNextMediaItem()
    }

    actual fun playPrevious() {
        if ((exoPlayer?.currentPosition ?: 0) > 5000) exoPlayer?.seekTo(0)
        else exoPlayer?.seekToPreviousMediaItem()
    }

    actual fun seekTo(position: Long) {
        exoPlayer?.seekTo(position)
    }

    actual fun skipToIndex(index: Int) {
        if (index in _currentPlaylist.value.indices) {
            exoPlayer?.seekTo(index, 0L)
            if (exoPlayer?.isPlaying == false) exoPlayer?.play()
        }
    }
    
    actual fun setVolume(volume: Float) {
        val level = volume.coerceIn(0f, 1f)
        _volume.value = level
        exoPlayer?.volume = level
    }
}
