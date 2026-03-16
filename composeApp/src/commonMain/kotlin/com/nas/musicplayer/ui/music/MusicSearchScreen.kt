package com.nas.musicplayer.ui.music

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nas.musicplayer.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicSearchScreen(
    onNavigateToArtist: (Artist) -> Unit,
    onNavigateToAddToPlaylist: (Song) -> Unit,
    onNavigateToAlbum: (Album) -> Unit,
    onNavigateToTheme: (Theme) -> Unit,
    onNavigateToArtistGrid: (String) -> Unit,
    onSongClick: (Song, List<Song>) -> Unit,
    onNavigateToPlaylists: () -> Unit,
    onVoiceSearchClick: () -> Unit,
    onDownloadSong: (Song) -> Unit,
    onDeleteSong: (Song) -> Unit,
    onNavigateToLibrary: () -> Unit,
    downloadingSongIds: Set<Long> = emptySet(),
    isVoiceSearching: Boolean = false,
    viewModel: MusicSearchViewModel,
    bottomPadding: Dp = 0.dp 
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    
    var isSearchFocused by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    var selectedSongForSheet by remember { mutableStateOf<Song?>(null) }

    val primaryColor = MaterialTheme.colorScheme.primary

    LaunchedEffect(Unit) {
        focusManager.clearFocus()
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.onSearchQueryChanged(it) },
                placeholder = { Text(if (isVoiceSearching) "음악 검색..." else "아티스트, 노래, 앨범") },
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
                IconButton(onClick = onNavigateToLibrary) { 
                    Icon(Icons.Default.LibraryMusic, null, modifier = Modifier.size(28.dp), tint = primaryColor) 
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (isSearchFocused || uiState.searchQuery.isNotEmpty()) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (uiState.searchArtists.isNotEmpty()) {
                        item { SearchSectionTitle("아티스트") }
                        item {
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                items(uiState.searchArtists) { artist ->
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.width(90.dp).clickable { onNavigateToArtist(Artist(name = artist.name)) }
                                    ) {
                                        AsyncImage(
                                            model = artist.cover,
                                            contentDescription = null,
                                            modifier = Modifier.size(80.dp).clip(CircleShape).background(Color.Gray.copy(alpha = 0.1f)),
                                            contentScale = ContentScale.Crop
                                        )
                                        Text(artist.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                                    }
                                }
                            }
                        }
                    }

                    if (uiState.searchAlbums.isNotEmpty()) {
                        item { SearchSectionTitle("앨범") }
                        item {
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(uiState.searchAlbums) { album ->
                                    Column(modifier = Modifier.width(130.dp).clickable { onNavigateToAlbum(album) }) {
                                        AsyncImage(
                                            model = album.imageUrl,
                                            contentDescription = null,
                                            modifier = Modifier.size(130.dp).clip(RoundedCornerShape(8.dp)).background(Color.Gray.copy(alpha = 0.1f)),
                                            contentScale = ContentScale.Crop
                                        )
                                        Text(album.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                                        Text(album.artist, style = MaterialTheme.typography.labelSmall, color = Color.Gray, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }

                    if (uiState.searchResults.isNotEmpty()) {
                        item { SearchSectionTitle("노래") }
                        items(uiState.searchResults, key = { it.id.toString() + it.name + it.artist }) { song ->
                            SongListItem(
                                song = song,
                                onItemClick = { onSongClick(song, uiState.searchResults) },
                                onMoreClick = { selectedSongForSheet = song; scope.launch { sheetState.show() } },
                                isDownloading = downloadingSongIds.contains(song.id),
                                isDownloaded = viewModel.isSongDownloaded(song)
                            )
                        }
                    } else {
                        item {
                            Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                Text(if(uiState.searchQuery.isEmpty()) "검색어를 입력해 주세요." else "검색 결과가 없습니다.", color = Color.Gray)
                            }
                        }
                    }
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    if (uiState.themes.isNotEmpty()) {
                        item { ThemeSection(title = "추천 차트", themes = uiState.themes, onNavigateToTheme = onNavigateToTheme) }
                    }
                    if (uiState.collectionThemes.isNotEmpty()) {
                        item { ThemeSection(title = "추천 모음", themes = uiState.collectionThemes, onNavigateToTheme = onNavigateToTheme) }
                    }
                    if (uiState.artistThemes.isNotEmpty()) {
                        item { ThemeSection(title = "가수별 추천", themes = uiState.artistThemes, onNavigateToTheme = onNavigateToTheme) }
                    }
                    if (uiState.genreThemes.isNotEmpty()) {
                        item { 
                            GenreGridSection(
                                genres = uiState.genreThemes, 
                                onGenreClick = { theme -> onNavigateToArtistGrid(theme.name) }
                            ) 
                        }
                    }
                    
                    if (uiState.top100Songs.isNotEmpty()) {
                        item {
                            Text(
                                text = "멜론 주간 TOP 100",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 12.dp)
                            )
                        }
                        items(uiState.top100Songs, key = { it.id.toString() + it.name + it.artist }) { song ->
                            SongListItem(
                                song = song,
                                onItemClick = { onSongClick(song, uiState.top100Songs) },
                                onMoreClick = { selectedSongForSheet = song; scope.launch { sheetState.show() } },
                                isDownloading = downloadingSongIds.contains(song.id),
                                isDownloaded = viewModel.isSongDownloaded(song)
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
fun SearchSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 12.dp)
    )
}

@Composable
fun GenreGridSection(genres: List<Theme>, onGenreClick: (Theme) -> Unit) {
    val colors = listOf(
        Pair(Color(0xFFE91E63), Color(0xFFFF80AB)),
        Pair(Color(0xFF9C27B0), Color(0xFFEA80FC)),
        Pair(Color(0xFF2196F3), Color(0xFF82B1FF)),
        Pair(Color(0xFF4CAF50), Color(0xFFB9F6CA)),
        Pair(Color(0xFFFF9800), Color(0xFFFFE082))
    )

    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Text(
            text = "장르별 보기",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
        )
        
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

@Composable
fun ThemeItem(theme: Theme, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(110.dp).clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(110.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Gray.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = theme.imageUrl ?: "", 
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = theme.name,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}
