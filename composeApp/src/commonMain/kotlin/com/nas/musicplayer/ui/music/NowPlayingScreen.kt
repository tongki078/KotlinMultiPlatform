package com.nas.musicplayer.ui.music

import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nas.musicplayer.*
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Airplay
import androidx.compose.material.icons.filled.Download
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    viewModel: MusicPlayerViewModel,
    onBack: () -> Unit,
    onNavigateToArtist: (Artist) -> Unit,
    onNavigateToAlbum: (Album) -> Unit,
    onNavigateToAddToPlaylist: (Song) -> Unit,
    onDownloadSong: (Song) -> Unit // 추가
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

    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    var showMoreSheet by remember { mutableStateOf(false) }

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

    val albumArtScale by animateFloatAsState(targetValue = if (isPlaying) 1f else 0.92f, label = "albumArtScale")

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
            modifier = Modifier.fillMaxSize().alpha(0.45f).blur(60.dp),
            contentScale = ContentScale.Crop
        )

        Column(
            modifier = Modifier
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp)
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.KeyboardArrowDown, null, modifier = Modifier.size(32.dp), tint = Color.White)
                }
                Box(modifier = Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.3f)))
                IconButton(onClick = { showMoreSheet = true }) {
                    Icon(Icons.Rounded.MoreHoriz, null, tint = Color.White)
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds(),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = isLyricsMode,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(500)) + scaleIn(initialScale = 0.95f)) togetherWith
                        (fadeOut(animationSpec = tween(500)) + scaleOut(targetScale = 0.95f))
                    },
                    label = "LyricsTransition"
                ) { targetIsLyrics ->
                    if (targetIsLyrics) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 24.dp)
                                .clickable { isLyricsMode = false }
                        ) {
                            LyricsView(song = song, currentPosition = currentPosition)
                        }
                    } else {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier
                                .fillMaxWidth(0.75f)
                                .aspectRatio(1f, matchHeightConstraintsFirst = true)
                                .clickable { isLyricsMode = true },
                            contentPadding = PaddingValues(0.dp),
                            pageSpacing = 0.dp
                        ) { page ->
                            val pageSong = playlist.getOrNull(page)
                            AsyncImage(
                                model = pageSong?.metaPoster ?: pageSong?.streamUrl,
                                contentDescription = "Album Art",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .scale(if (page == pagerState.currentPage) albumArtScale else 1f)
                                    .clip(RoundedCornerShape(24.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 32.dp)
            ) {
                AnimatedVisibility(
                    visible = !isLyricsMode,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = song?.name ?: "알 수 없는 제목",
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Color.White),
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

                        Spacer(modifier = Modifier.height(8.dp))

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
                }

                if (isLyricsMode) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = song?.name ?: "",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = Color.White),
                            maxLines = 1
                        )
                        Text(
                            text = song?.artist ?: "",
                            style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.7f)),
                            maxLines = 1
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

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

                AnimatedVisibility(
                    visible = !isLyricsMode,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
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

                if (isLyricsMode) {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }

    if (showMoreSheet && song != null) {
        ModalBottomSheet(
            onDismissRequest = { showMoreSheet = false },
            sheetState = sheetState,
            containerColor = Color(0xFF1C1C1E),
            contentColor = Color.White
        ) {
            val currentSong = song!!
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
                ListItem(
                    headlineContent = { Text(currentSong.name ?: "", color = Color.White) },
                    supportingContent = { Text(currentSong.artist, color = Color.Gray) },
                    leadingContent = { 
                        AsyncImage(
                            model = currentSong.metaPoster ?: currentSong.streamUrl, 
                            contentDescription = null, 
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)), 
                            contentScale = ContentScale.Crop
                        ) 
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                
                ListItem(
                    headlineContent = { Text("다운로드", color = Color.White) },
                    leadingContent = { Icon(Icons.Default.Download, null, tint = Color.White) },
                    modifier = Modifier.clickable { 
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            showMoreSheet = false
                            onDownloadSong(currentSong)
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )

                ListItem(
                    headlineContent = { Text("플레이리스트에 추가", color = Color.White) },
                    leadingContent = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null, tint = Color.White) },
                    modifier = Modifier.clickable { 
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            showMoreSheet = false
                            onNavigateToAddToPlaylist(currentSong)
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
                ListItem(
                    headlineContent = { Text("아티스트 보기", color = Color.White) },
                    leadingContent = { Icon(Icons.Rounded.Person, null, tint = Color.White) },
                    modifier = Modifier.clickable { 
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            showMoreSheet = false
                            onNavigateToArtist(Artist(name = currentSong.artist))
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
                ListItem(
                    headlineContent = { Text("앨범 보기", color = Color.White) },
                    leadingContent = { Icon(Icons.Rounded.Album, null, tint = Color.White) },
                    modifier = Modifier.clickable { 
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            showMoreSheet = false
                            onNavigateToAlbum(Album(name = currentSong.albumName, artist = currentSong.artist))
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }
    }
}

@Composable
fun LyricsView(song: Song?, currentPosition: Long) {
    val syncOffset = 300L
    val adjustedPosition = currentPosition + syncOffset

    val parsedLyrics = remember(song?.lyrics) {
        parseLrc(song?.lyrics)
    }

    val listState = rememberLazyListState()
    val density = LocalDensity.current

    val activeLineIndex = remember(adjustedPosition, parsedLyrics) {
        if (parsedLyrics.isEmpty()) return@remember -1
        val index = parsedLyrics.indexOfLast { it.isSynced && it.timeMs <= adjustedPosition }
        if (index == -1) 0 else index
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val viewHeightPx = with(density) { maxHeight.toPx() }

        LaunchedEffect(activeLineIndex) {
            if (activeLineIndex >= 0 && parsedLyrics.isNotEmpty()) {
                listState.animateScrollToItem(
                    index = activeLineIndex,
                    scrollOffset = -(viewHeightPx / 2.5f).toInt()
                )
            }
        }

        if (parsedLyrics.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("가사 정보가 없습니다.", color = Color.White.copy(alpha = 0.5f), fontSize = 18.sp)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(
                    top = maxHeight / 4,
                    bottom = maxHeight / 2,
                    start = 20.dp,
                    end = 20.dp
                )
            ) {
                itemsIndexed(parsedLyrics) { index, line ->
                    val isActive = index == activeLineIndex
                    val alpha by animateFloatAsState(
                        targetValue = if (isActive) 1f else 0.25f,
                        animationSpec = tween(durationMillis = 500)
                    )
                    val scale by animateFloatAsState(
                        targetValue = if (isActive) 1.05f else 1f,
                        animationSpec = tween(durationMillis = 500)
                    )

                    Text(
                        text = line.text,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        ),
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(alpha)
                            .scale(scale)
                            .padding(vertical = 2.dp),
                        textAlign = TextAlign.Left
                    )
                }
            }
        }
    }
}

private fun parseLrc(lrcContent: String?): List<LyricsLine> {
    if (lrcContent == null) return emptyList()
    
    val lines = mutableListOf<LyricsLine>()
    val timestampRegex = Regex("\\[(\\d+):(\\d+)(?:[.:](\\d+))?\\]")
    
    var lastKnownTimeMs = 0L
    
    lrcContent.split(Regex("\\r?\\n")).forEach { line ->
        val timestampMatches = timestampRegex.findAll(line).toList()
        
        if (timestampMatches.isNotEmpty()) {
            val text = line.replace(timestampRegex, "").trim()
            if (text.isNotEmpty()) {
                timestampMatches.forEach { match ->
                    val min = match.groupValues[1].toLongOrNull() ?: 0L
                    val sec = match.groupValues[2].toLongOrNull() ?: 0L
                    val msPart = match.groupValues.getOrNull(3) ?: ""
                    
                    val ms = when (msPart.length) {
                        1 -> msPart.toLongOrNull()?.let { it * 100 } ?: 0L
                        2 -> msPart.toLongOrNull()?.let { it * 10 } ?: 0L
                        3 -> msPart.toLongOrNull() ?: 0L
                        else -> 0L
                    }
                    
                    val timeMs = (min * 60 * 1000) + (sec * 1000) + ms
                    lines.add(LyricsLine(timeMs, text, isSynced = true))
                    lastKnownTimeMs = timeMs
                }
            }
        } else {
            val text = line.trim()
            if (text.isNotEmpty() && !text.startsWith("[")) {
                lastKnownTimeMs += 1 
                lines.add(LyricsLine(lastKnownTimeMs, text, isSynced = false))
            }
        }
    }
    return lines.sortedBy { it.timeMs }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${minutes}:${seconds.toString().padStart(2, '0')}"
}

data class LyricsLine(val timeMs: Long, val text: String, val isSynced: Boolean = true)
