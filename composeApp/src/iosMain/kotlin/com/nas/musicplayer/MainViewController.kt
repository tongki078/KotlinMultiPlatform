package com.nas.musicplayer

import androidx.compose.runtime.*
import androidx.compose.ui.window.ComposeUIViewController
import com.nas.musicplayer.db.getDatabase

fun MainViewController() = ComposeUIViewController {
    val database = remember { getDatabase() }
    val repository = remember { MusicRepository(database.playlistDao(), database.recentSearchDao()) }
    val controller = remember { MusicPlayerController() }
    val viewModel = remember { MusicPlayerViewModel(controller, repository) }
    val localSongs = remember { LocalMusicLoader.loadLocalMusic() }

    var voiceQuery by remember { mutableStateOf("") }
    var isVoiceFinal by remember { mutableStateOf(false) }
    var isVoiceSearching by remember { mutableStateOf(false) }
    
    val voiceHelper = remember { 
        VoiceSearchHelper(
            onResult = { text, final -> 
                if (final) {
                    println("iOS MainVC: Final result received. Text: '$text'")
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
                println("iOS Voice Search Error: $it")
                isVoiceSearching = false
                isVoiceFinal = true // 에러 시에도 종료 상태로 만들어 대기 해제
            }
        )
    }

    App(
        musicRepository = repository,
        musicPlayerViewModel = viewModel,
        localSongs = localSongs,
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
        }
    )
}
