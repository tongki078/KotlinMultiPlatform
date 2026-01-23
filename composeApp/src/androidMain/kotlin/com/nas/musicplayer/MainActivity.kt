package com.nas.musicplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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

    private var speechRecognizer: SpeechRecognizer? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        scanAndLoadMusic()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        }

        checkPermissions()

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
                onVoiceSearchClick = { 
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        startVoiceRecognition()
                    } else {
                        requestPermissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    }
                },
                onVoiceQueryConsumed = { 
                    voiceSearchQuery = ""
                    isVoiceFinal = false
                }
            )
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissions.add(Manifest.permission.RECORD_AUDIO)

        val neededPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (neededPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(neededPermissions.toTypedArray())
        } else {
            scanAndLoadMusic()
        }
    }

    private fun startVoiceRecognition() {
        if (speechRecognizer == null) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                voiceSearchQuery = ""
                isVoiceSearching = true
                isVoiceFinal = false
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                isVoiceSearching = false
                isVoiceFinal = true
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    voiceSearchQuery = matches[0]
                }
                isVoiceSearching = false
                isVoiceFinal = true
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    voiceSearchQuery = matches[0]
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer?.startListening(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
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
