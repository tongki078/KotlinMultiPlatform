package com.nas.musicplayer

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nas.musicplayer.ui.music.*

@Composable
fun App(
    musicRepository: MusicRepository,
    musicPlayerViewModel: MusicPlayerViewModel,
    localSongs: List<Song> = emptyList()
) {
    val searchViewModel: MusicSearchViewModel = viewModel(
        factory = MusicSearchViewModel.Factory(musicRepository)
    )
    
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val currentSong by musicPlayerViewModel.currentSong.collectAsState()
    val isPlaying by musicPlayerViewModel.isPlaying.collectAsState()

    MaterialTheme {
        Scaffold(
            bottomBar = {
                if (currentRoute != "player") {
                    Column {
                        HorizontalDivider(
                            thickness = 0.5.dp, 
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                        )
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 0.dp,
                            // 상하 여백을 대칭으로 만들기 위해 높이를 확보하고 인셋을 명시적으로 제어
                            modifier = Modifier.height(80.dp),
                            // 위아래 여백을 동일하게 가져가기 위해 내부 인셋을 비우고 정중앙 배치를 유도
                            windowInsets = WindowInsets(0.dp) 
                        ) {
                            NavigationBarItem(
                                selected = currentRoute == "search" || currentRoute == null,
                                onClick = { 
                                    navController.navigate("search") {
                                        popUpTo("search") { inclusive = true }
                                    }
                                },
                                icon = { 
                                    Icon(
                                        imageVector = Icons.Default.Search, 
                                        contentDescription = "Search",
                                        modifier = Modifier.size(26.dp) // 아이콘 크기 최적화
                                    ) 
                                },
                                label = { Text("검색", fontSize = 12.sp) },
                                alwaysShowLabel = true
                            )
                            NavigationBarItem(
                                selected = currentRoute == "library" || currentRoute == "playlists",
                                onClick = { 
                                    navController.navigate("library") {
                                        popUpTo("search")
                                    }
                                },
                                icon = { 
                                    Icon(
                                        imageVector = Icons.Default.LibraryMusic, 
                                        contentDescription = "Library",
                                        modifier = Modifier.size(26.dp)
                                    ) 
                                },
                                label = { Text("보관함", fontSize = 12.sp) },
                                alwaysShowLabel = true
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            // 하단 탭 + 시스템 네비게이션 바 영역을 포함한 하단 패딩 적용
            // NavigationBar의 높이가 80dp이므로 그에 맞춰 본문 영역 확보
            val bottomPadding = if (currentRoute != "player") {
                innerPadding.calculateBottomPadding().coerceAtLeast(80.dp)
            } else {
                0.dp
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = bottomPadding)
            ) {
                NavHost(navController = navController, startDestination = "search") {
                    composable("search") {
                        MusicSearchScreen(
                            viewModel = searchViewModel,
                            onSongClick = { song -> 
                                val currentSongs = searchViewModel.uiState.value.songs
                                musicPlayerViewModel.playSong(song, currentSongs)
                            },
                            onNavigateToPlaylists = { navController.navigate("playlists") },
                            onNavigateToArtist = { /* TODO */ },
                            onNavigateToAlbum = { /* TODO */ },
                            onNavigateToAddToPlaylist = { /* TODO */ }
                        )
                    }
                    composable("library") {
                        LibraryScreen(
                            localSongs = localSongs,
                            onSongClick = { song, list ->
                                musicPlayerViewModel.playSong(song, list)
                                navController.navigate("player")
                            },
                            onNavigateToAddToPlaylist = { /* TODO */ },
                            onNavigateToPlaylists = { navController.navigate("playlists") },
                            onNavigateToArtist = { /* TODO */ },
                            onNavigateToAlbum = { /* TODO */ }
                        )
                    }
                    composable("playlists") {
                        PlaylistsListScreen(
                            repository = musicRepository,
                            onPlaylistClick = { id -> 
                                navController.navigate("playlist_detail/$id")
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(
                        "playlist_detail/{playlistId}",
                        arguments = listOf(navArgument("playlistId") { type = NavType.IntType })
                    ) { backStackEntry ->
                        val playlistId = backStackEntry.arguments?.getInt("playlistId") ?: -1
                        val playlistViewModel = remember(playlistId) { 
                            PlaylistViewModel(playlistId, musicRepository) 
                        }
                        PlaylistViewScreen(
                            viewModel = playlistViewModel,
                            playerViewModel = musicPlayerViewModel,
                            onSongClick = { song, list ->
                                musicPlayerViewModel.playSong(song, list)
                                navController.navigate("player")
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("player") {
                        NowPlayingScreen(
                            viewModel = musicPlayerViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }

                if (currentRoute != "player" && currentSong != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 4.dp)
                    ) {
                        MiniPlayer(
                            song = currentSong!!,
                            isPlaying = isPlaying,
                            onTogglePlay = { musicPlayerViewModel.togglePlayPause() },
                            onNextClick = { musicPlayerViewModel.playNext() },
                            onClick = { navController.navigate("player") }
                        )
                    }
                }
            }
        }
    }
}
