package com.nas.musicplayer.ui.music

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nas.musicplayer.*
import com.nas.musicplayer.db.RecentSearch
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicSearchScreen(
    onNavigateToArtist: (Artist) -> Unit,
    onNavigateToAddToPlaylist: (Song) -> Unit,
    onNavigateToAlbum: (Album) -> Unit,
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
    var lastAppliedQuery by remember { mutableStateOf("") }

    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    var selectedSongForSheet by remember { mutableStateOf<Song?>(null) }

    val primaryColor = MaterialTheme.colorScheme.primary

    LaunchedEffect(isVoiceSearching) {
        if (!isVoiceSearching && uiState.searchQuery.isNotEmpty()) {
            focusManager.clearFocus()
            keyboardController?.hide()
            lastAppliedQuery = uiState.searchQuery
        }
    }

    val infiniteTransition = rememberInfiniteTransition()
    val micAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding() 
            .padding(bottom = bottomPadding) 
            .imePadding()
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    focusManager.clearFocus()
                })
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.onSearchQueryChanged(it) },
                placeholder = { 
                    Text(if (isVoiceSearching) "듣고 있습니다..." else "아티스트, 노래, 앨범 등") 
                },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                                Icon(Icons.Default.Close, "Clear")
                            }
                        }
                        IconButton(onClick = onVoiceSearchClick) {
                            Icon(
                                imageVector = Icons.Default.Mic, 
                                contentDescription = "Voice Search", 
                                tint = if (isVoiceSearching) Color.Red else primaryColor,
                                modifier = Modifier.graphicsLayer { alpha = if (isVoiceSearching) micAlpha else 1f }
                            )
                        }
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .onFocusChanged { isSearchFocused = it.isFocused },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    lastAppliedQuery = uiState.searchQuery
                    viewModel.performSearch()
                    focusManager.clearFocus()
                }),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            )
            
            if (isSearchFocused) {
                TextButton(onClick = { focusManager.clearFocus() }) {
                    Text("취소", color = primaryColor)
                }
            } else {
                IconButton(onClick = onNavigateToPlaylists) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null, modifier = Modifier.size(32.dp), tint = primaryColor)
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            // 1. 배경 컨텐츠: Top 100 리스트 또는 검색 결과
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(uiState.songs, key = { it.id.toString() + it.name + it.artist }) { song ->
                    SongListItem(
                        song = song,
                        onItemClick = { onSongClick(song) },
                        onMoreClick = {
                            selectedSongForSheet = song
                            scope.launch { sheetState.show() }
                        },
                        isDownloading = downloadingSongIds.contains(song.id),
                        isDownloaded = viewModel.isSongDownloaded(song)
                    )
                }
            }
            
            // 로딩 화면 (결과 위에 덮음)
            if (uiState.isLoading && uiState.songs.isEmpty()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MusicLoadingScreen()
                }
            }

            // 결과 없음 화면 (결과 위에 덮음)
            if (uiState.songs.isEmpty() && uiState.searchQuery.isNotEmpty() && !isSearchFocused && !uiState.isLoading) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.SearchOff, null, modifier = Modifier.size(64.dp), tint = Color.LightGray)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("결과 없음", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                        Text("'${uiState.searchQuery}'에 대한 결과를 찾을 수 없습니다.", textAlign = TextAlign.Center)
                    }
                }
            }

            // 2. 오버레이 컨텐츠: 최근 검색어 목록 (가장 위에 덮음)
            // 포커스 중 + 검색어 비어있음 + 검색 기록 있음 일 때만 표시
            if (isSearchFocused && uiState.searchQuery.isEmpty() && uiState.recentSearches.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RecentSearchesView(
                        recentSearches = uiState.recentSearches,
                        onSearchClick = { 
                            viewModel.onSearchQueryChanged(it)
                            viewModel.performSearch(it)
                            focusManager.clearFocus()
                        },
                        onDeleteClick = { viewModel.deleteRecentSearch(it) },
                        onClearAll = { viewModel.clearAllRecentSearches() }
                    )
                }
            }
        }
    }

    if (selectedSongForSheet != null) {
        val currentSong = selectedSongForSheet!!
        val isDownloaded = viewModel.isSongDownloaded(currentSong)
        ModalBottomSheet(onDismissRequest = { selectedSongForSheet = null }, sheetState = sheetState) {
            MoreOptionsSheet(
                song = currentSong,
                onNavigateToArtist = {
                    scope.launch { 
                        sheetState.hide()
                        selectedSongForSheet = null
                        onNavigateToArtist(Artist(name = currentSong.artist, imageUrl = currentSong.metaPoster ?: currentSong.streamUrl))
                    }
                },
                onNavigateToAddToPlaylist = {
                    scope.launch {
                        sheetState.hide()
                        selectedSongForSheet = null
                        onNavigateToAddToPlaylist(it)
                    }
                },
                onNavigateToAlbum = {
                    scope.launch {
                        sheetState.hide()
                        selectedSongForSheet = null
                        onNavigateToAlbum(Album(name = currentSong.albumName, artist = currentSong.artist, imageUrl = currentSong.metaPoster ?: currentSong.streamUrl))
                    }
                },
                onDownloadClick = {
                    scope.launch {
                        sheetState.hide()
                        selectedSongForSheet = null
                        onDownloadSong(currentSong)
                    }
                },
                onDeleteClick = if (isDownloaded) {
                    {
                        scope.launch {
                            sheetState.hide()
                            selectedSongForSheet = null
                            onDeleteSong(currentSong)
                        }
                    }
                } else null,
                isDownloaded = isDownloaded
            )
        }
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
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("최근 검색어", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("지우기", color = primaryColor, fontSize = 14.sp, modifier = Modifier.clickable { onClearAll() })
            }
        }
        items(recentSearches) { search ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onSearchClick(search.query) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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
