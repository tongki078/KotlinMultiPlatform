package com.nas.musicplayer

import androidx.lifecycle.viewModelScope
import com.nas.musicplayer.db.PlaylistEntity
import com.nas.musicplayer.network.MusicApiServiceImpl
import com.nas.musicplayer.network.httpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MusicPlayerViewModel(
    private val musicPlayerController: MusicPlayerController,
    private val repository: MusicRepository
) : BaseViewModel() {

    private val musicApiService = MusicApiServiceImpl(httpClient)
    private val fileDownloader = getFileDownloader()

    // 가사가 실시간으로 반영될 수 있도록 내부 상태 관리
    private val _currentSongWithLyrics = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSongWithLyrics.asStateFlow()

    val isPlaying: StateFlow<Boolean> = musicPlayerController.isPlaying
    val currentPosition: StateFlow<Long> = musicPlayerController.currentPosition
    val duration: StateFlow<Long> = musicPlayerController.duration
    val currentPlaylist: StateFlow<List<Song>> = musicPlayerController.currentPlaylist
    val currentIndex: StateFlow<Int> = musicPlayerController.currentIndex
    val volume: StateFlow<Float> = musicPlayerController.volume

    val playlistItems: StateFlow<List<PlaylistEntity>> = repository.allPlaylists
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // 재생 곡이 바뀔 때마다 가사 검색 시도
        viewModelScope.launch {
            musicPlayerController.currentSong.collect { song ->
                _currentSongWithLyrics.value = song
                if (song != null && song.lyrics == null) {
                    fetchLyricsForSong(song)
                }
            }
        }
    }

    private fun fetchLyricsForSong(song: Song) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 정제된 정보를 바탕으로 가사 검색
                val lrcResponse = musicApiService.getLyrics(song.artist, song.name ?: "")
                if (lrcResponse != null) {
                    val syncedLyrics = lrcResponse.syncedLyrics ?: lrcResponse.plainLyrics
                    if (syncedLyrics != null) {
                        // 기존 곡 정보에 가사를 추가하여 상태 업데이트
                        _currentSongWithLyrics.update { current ->
                            if (current?.id == song.id) {
                                current.copy(lyrics = syncedLyrics)
                            } else {
                                current
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("Lyrics fetch error: ${e.message}")
            }
        }
    }

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

    /**
     * 음악 파일을 기기에 다운로드합니다.
     * 이미지 URL을 함께 전달하여 보관함에서 이미지가 나오도록 개선합니다.
     */
    fun downloadSong(song: Song, onResult: (Boolean, String?) -> Unit) {
        val url = song.streamUrl ?: run {
            onResult(false, "다운로드 가능한 URL이 없습니다.")
            return
        }
        
        // 파일명 생성: "아티스트 - 제목.mp3" 형태
        val fileName = "${song.artist} - ${song.name ?: "제목 없음"}.mp3"
            .replace(Regex("[\\\\/:*?\"<>|]"), "_") 
        
        // 이미지 URL(metaPoster)을 함께 전달
        fileDownloader.downloadFile(url, song.metaPoster, fileName, onResult)
    }
}
