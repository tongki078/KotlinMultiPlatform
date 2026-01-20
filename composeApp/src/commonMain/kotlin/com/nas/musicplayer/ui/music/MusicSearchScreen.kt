package com.nas.musicplayer.ui.music

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
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
    viewModel: MusicSearchViewModel,
    mainViewModel: MainViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val aiResult by mainViewModel.outputText.collectAsState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    
    var isSearchFocused by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    var selectedSongForSheet by remember { mutableStateOf<Song?>(null) }

    val primaryColor = MaterialTheme.colorScheme.primary

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { 
                    Text("검색", fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
                },
                actions = {
                    IconButton(onClick = onNavigateToPlaylists) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null, modifier = Modifier.size(32.dp), tint = primaryColor)
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                TextField(
                    value = uiState.searchQuery,
                    onValueChange = { viewModel.onSearchQueryChanged(it) },
                    placeholder = { Text("아티스트, 노래, 앨범 등") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .onFocusChanged { isSearchFocused = it.isFocused },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
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
            }
            
            if (uiState.searchQuery.isEmpty() && !isSearchFocused) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("AI 추천", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Button(onClick = {
                            mainViewModel.generateText("활동적인 분위기에 어울리는 최신 팝송 3곡만 추천해줘. 각 추천은 '가수 - 제목' 형식으로 한 줄씩 표시해줘.")
                        }) {
                            Text("추천 받기")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    if (aiResult.isNotBlank()) {
                        val recommendations = aiResult.lines().filter { it.isNotBlank() }
                        recommendations.forEach { recommendation ->
                            Text(
                                text = "🎵 $recommendation",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.onSearchQueryChanged(recommendation)
                                        viewModel.performSearch()
                                        focusManager.clearFocus()
                                        isSearchFocused = false
                                    }
                                    .padding(vertical = 8.dp),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 120.dp),
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

                if (isSearchFocused && uiState.searchQuery.isEmpty() && uiState.recentSearches.isNotEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        RecentSearchesView(
                            recentSearches = uiState.recentSearches,
                            onSearchClick = { 
                                viewModel.performSearch(it)
                                focusManager.clearFocus()
                                isSearchFocused = false
                            },
                            onDeleteClick = { viewModel.deleteRecentSearch(it) },
                            onClearAll = { viewModel.clearAllRecentSearches() }
                        )
                    }
                }

                if (uiState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }

    if (selectedSongForSheet != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedSongForSheet = null },
            sheetState = sheetState
        ) {
            val currentSong = selectedSongForSheet!!
            MoreOptionsSheet(
                song = currentSong,
                onNavigateToArtist = {
                    onNavigateToArtist(Artist(name = currentSong.artist))
                    scope.launch { sheetState.hide() }.invokeOnCompletion { selectedSongForSheet = null }
                },
                onNavigateToAddToPlaylist = {
                    onNavigateToAddToPlaylist(it)
                    scope.launch { sheetState.hide() }.invokeOnCompletion { selectedSongForSheet = null }
                },
                onNavigateToAlbum = {
                    onNavigateToAlbum(Album(name = currentSong.albumName, artist = currentSong.artist))
                    scope.launch { sheetState.hide() }.invokeOnCompletion { selectedSongForSheet = null }
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
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("최근 검색어", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("지우기", color = primaryColor, modifier = Modifier.clickable { onClearAll() })
            }
        }
        items(recentSearches) { search ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onSearchClick(search.query) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.History, null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Text(search.query, modifier = Modifier.weight(1f), fontSize = 17.sp)
                IconButton(onClick = { onDeleteClick(search.query) }) {
                    Icon(Icons.Default.Close, null, tint = Color.LightGray, modifier = Modifier.size(16.dp))
                }
            }
            HorizontalDivider(modifier = Modifier.padding(start = 52.dp, end = 16.dp), thickness = 0.5.dp)
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
