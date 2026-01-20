package com.nas.musicplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import com.nas.musicplayer.db.getDatabase

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            // Android-specific initialization
            val database = remember { getDatabase(applicationContext) }
            val repository = remember { MusicRepository(database.playlistDao(), database.recentSearchDao()) }
            val musicPlayerController = remember { MusicPlayerController(applicationContext) }
            val musicPlayerViewModel = remember { MusicPlayerViewModel(musicPlayerController, repository) }

            App(
                musicRepository = repository,
                musicPlayerViewModel = musicPlayerViewModel
            )
        }
    }
}
