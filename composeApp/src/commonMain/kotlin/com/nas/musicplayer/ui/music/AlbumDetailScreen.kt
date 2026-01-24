package com.nas.musicplayer.ui.music

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nas.musicplayer.Album
import com.nas.musicplayer.Artist
import com.nas.musicplayer.Song
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    album: Album,
    onBack: () -> Unit,
    onSongClick: (Song, List<Song>) -> Unit,
    onDownloadSong: (Song) -> Unit,
    downloadingSongIds: Set<Long> = emptySet() // 추가: 다운로드 상태 감시용
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    var selectedSongForSheet by remember { mutableStateOf<Song?>(null) }

    val infiniteTransition = rememberInfiniteTransition()
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.3f),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                modifier = Modifier.statusBarsPadding()
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 0.dp,
                bottom = padding.calculateBottomPadding() + 100.dp
            )
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(top = padding.calculateTopPadding() + 16.dp, bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AsyncImage(
                        model = album.imageUrl,
                        contentDescription = "Album Art",
                        modifier = Modifier
                            .size(240.dp)
                            .shadow(16.dp, RoundedCornerShape(16.dp))
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = album.name,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp
                        ),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = album.artist,
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Button(
                            onClick = { if(album.songs.isNotEmpty()) onSongClick(album.songs.first(), album.songs) },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Icon(Icons.Default.PlayArrow, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("재생", fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { if(album.songs.isNotEmpty()) onSongClick(album.songs.shuffled().first(), album.songs) },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Icon(Icons.Default.Shuffle, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("셔플", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            
            itemsIndexed(album.songs) { index, song ->
                val isDownloading = downloadingSongIds.contains(song.id)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSongClick(song, album.songs) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${index + 1}",
                        modifier = Modifier.width(36.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = Color.Gray,
                            fontWeight = FontWeight.Light
                        )
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.name ?: "알 수 없는 제목",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (song.artist != album.artist) {
                            Text(
                                text = song.artist,
                                style = MaterialTheme.typography.bodySmall.copy(color = Color.Gray),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    
                    if (isDownloading) {
                        Icon(
                            Icons.Default.Sync, 
                            null, 
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp).rotate(rotation)
                        )
                    } else {
                        IconButton(onClick = { 
                            selectedSongForSheet = song
                            scope.launch { sheetState.show() }
                        }) {
                            Icon(Icons.Default.MoreVert, null, tint = Color.Gray)
                        }
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(start = 52.dp, end = 16.dp),
                    thickness = 0.5.dp,
                    color = Color.LightGray.copy(alpha = 0.2f)
                )
            }
        }
    }

    if (selectedSongForSheet != null) {
        val currentSong = selectedSongForSheet!!
        ModalBottomSheet(
            onDismissRequest = { selectedSongForSheet = null },
            sheetState = sheetState
        ) {
            MoreOptionsSheet(
                song = currentSong,
                onNavigateToArtist = { /* App.kt 연동 */ },
                onNavigateToAddToPlaylist = { /* App.kt 연동 */ },
                onNavigateToAlbum = { /* App.kt 연동 */ },
                onDownloadClick = {
                    scope.launch {
                        sheetState.hide()
                        selectedSongForSheet = null
                        onDownloadSong(currentSong)
                    }
                }
            )
        }
    }
}
