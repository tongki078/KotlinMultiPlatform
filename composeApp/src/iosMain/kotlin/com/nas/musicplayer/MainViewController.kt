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
                voiceQuery = text
                isVoiceFinal = final
                if (final) {
                    isVoiceSearching = false
                }
            },
            onError = { 
                println("iOS Voice Search Error: $it")
                isVoiceSearching = false
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
