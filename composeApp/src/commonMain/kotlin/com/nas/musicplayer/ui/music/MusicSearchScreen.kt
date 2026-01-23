package com.nas.musicplayer.ui.music

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
    onSongClick: (Song) -> Unit,
    onNavigateToPlaylists: () -> Unit,
    onVoiceSearchClick: () -> Unit,
    isVoiceSearching: Boolean = false,
    viewModel: MusicSearchViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    
    var isSearchFocused by remember { mutableStateOf(false) }
    // 마지막으로 실행된 실제 검색어를 저장하여 취소 시 복구할 용도
    var lastAppliedQuery by remember { mutableStateOf("") }

    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    var selectedSongForSheet by remember { mutableStateOf<Song?>(null) }

    val primaryColor = MaterialTheme.colorScheme.primary

    // 음성 검색이 종료되고 쿼리가 있을 때 자동으로 포커스를 해제하여 검색 결과 노출
    LaunchedEffect(isVoiceSearching) {
        if (!isVoiceSearching && uiState.searchQuery.isNotEmpty()) {
            focusManager.clearFocus()
            keyboardController?.hide()
            lastAppliedQuery = uiState.searchQuery
        }
    }

    // 마이크 애니메이션 (깜빡임 효과)
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
            // 외부 터치 시 포커스 해제 및 키보드 닫기 구현
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    if (isSearchFocused) {
                        // 입력 중이던 텍스트를 마지막 검색 결과 상태로 복구
                        viewModel.onSearchQueryChanged(lastAppliedQuery)
                        focusManager.clearFocus()
                        isSearchFocused = false
                        keyboardController?.hide()
                    }
                })
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 0.dp),
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
                    IconButton(onClick = onVoiceSearchClick) {
                        Icon(
                            imageVector = Icons.Default.Mic, 
                            contentDescription = "Voice Search", 
                            tint = if (isVoiceSearching) Color.Red else primaryColor,
                            modifier = Modifier.graphicsLayer {
                                alpha = if (isVoiceSearching) micAlpha else 1f
                            }
                        )
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
                    isSearchFocused = false
                }),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            )
            
            // 검색 모드일 때 나타나는 '취소' 버튼 (글자 형태가 더 범용적입니다)
            if (isSearchFocused || (uiState.searchQuery != lastAppliedQuery)) {
                TextButton(
                    onClick = {
                        // 현재 입력 중인 내용을 버리고 이전 검색 결과(또는 빈 상태)로 복구
                        viewModel.onSearchQueryChanged(lastAppliedQuery)
                        focusManager.clearFocus()
                        isSearchFocused = false
                        keyboardController?.hide()
                    },
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    Text("취소", color = primaryColor)
                }
            } else {
                // 평상시 플레이리스트 아이콘
                IconButton(onClick = onNavigateToPlaylists, modifier = Modifier.padding(start = 4.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.PlaylistPlay, 
                        null, 
                        modifier = Modifier.size(32.dp), 
                        tint = primaryColor
                    )
                }
            }
        }
        
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(top = 8.dp, bottom = 8.dp) 
        ) {
            // 로딩 중일 때 Shimmer 화면 표시 (화면 중앙 로딩 아이콘 제거됨)
            if (uiState.isLoading) {
                MusicLoadingScreen()
            } else {
                // 데이터 로드 완료 시 리스트 표시
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.songs, key = { it.id }) { song ->
                        SongListItem(
                            song = song,
                            onItemClick = { onSongClick(song) },
                            onMoreClick = {
                                selectedSongForSheet = song
                                scope.launch { sheetState.show() }
                            }
                        )
                    }
                }
            }

            // 검색 포커스 시 최근 검색어 화면 표시
            if (isSearchFocused && uiState.searchQuery.isEmpty() && uiState.recentSearches.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RecentSearchesView(
                        recentSearches = uiState.recentSearches,
                        onSearchClick = { 
                            lastAppliedQuery = it
                            viewModel.performSearch(it)
                            focusManager.clearFocus()
                            isSearchFocused = false
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
        ModalBottomSheet(
            onDismissRequest = { selectedSongForSheet = null },
            sheetState = sheetState
        ) {
            MoreOptionsSheet(
                song = currentSong,
                onNavigateToArtist = {
                    scope.launch { 
                        sheetState.hide()
                        selectedSongForSheet = null
                        onNavigateToArtist(Artist(name = currentSong.artist))
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
                        onNavigateToAlbum(Album(name = currentSong.albumName, artist = currentSong.artist))
                    }
                }
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
    LazyColumn(modifier = Modifier.fillMaxSize()) {
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
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSearchClick(search.query) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.History, null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(search.query, modifier = Modifier.weight(1f), fontSize = 15.sp)
                IconButton(
                    onClick = { onDeleteClick(search.query) },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Default.Close, null, tint = Color.LightGray, modifier = Modifier.size(14.dp))
                }
            }
            HorizontalDivider(modifier = Modifier.padding(start = 46.dp, end = 16.dp), thickness = 0.5.dp, color = Color.LightGray.copy(alpha = 0.3f))
        }
    }
}

@Composable
fun SongListItem(song: Song, onItemClick: () -> Unit, onMoreClick: () -> Unit) {
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
            IconButton(onClick = onMoreClick) {
                Icon(Icons.Default.MoreVert, null, tint = Color.Gray)
            }
        }
        HorizontalDivider(modifier = Modifier.padding(start = 88.dp, end = 16.dp), thickness = 0.5.dp, color = Color.LightGray.copy(alpha = 0.5f))
    }
}

@Composable
fun MoreOptionsSheet(song: Song, onNavigateToArtist: () -> Unit, onNavigateToAddToPlaylist: (Song) -> Unit, onNavigateToAlbum: () -> Unit) {
    val primaryColor = MaterialTheme.colorScheme.primary
    Column(modifier = Modifier.padding(bottom = 32.dp)) {
        ListItem(
            headlineContent = { Text(song.name ?: "") },
            supportingContent = { Text(song.artist) },
            leadingContent = { AsyncImage(model = song.metaPoster ?: song.streamUrl, contentDescription = null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop) }
        )
        HorizontalDivider()
        ListItem(headlineContent = { Text("플레이리스트에 추가") }, leadingContent = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null, tint = primaryColor) }, modifier = Modifier.clickable { onNavigateToAddToPlaylist(song) })
        ListItem(headlineContent = { Text("아티스트 보기") }, leadingContent = { Icon(Icons.Default.Person, null, tint = primaryColor) }, modifier = Modifier.clickable { onNavigateToArtist() })
        ListItem(headlineContent = { Text("앨범 보기") }, leadingContent = { Icon(Icons.Default.Album, null, tint = primaryColor) }, modifier = Modifier.clickable { onNavigateToAlbum() })
    }
}
