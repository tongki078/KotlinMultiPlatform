package com.nas.musicplayer.ui.music

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nas.musicplayer.Song

@Composable
fun SongListItem(
    song: Song, 
    onItemClick: () -> Unit, 
    onMoreClick: () -> Unit,
    isDownloading: Boolean = false // 다운로드 상태 추가
) {
    val infiniteTransition = rememberInfiniteTransition()
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    Column(modifier = Modifier.clickable { onItemClick() }) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = song.metaPoster ?: song.streamUrl,
                contentDescription = null,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(song.name ?: "제목 없음", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = Color.Gray, maxLines = 1)
            }
            
            if (isDownloading) {
                Icon(
                    Icons.Default.Sync, 
                    null, 
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp).rotate(rotation)
                )
            } else {
                IconButton(onClick = onMoreClick) {
                    Icon(Icons.Default.MoreVert, null, tint = Color.Gray)
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(start = 88.dp, end = 16.dp), thickness = 0.5.dp, color = Color.LightGray.copy(alpha = 0.5f))
    }
}

@Composable
fun MoreOptionsSheet(
    song: Song, 
    onNavigateToArtist: () -> Unit, 
    onNavigateToAddToPlaylist: (Song) -> Unit, 
    onNavigateToAlbum: () -> Unit,
    onDownloadClick: () -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    Column(modifier = Modifier.padding(bottom = 32.dp)) {
        ListItem(
            headlineContent = { Text(song.name ?: "") },
            supportingContent = { Text(song.artist) },
            leadingContent = { 
                AsyncImage(
                    model = song.metaPoster ?: song.streamUrl, 
                    contentDescription = null, 
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)), 
                    contentScale = ContentScale.Crop
                ) 
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
        HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))
        
        ListItem(
            headlineContent = { Text("다운로드") }, 
            leadingContent = { Icon(Icons.Default.Download, null, tint = primaryColor) }, 
            modifier = Modifier.clickable { onDownloadClick() },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )

        ListItem(
            headlineContent = { Text("플레이리스트에 추가") }, 
            leadingContent = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null, tint = primaryColor) }, 
            modifier = Modifier.clickable { onNavigateToAddToPlaylist(song) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
        ListItem(
            headlineContent = { Text("아티스트 보기") }, 
            leadingContent = { Icon(Icons.Default.Person, null, tint = primaryColor) }, 
            modifier = Modifier.clickable { onNavigateToArtist() },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
        ListItem(
            headlineContent = { Text("앨범 보기") }, 
            leadingContent = { Icon(Icons.Default.Album, null, tint = primaryColor) }, 
            modifier = Modifier.clickable { onNavigateToAlbum() },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}
