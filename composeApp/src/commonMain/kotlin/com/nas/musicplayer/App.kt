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
    localSongs: List<Song> = emptyList() // 로컬 노래 리스트 추가
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
            contentWindowInsets = WindowInsets(0.dp),
            bottomBar = {
                if (currentRoute != "player") {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp
                    ) {
                        NavigationBarItem(
                            selected = currentRoute == "search" || currentRoute == null,
                            onClick = { 
                                navController.navigate("search") {
                                    popUpTo("search") { inclusive = true }
                                }
                            },
                            icon = { Icon(Icons.Default.Search, "Search") },
                            label = { Text("검색", fontSize = 11.sp) },
                            alwaysShowLabel = true
                        )
                        NavigationBarItem(
                            selected = currentRoute == "library" || currentRoute == "playlists",
                            onClick = { 
                                navController.navigate("library") {
                                    popUpTo("search")
                                }
                            },
                            icon = { Icon(Icons.Default.LibraryMusic, "Library") },
                            label = { Text("보관함", fontSize = 11.sp) },
                            alwaysShowLabel = true
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(bottom = innerPadding.calculateBottomPadding())) {
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
                            localSongs = localSongs, // 로컬 노래 전달
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
