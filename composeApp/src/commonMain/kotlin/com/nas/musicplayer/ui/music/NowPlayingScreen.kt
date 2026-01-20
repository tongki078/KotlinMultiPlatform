package com.nas.musicplayer.ui.music

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nas.musicplayer.MusicPlayerViewModel
import com.nas.musicplayer.Song
import kotlin.math.roundToInt
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Airplay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    viewModel: MusicPlayerViewModel,
    onBack: () -> Unit
) {
    val song by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val volume by viewModel.volume.collectAsState()
    val playlist by viewModel.currentPlaylist.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()

    var offsetY by remember { mutableStateOf(0f) }
    val dismissThreshold = 400f // Approximate threshold for commonMain

    val draggableState = rememberDraggableState { delta ->
        val newOffset = offsetY + delta
        if (newOffset >= 0) offsetY = newOffset
    }

    val pagerState = rememberPagerState(
        initialPage = if (currentIndex < 0) 0 else currentIndex,
        pageCount = { playlist.size }
    )

    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0 && currentIndex < playlist.size && pagerState.currentPage != currentIndex) {
            pagerState.scrollToPage(currentIndex)
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != currentIndex && playlist.isNotEmpty()) {
            viewModel.skipToIndex(pagerState.currentPage)
        }
    }

    val albumArtScale by animateFloatAsState(targetValue = if (isPlaying) 1f else 0.88f, label = "albumArtScale")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
            .draggable(
                state = draggableState,
                orientation = Orientation.Vertical,
                onDragStopped = {
                    if (offsetY > dismissThreshold) onBack() else offsetY = 0f
                }
            )
            .offset { IntOffset(0, offsetY.roundToInt()) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Top Bar
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.KeyboardArrowDown, null, modifier = Modifier.size(32.dp), tint = Color(0xFFFA2D48))
                }
                Box(modifier = Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)))
                IconButton(onClick = { /* More */ }) {
                    Icon(Icons.Rounded.MoreHoriz, null, tint = Color(0xFFFA2D48))
                }
            }

            Spacer(modifier = Modifier.weight(0.2f))

            // 2. Album Art Pager
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                contentPadding = PaddingValues(horizontal = 0.dp)
            ) { page ->
                val pageSong = playlist.getOrNull(page)
                
                AsyncImage(
                    model = pageSong?.metaPoster ?: pageSong?.streamUrl, // Simplified for KMP
                    contentDescription = "Album Art",
                    modifier = Modifier
                        .fillMaxSize()
                        .scale(if (page == pagerState.currentPage) albumArtScale else 0.85f)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
            }

            Spacer(modifier = Modifier.height(40.dp))

            // 3. Info
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song?.name ?: "알 수 없는 제목",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = song?.artist ?: "Unknown Artist",
                            style = MaterialTheme.typography.bodyLarge.copy(color = Color(0xFFFA2D48), fontSize = 17.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { /* Favorite */ }) {
                        Icon(Icons.Rounded.FavoriteBorder, null, tint = Color(0xFFFA2D48))
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 4. Progress Slider
                Slider(
                    value = if (duration > 0) (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f,
                    onValueChange = { if (duration > 0) viewModel.seekTo((it * duration).toLong()) },
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.onSurface,
                        activeTrackColor = MaterialTheme.colorScheme.onSurface,
                        inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                    )
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = formatTime(currentPosition), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text = formatTime(duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.weight(0.2f))

            // 5. Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { /* Lyrics */ }) {
                    Icon(Icons.Default.Lyrics, null, modifier = Modifier.size(24.dp), tint = Color(0xFFFA2D48))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    IconButton(onClick = { viewModel.playPrevious() }) {
                        Icon(Icons.Rounded.SkipPrevious, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { viewModel.togglePlayPause() }, modifier = Modifier.size(72.dp)) {
                        Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { viewModel.playNext() }) {
                        Icon(Icons.Rounded.SkipNext, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
                IconButton(onClick = { /* AirPlay */ }) {
                    Icon(Icons.Default.Airplay, null, modifier = Modifier.size(24.dp), tint = Color(0xFFFA2D48))
                }
            }

            Spacer(modifier = Modifier.weight(0.2f))
            
            // 6. Volume
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)
            ) {
                Icon(Icons.AutoMirrored.Rounded.VolumeDown, null, modifier = Modifier.size(16.dp), tint = Color.Gray)
                Slider(
                    value = volume,
                    onValueChange = { viewModel.setVolume(it) },
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.Gray,
                        inactiveTrackColor = Color.LightGray.copy(alpha = 0.3f)
                    )
                )
                Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, modifier = Modifier.size(16.dp), tint = Color.Gray)
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${minutes}:${seconds.toString().padStart(2, '0')}"
}
