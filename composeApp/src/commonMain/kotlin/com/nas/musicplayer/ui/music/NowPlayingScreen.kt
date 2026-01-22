package com.nas.musicplayer.ui.music

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.launch

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

    var isLyricsMode by remember { mutableStateOf(false) }
    var offsetY by remember { mutableStateOf(0f) }
    val dismissThreshold = 400f

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
            .background(Color.Black)
            .draggable(
                state = draggableState,
                orientation = Orientation.Vertical,
                onDragStopped = {
                    if (offsetY > dismissThreshold) onBack() else offsetY = 0f
                }
            )
            .offset { IntOffset(0, offsetY.roundToInt()) }
    ) {
        AsyncImage(
            model = song?.metaPoster ?: song?.streamUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().alpha(0.4f).blur(50.dp),
            contentScale = ContentScale.Crop
        )

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
                    Icon(Icons.Rounded.KeyboardArrowDown, null, modifier = Modifier.size(32.dp), tint = Color.White)
                }
                Box(modifier = Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.3f)))
                IconButton(onClick = { /* More */ }) {
                    Icon(Icons.Rounded.MoreHoriz, null, tint = Color.White)
                }
            }

            // 2. Center Content (Album Art or Lyrics)
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = isLyricsMode,
                    transitionSpec = { fadeIn() togetherWith fadeOut() }
                ) { targetIsLyrics ->
                    if (targetIsLyrics) {
                        // 가사 뷰를 클릭하면 다시 앨범 아트 모드로 전환
                        Box(modifier = Modifier.fillMaxSize().clickable { isLyricsMode = false }) {
                            LyricsView(song = song, currentPosition = currentPosition)
                        }
                    } else {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clickable { isLyricsMode = true },
                            contentPadding = PaddingValues(horizontal = 0.dp)
                        ) { page ->
                            val pageSong = playlist.getOrNull(page)
                            AsyncImage(
                                model = pageSong?.metaPoster ?: pageSong?.streamUrl,
                                contentDescription = "Album Art",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .scale(if (page == pagerState.currentPage) albumArtScale else 0.85f)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            }

            // 3. Info & Progress
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song?.name ?: "알 수 없는 제목",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Color.White),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = song?.artist ?: "Unknown Artist",
                            style = MaterialTheme.typography.bodyLarge.copy(color = Color.White.copy(alpha = 0.7f), fontSize = 18.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { /* Favorite */ }) {
                        Icon(Icons.Rounded.FavoriteBorder, null, tint = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Slider(
                    value = if (duration > 0) (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f,
                    onValueChange = { if (duration > 0) viewModel.seekTo((it * duration).toLong()) },
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                    )
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = formatTime(currentPosition), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
                    Text(text = formatTime(duration), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 4. Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { isLyricsMode = !isLyricsMode }) {
                    Icon(Icons.Default.Lyrics, null, modifier = Modifier.size(28.dp), tint = if (isLyricsMode) Color.White else Color.White.copy(alpha = 0.4f))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    IconButton(onClick = { viewModel.playPrevious() }) {
                        Icon(Icons.Rounded.SkipPrevious, null, modifier = Modifier.size(48.dp), tint = Color.White)
                    }
                    IconButton(onClick = { viewModel.togglePlayPause() }, modifier = Modifier.size(72.dp)) {
                        Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, modifier = Modifier.size(64.dp), tint = Color.White)
                    }
                    IconButton(onClick = { viewModel.playNext() }) {
                        Icon(Icons.Rounded.SkipNext, null, modifier = Modifier.size(48.dp), tint = Color.White)
                    }
                }
                IconButton(onClick = { /* AirPlay */ }) {
                    Icon(Icons.Default.Airplay, null, modifier = Modifier.size(24.dp), tint = Color.White.copy(alpha = 0.4f))
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
            
            // 5. Volume
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 48.dp)
            ) {
                Icon(Icons.AutoMirrored.Rounded.VolumeDown, null, modifier = Modifier.size(20.dp), tint = Color.White.copy(alpha = 0.5f))
                Slider(
                    value = volume,
                    onValueChange = { viewModel.setVolume(it) },
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                    )
                )
                Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, modifier = Modifier.size(20.dp), tint = Color.White.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
fun LyricsView(song: Song?, currentPosition: Long) {
    val lyricsLines = remember(song) {
        song?.lyrics?.lines() ?: listOf(
            "가사 정보가 없습니다.",
            "",
            "음악을 즐겨보세요!"
        )
    }

    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(vertical = 100.dp),
        userScrollEnabled = true // 가사는 스크롤 가능해야 함
    ) {
        itemsIndexed(lyricsLines) { index, line ->
            Text(
                text = line,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 28.sp,
                    lineHeight = 36.sp
                ),
                color = Color.White,
                modifier = Modifier.fillMaxWidth().alpha(0.9f),
                textAlign = TextAlign.Left
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${minutes}:${seconds.toString().padStart(2, '0')}"
}
