package com.nas.musicplayer.ui.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nas.musicplayer.Album
import com.nas.musicplayer.Song

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    album: Album,
    onBack: () -> Unit,
    onSongClick: (Song, List<Song>) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(album.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { /* TODO: Shuffle play */ }) {
                        Icon(Icons.Default.Shuffle, contentDescription = "Shuffle")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            item {
                Column(modifier = Modifier.padding(16.dp)) {
                    AsyncImage(
                        model = album.imageUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.medium),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(album.name, style = MaterialTheme.typography.headlineMedium)
                    Text(album.artist, style = MaterialTheme.typography.titleMedium)
                }
            }
            items(album.songs) { song ->
                ListItem(
                    headlineContent = { Text(song.name ?: "") },
                    supportingContent = { Text(song.artist) },
                    modifier = Modifier.clickable { onSongClick(song, album.songs) }
                )
            }
        }
    }
}
