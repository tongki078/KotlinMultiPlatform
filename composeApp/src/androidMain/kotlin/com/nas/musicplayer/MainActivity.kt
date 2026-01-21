package com.nas.musicplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.nas.musicplayer.db.getDatabase
import java.io.File

class MainActivity : ComponentActivity() {
    
    private val localSongsState = mutableStateListOf<Song>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                scanAndLoadMusic()
            }
        }

        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            scanAndLoadMusic()
        } else {
            requestPermissionLauncher.launch(permission)
        }

        setContent {
            val database = remember { getDatabase(applicationContext) }
            val repository = remember { MusicRepository(database.playlistDao(), database.recentSearchDao()) }
            val musicPlayerController = remember { MusicPlayerController(applicationContext) }
            val musicPlayerViewModel = remember { MusicPlayerViewModel(musicPlayerController, repository) }

            App(
                musicRepository = repository,
                musicPlayerViewModel = musicPlayerViewModel,
                localSongs = localSongsState
            )
        }
    }

    private fun scanAndLoadMusic() {
        // Download 폴더 등 주요 경로를 스캔하도록 요청
        val downloadFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        
        MediaScannerConnection.scanFile(
            this,
            arrayOf(downloadFolder.absolutePath),
            null
        ) { _, _ ->
            // 스캔 완료 후 음악 로드 (UI 스레드에서 실행)
            runOnUiThread {
                loadMusic()
            }
        }
        
        // 즉시 한 번 로드 시도
        loadMusic()
    }

    private fun loadMusic() {
        val songs = LocalMusicLoader.loadLocalMusic(this)
        localSongsState.clear()
        localSongsState.addAll(songs)
    }
}
