package com.nas.musicplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.nas.musicplayer.db.getDatabase
import java.util.*

class MainActivity : ComponentActivity() {
    
    private val localSongsState = mutableStateListOf<Song>()
    
    // 음성 검색 상태들
    private var voiceSearchQuery by mutableStateOf("")
    private var isVoiceSearching by mutableStateOf(false)
    private var isVoiceFinal by mutableStateOf(false)

    private val voiceSearchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isVoiceSearching = false
        if (result.resultCode == RESULT_OK) {
            val data = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            voiceSearchQuery = data?.get(0) ?: ""
            isVoiceFinal = true // 안드로이드 인텐트 방식은 결과가 오면 무조건 최종 결과임
        }
    }

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
                localSongs = localSongsState,
                voiceQuery = voiceSearchQuery,
                isVoiceFinal = isVoiceFinal,
                isVoiceSearching = isVoiceSearching,
                onVoiceSearchClick = { startVoiceRecognition() },
                onVoiceQueryConsumed = { 
                    voiceSearchQuery = ""
                    isVoiceFinal = false
                }
            )
        }
    }

    private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "노래 제목이나 아티스트를 말씀하세요")
        }
        try {
            isVoiceSearching = true
            voiceSearchLauncher.launch(intent)
        } catch (e: Exception) {
            isVoiceSearching = false
        }
    }

    private fun scanAndLoadMusic() {
        val downloadFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        MediaScannerConnection.scanFile(this, arrayOf(downloadFolder.absolutePath), null) { _, _ ->
            runOnUiThread { loadMusic() }
        }
        loadMusic()
    }

    private fun loadMusic() {
        val songs = LocalMusicLoader.loadLocalMusic(this)
        localSongsState.clear()
        localSongsState.addAll(songs)
    }
}
