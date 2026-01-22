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
    localSongs: List<Song> = emptyList(),
    voiceQuery: String = "",
    isVoiceFinal: Boolean = false,
    isVoiceSearching: Boolean = false,
    onVoiceSearchClick: () -> Unit = {},
    onVoiceQueryConsumed: () -> Unit = {}
) {
    val searchViewModel: MusicSearchViewModel = viewModel(
        factory = MusicSearchViewModel.Factory(musicRepository)
    )
    
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val currentSong by musicPlayerViewModel.currentSong.collectAsState()
    val isPlaying by musicPlayerViewModel.isPlaying.collectAsState()

    // 인라인 음성 검색 로직: 실시간으로 텍스트를 보여주되 최종일 때만 검색 실행
    LaunchedEffect(voiceQuery, isVoiceFinal) {
        if (voiceQuery.isNotEmpty()) {
            searchViewModel.onSearchQueryChanged(voiceQuery)
            if (isVoiceFinal) {
                searchViewModel.performSearch(voiceQuery)
                onVoiceQueryConsumed()
            }
        }
    }

    val navBarHeight = 80.dp
    val miniPlayerHeight = 72.dp

    MaterialTheme {
        Scaffold(
            contentWindowInsets = WindowInsets(0.dp),
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
                            modifier = Modifier.height(navBarHeight),
                            windowInsets = WindowInsets(0.dp) 
                        ) {
                            NavigationBarItem(
                                selected = currentRoute == "search" || currentRoute == null,
                                onClick = { 
                                    navController.navigate("search") {
                                        popUpTo("search") { inclusive = true }
                                    }
                                },
                                icon = { Icon(Icons.Default.Search, "Search", modifier = Modifier.size(26.dp)) },
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
                                icon = { Icon(Icons.Default.LibraryMusic, "Library", modifier = Modifier.size(26.dp)) },
                                label = { Text("보관함", fontSize = 12.sp) },
                                alwaysShowLabel = true
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            val totalBottomPadding = if (currentRoute != "player") {
                val basePadding = innerPadding.calculateBottomPadding().coerceAtLeast(navBarHeight)
                if (currentSong != null) basePadding + miniPlayerHeight else basePadding
            } else {
                0.dp
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = totalBottomPadding)
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
                            onNavigateToAddToPlaylist = { /* TODO */ },
                            onVoiceSearchClick = onVoiceSearchClick,
                            isVoiceSearching = isVoiceSearching // 인라인 상태 전달
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
                    // ... 나머지 컴포저블 유지
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
            }

            if (currentRoute != "player" && currentSong != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = innerPadding.calculateBottomPadding())
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
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
