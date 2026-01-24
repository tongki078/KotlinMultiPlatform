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
    onDeleteSong: (Song) -> Unit, // 삭제 추가
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
                    if (isSearchFocused) {
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
            
            if (isSearchFocused || (uiState.searchQuery != lastAppliedQuery)) {
                TextButton(
                    onClick = {
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
                .padding(top = 4.dp) 
        ) {
            when {
                uiState.isLoading && uiState.songs.isEmpty() -> {
                    MusicLoadingScreen()
                }
                uiState.songs.isEmpty() && uiState.searchQuery.isNotEmpty() && !isSearchFocused -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 32.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SearchOff,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = Color.LightGray
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "결과 없음",
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "'${uiState.searchQuery}'에 대한 결과를 찾을 수 없습니다.",
                            style = MaterialTheme.typography.bodyMedium.copy(color = Color.Gray),
                            textAlign = TextAlign.Center
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(bottom = 16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(uiState.songs, key = { it.id.toString() + it.name + it.artist }) { song ->
                            SongListItem(
                                song = song,
                                onItemClick = { onSongClick(song) },
                                onMoreClick = {
                                    selectedSongForSheet = song
                                    scope.launch { sheetState.show() }
                                },
                                isDownloading = downloadingSongIds.contains(song.id),
                                isDownloaded = viewModel.isSongDownloaded(song) // 다운로드 여부 체크
                            )
                        }
                    }
                }
            }
        }
    }

    if (selectedSongForSheet != null) {
        val currentSong = selectedSongForSheet!!
        val isDownloaded = viewModel.isSongDownloaded(currentSong)
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
