package com.nas.musicplayer.ui.music

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nas.musicplayer.Song

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeDetailScreen(
    themeName: String,
    viewModel: MusicSearchViewModel,
    onBack: () -> Unit,
    onSongClick: (Song, List<Song>) -> Unit,
    onShowSongOptions: (Song) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(themeName, fontWeight = FontWeight.ExtraBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // uiState.songs가 searchResults로 변경됨에 따라 참조 수정
            if (uiState.isLoading && uiState.searchResults.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(uiState.searchResults) { song ->
                        SongCardItem(
                            song = song,
                            onClick = { onSongClick(song, uiState.searchResults) },
                            onLongClick = { onShowSongOptions(song) }
                        )
                    }
                }
            }
        }
    }
}
