package com.nas.musicplayer.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nas.musicplayer.MusicRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsListScreen(
    repository: MusicRepository,
    onPlaylistClick: (Int) -> Unit,
    onBack: () -> Unit
) {
    val playlists by repository.allPlaylists.collectAsState(initial = emptyList())

    // 중복된 Scaffold를 제거하여 하단 탭바의 터치 이벤트를 가로막지 않도록 합니다.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = { Text("플레이리스트", fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(playlists) { playlist ->
                ListItem(
                    headlineContent = { Text(playlist.name, fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text("${playlist.songs.size} 곡") },
                    leadingContent = { 
                        Icon(
                            Icons.Default.MusicNote, 
                            null, 
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        ) 
                    },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                    modifier = Modifier.clickable { onPlaylistClick(playlist.id) }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp), 
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                )
            }
        }
    }
}
