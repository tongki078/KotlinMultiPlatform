package com.nas.musicplayer.ui.music

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nas.musicplayer.*
import com.nas.musicplayer.db.RecentSearch
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicSearchScreen(
    onNavigateToArtist: (Artist) -> Unit,
    onNavigateToAddToPlaylist: (Song) -> Unit,
    onNavigateToAlbum: (Album) -> Unit,
    onNavigateToTheme: (Theme) -> Unit,
    onSongClick: (Song) -> Unit,
    onNavigateToPlaylists: () -> Unit,
    onVoiceSearchClick: () -> Unit,
    onDownloadSong: (Song) -> Unit,
    onDeleteSong: (Song) -> Unit,
    downloadingSongIds: Set<Long> = emptySet(),
    isVoiceSearching: Boolean = false,
    viewModel: MusicSearchViewModel,
    bottomPadding: Dp = 0.dp 
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    
    var isSearchFocused by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    var selectedSongForSheet by remember { mutableStateOf<Song?>(null) }

    val primaryColor = MaterialTheme.colorScheme.primary

    LaunchedEffect(isVoiceSearching) {
        if (!isVoiceSearching && uiState.searchQuery.isNotEmpty()) {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    val infiniteTransition = rememberInfiniteTransition()
    val micAlpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(animation = tween(600, easing = LinearEasing), repeatMode = RepeatMode.Reverse)
    )

    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().padding(bottom = bottomPadding).imePadding()
            .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) }
    ) {
        // 검색바
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.onSearchQueryChanged(it) },
                placeholder = { Text(if (isVoiceSearching) "듣고 있습니다..." else "아티스트, 노래, 앨범 등") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onSearchQueryChanged("") }) { Icon(Icons.Default.Close, "Clear") }
                        }
                        IconButton(onClick = onVoiceSearchClick) {
                            Icon(Icons.Default.Mic, null, tint = if (isVoiceSearching) Color.Red else primaryColor,
                                modifier = Modifier.graphicsLayer { alpha = if (isVoiceSearching) micAlpha else 1f })
                        }
                    }
                },
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).onFocusChanged { isSearchFocused = it.isFocused },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.performSearch(); focusManager.clearFocus() }),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            )
            
            if (isSearchFocused) {
                TextButton(onClick = { focusManager.clearFocus() }) { Text("취소", color = primaryColor) }
            } else {
                IconButton(onClick = onNavigateToPlaylists) { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null, modifier = Modifier.size(32.dp), tint = primaryColor) }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (!isSearchFocused && uiState.searchQuery.isEmpty()) {
                    // 1. 추천 차트
                    if (uiState.themes.isNotEmpty()) {
                        item { ThemeSection(title = "추천 차트", themes = uiState.themes, onNavigateToTheme = onNavigateToTheme) }
                    }
                    // 2. 추천 모음
                    if (uiState.collectionThemes.isNotEmpty()) {
                        item { ThemeSection(title = "추천 모음", themes = uiState.collectionThemes, onNavigateToTheme = onNavigateToTheme) }
                    }
                    // 3. 가수별 추천
                    if (uiState.artistThemes.isNotEmpty()) {
                        item { ThemeSection(title = "가수별 추천", themes = uiState.artistThemes, onNavigateToTheme = onNavigateToTheme) }
                    }
                    // 4. 장르별 보기 (그리드)
                    if (uiState.genreThemes.isNotEmpty()) {
                        item {
                            GenreGridSection(genres = uiState.genreThemes, onGenreClick = onNavigateToTheme)
                        }
                    }
                }

                // 5. 검색 결과 또는 주간 차트 리스트
                items(uiState.songs, key = { it.id.toString() + it.name + it.artist }) { song ->
                    SongListItem(
                        song = song,
                        onItemClick = { onSongClick(song) },
                        onMoreClick = { selectedSongForSheet = song; scope.launch { sheetState.show() } },
                        isDownloading = downloadingSongIds.contains(song.id),
                        isDownloaded = viewModel.isSongDownloaded(song)
                    )
                }
            }
            
            if (uiState.isLoading && uiState.songs.isEmpty()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MusicLoadingScreen() }
            }
        }
    }

    if (selectedSongForSheet != null) {
        val currentSong = selectedSongForSheet!!
        val isDownloaded = viewModel.isSongDownloaded(currentSong)
        ModalBottomSheet(onDismissRequest = { selectedSongForSheet = null }, sheetState = sheetState) {
            MoreOptionsSheet(
                song = currentSong,
                onNavigateToArtist = { scope.launch { sheetState.hide(); selectedSongForSheet = null; onNavigateToArtist(Artist(name = currentSong.artist)) } },
                onNavigateToAddToPlaylist = { scope.launch { sheetState.hide(); selectedSongForSheet = null; onNavigateToAddToPlaylist(it) } },
                onNavigateToAlbum = { scope.launch { sheetState.hide(); selectedSongForSheet = null; onNavigateToAlbum(Album(name = currentSong.albumName, artist = currentSong.artist)) } },
                onDownloadClick = { scope.launch { sheetState.hide(); selectedSongForSheet = null; onDownloadSong(currentSong) } },
                onDeleteClick = if (isDownloaded) { { scope.launch { sheetState.hide(); selectedSongForSheet = null; onDeleteSong(currentSong) } } } else null,
                isDownloaded = isDownloaded
            )
        }
    }
}

@Composable
fun GenreGridSection(genres: List<Theme>, onGenreClick: (Theme) -> Unit) {
    val colors = listOf(
        Pair(Color(0xFFE91E63), Color(0xFFFF80AB)), // 핑크
        Pair(Color(0xFF9C27B0), Color(0xFFEA80FC)), // 퍼플
        Pair(Color(0xFF2196F3), Color(0xFF82B1FF)), // 블루
        Pair(Color(0xFF4CAF50), Color(0xFFB9F6CA)), // 그린
        Pair(Color(0xFFFF9800), Color(0xFFFFE082))  // 오렌지
    )

    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Text(
            text = "장르별 보기",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
        )
        
        // 고정 높이 그리드 (5개 항목이므로 3행 예상)
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            genres.chunked(2).forEachIndexed { rowIndex, rowItems ->
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    rowItems.forEachIndexed { colIndex, genre ->
                        val colorIdx = (rowIndex * 2 + colIndex) % colors.size
                        GenreCard(
                            genre = genre,
                            startColor = colors[colorIdx].first,
                            endColor = colors[colorIdx].second,
                            modifier = Modifier.weight(1f).padding(end = if (colIndex == 0 && rowItems.size > 1) 12.dp else 0.dp),
                            onClick = { onGenreClick(genre) }
                        )
                    }
                    if (rowItems.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun GenreCard(genre: Theme, startColor: Color, endColor: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(100.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(startColor, endColor)))
            .clickable { onClick() }
            .padding(16.dp),
        contentAlignment = Alignment.BottomStart
    ) {
        Text(
            text = genre.name,
            style = MaterialTheme.typography.titleMedium.copy(color = Color.White, fontWeight = FontWeight.Bold)
        )
    }
}

@Composable
fun ThemeSection(title: String, themes: List<Theme>, onNavigateToTheme: (Theme) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(themes) { theme -> ThemeItem(theme = theme, onClick = { onNavigateToTheme(theme) }) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongCardItem(song: Song, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(modifier = Modifier.width(140.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(140.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            AsyncImage(model = song.metaPoster ?: song.streamUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (song.metaPoster == null) {
                Icon(Icons.Default.MusicNote, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = song.name ?: "", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
        Text(text = song.artist, style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun ThemeItem(theme: Theme, onClick: () -> Unit) {
    Column(modifier = Modifier.width(120.dp).clickable { onClick() }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(120.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MusicNote, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = theme.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
fun RecentSearchesView(
    recentSearches: List<RecentSearch>,
    onSearchClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit,
    onClearAll: () -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("최근 검색어", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("지우기", color = primaryColor, fontSize = 14.sp, modifier = Modifier.clickable { onClearAll() })
            }
        }
        items(recentSearches) { search ->
            Row(modifier = Modifier.fillMaxWidth().clickable { onSearchClick(search.query) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.History, null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(search.query, modifier = Modifier.weight(1f), fontSize = 15.sp)
                IconButton(onClick = { onDeleteClick(search.query) }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, null, tint = Color.LightGray, modifier = Modifier.size(14.dp))
                }
            }
            HorizontalDivider(modifier = Modifier.padding(start = 46.dp, end = 16.dp), thickness = 0.5.dp, color = Color.LightGray.copy(alpha = 0.3f))
        }
    }
}
