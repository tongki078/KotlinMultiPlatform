package com.nas.musicplayer.ui.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nas.musicplayer.Album
import com.nas.musicplayer.Artist
import com.nas.musicplayer.Song
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    localSongs: List<Song>,
    onSongClick: (Song, List<Song>) -> Unit,
    onNavigateToAddToPlaylist: (Song) -> Unit,
    onNavigateToPlaylists: () -> Unit,
    onNavigateToArtist: (Artist) -> Unit,
    onNavigateToAlbum: (Album) -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    var selectedSongForSheet by remember { mutableStateOf<Song?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 0.dp), // 좌우 여백 제거하여 꽉 차게
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 16.dp, 
                bottom = 16.dp
            )
        ) {
            // 1. 애플 뮤직 스타일의 대형 타이틀 (스크롤 가능)
            item {
                Text(
                    text = "보관함",
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, bottom = 12.dp)
                )
            }

            // 2. 카테고리 메뉴 섹션 (위쪽부터 밀착 배치)
            item {
                Column {
                    LibraryMenuItem("플레이리스트", Icons.Default.QueueMusic, onClick = onNavigateToPlaylists)
                    LibraryMenuItem("아티스트", Icons.Default.Person, onClick = { /* 구현 예정 */ })
                    LibraryMenuItem("앨범", Icons.Default.Album, onClick = { /* 구현 예정 */ })
                    LibraryMenuItem("노래", Icons.Default.MusicNote, onClick = { /* 구현 예정 */ })
                    LibraryMenuItem("다운로드됨", Icons.Default.DownloadForOffline, onClick = { /* 구현 예정 */ })
                }
            }

            // 3. 파일 유무에 따른 섹션 처리
            if (localSongs.isNotEmpty()) {
                item {
                    Text(
                        text = "최근 추가된 음악",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 16.dp, top = 32.dp, bottom = 12.dp)
                    )
                }
                
                items(localSongs.take(20)) { song -> 
                    SongListItem(
                        song = song,
                        onItemClick = { onSongClick(song, localSongs) },
                        onMoreClick = { 
                            selectedSongForSheet = song
                            scope.launch { sheetState.show() }
                        }
                    )
                }
            } else {
                // 메뉴 바로 아래에 가벼운 안내 문구 배치
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "저장된 음악이 없습니다.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.Gray
                        )
                    }
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
                }
            )
        }
    }
}

@Composable
fun LibraryMenuItem(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Column {
        ListItem(
            headlineContent = { Text(title, fontSize = 20.sp, fontWeight = FontWeight.Normal) },
            leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp)) },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.LightGray.copy(alpha = 0.5f)) },
            modifier = Modifier.clickable { onClick() },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
        HorizontalDivider(
            modifier = Modifier.padding(start = 60.dp),
            thickness = 0.5.dp,
            color = Color.LightGray.copy(alpha = 0.3f)
        )
    }
}
