package com.nas.musicplayer

import androidx.compose.runtime.*
import androidx.compose.ui.window.ComposeUIViewController
import com.nas.musicplayer.db.getDatabase

fun MainViewController() = ComposeUIViewController {
    println("MainViewController: Starting Skia Compose Engine...")

    val database = remember { getDatabase() }
    val repository = remember { MusicRepository(database.playlistDao(), database.recentSearchDao()) }
    val controller = remember { MusicPlayerController() }
    val viewModel = remember { MusicPlayerViewModel(controller, repository) }
    
    // 로컬 곡 목록 상태 관리
    var localSongsState by remember { mutableStateOf(emptyList<Song>()) }
    
    // 로컬 곡 로드 함수 정의
    fun refreshSongs() {
        println("MainViewController: Refreshing local songs...")
        val songs = LocalMusicLoader.loadLocalMusic()
        localSongsState = songs
        println("MainViewController: Refresh complete. Count: ${songs.size}")
    }

    // 초기 로드
    LaunchedEffect(Unit) {
        refreshSongs()
    }

    var voiceQuery by remember { mutableStateOf("") }
    var isVoiceFinal by remember { mutableStateOf(false) }
    var isVoiceSearching by remember { mutableStateOf(false) }
    
    val voiceHelper = remember { 
        VoiceSearchHelper(
            onResult = { text, final -> 
                if (final) {
                    if (text.isNotBlank()) {
                        voiceQuery = text
                    }
                    isVoiceFinal = true
                    isVoiceSearching = false
                } else {
                    if (text.isNotBlank()) {
                        voiceQuery = text
                    }
                }
            },
            onError = { 
                isVoiceSearching = false
                isVoiceFinal = true 
            }
        )
    }

    App(
        musicRepository = repository,
        musicPlayerViewModel = viewModel,
        localSongs = localSongsState,
        voiceQuery = voiceQuery,
        isVoiceFinal = isVoiceFinal,
        isVoiceSearching = isVoiceSearching,
        onVoiceSearchClick = { 
            isVoiceSearching = true
            voiceQuery = ""
            isVoiceFinal = false
            voiceHelper.startListening() 
        },
        onVoiceQueryConsumed = { 
            voiceQuery = ""
            isVoiceFinal = false
        },
        onRefreshLocalSongs = {
            // 다운로드 완료 시 여기서 호출됨
            refreshSongs()
        }
    )
}
