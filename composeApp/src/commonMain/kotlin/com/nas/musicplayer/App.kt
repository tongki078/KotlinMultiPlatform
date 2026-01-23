package com.nas.musicplayer

import androidx.compose.foundation.clickable
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
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nas.musicplayer.ui.music.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
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

    // iOS 경로 인자 크래시를 방지하기 위한 공유 상태
    var selectedAlbumForDetail by remember { mutableStateOf<Album?>(null) }
    var selectedArtistForDetail by remember { mutableStateOf<Artist?>(null) }
    var songToAddToPlaylist by remember { mutableStateOf<Song?>(null) }

    LaunchedEffect(voiceQuery, isVoiceFinal) {
        if (voiceQuery.isNotEmpty()) {
            searchViewModel.onSearchQueryChanged(voiceQuery)
            if (isVoiceFinal) {
                searchViewModel.performSearch(voiceQuery)
                onVoiceQueryConsumed()
            }
        }
    }

    val miniPlayerHeight = 72.dp

    MaterialTheme {
        Scaffold(
            bottomBar = {
                val route = currentRoute ?: ""
                val isPlayerOrDetail = route == "player" || 
                                     route == "album_detail" || 
                                     route == "artist_detail" ||
                                     route == "add_to_playlist"
                
                if (!isPlayerOrDetail) {
                    Column {
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 0.dp,
                            windowInsets = WindowInsets(0, 0, 0, 0)
                        ) {
                            NavigationBarItem(
                                selected = route == "search" || route == "",
                                onClick = { 
                                    navController.navigate("search") {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(Icons.Default.Search, "Search", modifier = Modifier.size(26.dp)) },
                                label = { Text("검색", fontSize = 12.sp) },
                                alwaysShowLabel = true
                            )
                            NavigationBarItem(
                                selected = route == "library" || route == "playlists" || route.startsWith("playlist_detail"),
                                onClick = { 
                                    navController.navigate("library") {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
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
            val bottomPadding = innerPadding.calculateBottomPadding()
            // player나 add_to_playlist 화면일 때는 하단 패딩을 0으로 설정하여 전체 화면을 사용하게 함
            val totalBottomPadding = if (currentRoute != "player" && currentRoute != "add_to_playlist") {
                if (currentSong != null) bottomPadding + miniPlayerHeight else bottomPadding
            } else {
                0.dp
            }

            Box(modifier = Modifier.fillMaxSize().padding(bottom = totalBottomPadding)) {
                NavHost(navController = navController, startDestination = "search") {
                    composable("search") {
                        MusicSearchScreen(
                            viewModel = searchViewModel,
                            onSongClick = { song -> musicPlayerViewModel.playSong(song, searchViewModel.uiState.value.songs) },
                            onNavigateToPlaylists = { navController.navigate("playlists") },
                            onNavigateToArtist = { artist ->
                                selectedArtistForDetail = artist.copy(popularSongs = searchViewModel.uiState.value.songs.filter { it.artist == artist.name })
                                navController.navigate("artist_detail")
                            },
                            onNavigateToAlbum = { album ->
                                selectedAlbumForDetail = album.copy(songs = searchViewModel.uiState.value.songs.filter { it.albumName == album.name })
                                navController.navigate("album_detail")
                            },
                            onNavigateToAddToPlaylist = { song ->
                                songToAddToPlaylist = song
                                navController.navigate("add_to_playlist")
                            },
                            onVoiceSearchClick = onVoiceSearchClick,
                            isVoiceSearching = isVoiceSearching
                        )
                    }
                    composable("library") {
                        LibraryScreen(
                            localSongs = localSongs,
                            onSongClick = { song, list ->
                                musicPlayerViewModel.playSong(song, list)
                                navController.navigate("player")
                            },
                            onNavigateToAddToPlaylist = { song ->
                                songToAddToPlaylist = song
                                navController.navigate("add_to_playlist")
                            },
                            onNavigateToPlaylists = { navController.navigate("playlists") },
                            onNavigateToArtist = { artist -> 
                                selectedArtistForDetail = artist.copy(popularSongs = localSongs.filter { it.artist == artist.name })
                                navController.navigate("artist_detail")
                            },
                            onNavigateToAlbum = { album -> 
                                selectedAlbumForDetail = album.copy(songs = localSongs.filter { it.albumName == album.name })
                                navController.navigate("album_detail")
                            }
                        )
                    }
                    composable("playlists") {
                        PlaylistsListScreen(
                            repository = musicRepository,
                            onPlaylistClick = { id -> navController.navigate("playlist_detail/$id") },
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(
                        "playlist_detail/{playlistId}",
                        arguments = listOf(navArgument("playlistId") { type = NavType.IntType })
                    ) { backStackEntry ->
                        val playlistId = backStackEntry.arguments?.getInt("playlistId") ?: -1
                        val playlistViewModel = remember(playlistId) { PlaylistViewModel(playlistId, musicRepository) }
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
                    composable("album_detail") {
                        selectedAlbumForDetail?.let { album ->
                            AlbumDetailScreen(
                                album = album,
                                onBack = { navController.popBackStack() },
                                onSongClick = { song, list ->
                                    musicPlayerViewModel.playSong(song, list)
                                    navController.navigate("player")
                                }
                            )
                        }
                    }
                    composable("artist_detail") {
                        selectedArtistForDetail?.let { artist ->
                            ArtistDetailScreen(
                                artist = artist,
                                onBack = { navController.popBackStack() },
                                onSongClick = { song, list ->
                                    musicPlayerViewModel.playSong(song, list)
                                    navController.navigate("player")
                                },
                                onPlayAllClick = { list ->
                                    if (list.isNotEmpty()) {
                                        musicPlayerViewModel.playSong(list.first(), list)
                                        navController.navigate("player")
                                    }
                                }
                            )
                        }
                    }
                    composable("player") {
                        NowPlayingScreen(
                            viewModel = musicPlayerViewModel,
                            onBack = { navController.popBackStack() },
                            onNavigateToArtist = { artist ->
                                selectedArtistForDetail = artist
                                navController.navigate("artist_detail")
                            },
                            onNavigateToAlbum = { album ->
                                selectedAlbumForDetail = album
                                navController.navigate("album_detail")
                            },
                            onNavigateToAddToPlaylist = { song ->
                                songToAddToPlaylist = song
                                navController.navigate("add_to_playlist")
                            }
                        )
                    }
                    composable("add_to_playlist") {
                        songToAddToPlaylist?.let { song ->
                            AddToPlaylistScreen(
                                song = song,
                                repository = musicRepository,
                                onBack = { navController.popBackStack() },
                                onPlaylistSelected = { 
                                    navController.popBackStack() 
                                    songToAddToPlaylist = null
                                }
                            )
                        }
                    }
                }
            }

            if (currentRoute != "player" && currentRoute != "add_to_playlist") {
                currentSong?.let { song ->
                    Box(modifier = Modifier.fillMaxSize().padding(bottom = bottomPadding)) {
                        Box(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 8.dp, vertical = 4.dp)) {
                            MiniPlayer(
                                song = song,
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
}
