package com.nas.musicplayer.ui.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nas.musicplayer.Song
import com.nas.musicplayer.MusicPlayerViewModel
import com.nas.musicplayer.PlaylistViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistViewScreen(
    onBack: () -> Unit,
    onSongClick: (Song, List<Song>) -> Unit,
    playerViewModel: MusicPlayerViewModel,
    viewModel: PlaylistViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val playlist = uiState.playlist

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(playlist?.name ?: "플레이리스트") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        val songs = playlist?.songs ?: emptyList()
        LazyColumn(contentPadding = padding) {
            items(songs) { song ->
                ListItem(
                    headlineContent = { Text(song.name ?: "Unknown Title") },
                    supportingContent = { Text(song.artist) },
                    leadingContent = {
                        AsyncImage(
                            model = song.metaPoster ?: song.streamUrl,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    },
                    modifier = Modifier.clickable { onSongClick(song, songs) }
                )
            }
        }
    }
}
