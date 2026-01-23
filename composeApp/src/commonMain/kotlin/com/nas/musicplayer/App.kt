package com.nas.musicplayer

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nas.musicplayer.ui.music.*

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
    
    // 음성 검색 결과 처리 (실시간 반영 및 최종 검색)
    LaunchedEffect(voiceQuery, isVoiceFinal, isVoiceSearching) {
        if (isVoiceFinal) {
            if (voiceQuery.isNotEmpty()) {
                searchViewModel.performSearch(voiceQuery)
            }
            onVoiceQueryConsumed() 
        } else if (isVoiceSearching && voiceQuery.isNotEmpty()) {
            // 사용자가 말하는 도중에 실시간으로 검색창에 텍스트 표시
            searchViewModel.onSearchQueryChanged(voiceQuery)
        }
    }
    
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentRoute = currentDestination?.route

    // 앱이 백그라운드에서 돌아올 때 검색 탭 우선 이동
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (currentRoute != "search" && currentRoute != null) {
            navController.navigate("search") {
                val startDest = navController.graph.findStartDestination()
                popUpTo(startDest.route ?: "search") { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    val currentSong by musicPlayerViewModel.currentSong.collectAsState()
    val isPlaying by musicPlayerViewModel.isPlaying.collectAsState()

    MaterialTheme {
        Scaffold(
            bottomBar = {
                val route = currentRoute ?: ""
                val isFullScreen = route == "player" || route.startsWith("add_to_playlist")
                
                if (!isFullScreen) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        currentSong?.let { song ->
                            Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                MiniPlayer(
                                    song = song,
                                    isPlaying = isPlaying,
                                    onTogglePlay = { musicPlayerViewModel.togglePlayPause() },
                                    onNextClick = { musicPlayerViewModel.playNext() },
                                    onClick = { navController.navigate("player") }
                                )
                            }
                        }
                        
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 0.dp,
                            windowInsets = WindowInsets(0, 0, 0, 0)
                        ) {
                            NavigationBarItem(
                                selected = currentDestination?.hierarchy?.any { it.route == "search" } == true,
                                onClick = { 
                                    navController.navigate("search") {
                                        val startDest = navController.graph.findStartDestination()
                                        popUpTo(startDest.route ?: "search") { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(26.dp)) },
                                label = { Text("검색", fontSize = 12.sp) },
                                alwaysShowLabel = true
                            )
                            
                            val isLibrarySelected = currentDestination?.hierarchy?.any { it.route in listOf("library", "playlists") } == true ||
                                                   route.startsWith("playlist_detail") || 
                                                   route.startsWith("album_detail") || 
                                                   route.startsWith("artist_detail")

                            NavigationBarItem(
                                selected = isLibrarySelected,
                                onClick = { 
                                    navController.navigate("library") {
                                        val startDest = navController.graph.findStartDestination()
                                        popUpTo(startDest.route ?: "search") { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(Icons.Default.LibraryMusic, null, modifier = Modifier.size(26.dp)) },
                                label = { Text("보관함", fontSize = 12.sp) },
                                alwaysShowLabel = true
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(bottom = innerPadding.calculateBottomPadding())) {
                NavHost(
                    navController = navController, 
                    startDestination = "search",
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable("search") {
                        MusicSearchScreen(
                            viewModel = searchViewModel,
                            onSongClick = { song -> musicPlayerViewModel.playSong(song, searchViewModel.uiState.value.songs) },
                            onNavigateToPlaylists = { navController.navigate("playlists") },
                            onNavigateToArtist = { artist -> navController.navigate("artist_detail/${artist.name}") },
                            onNavigateToAlbum = { album -> navController.navigate("album_detail/${album.name}/${album.artist}") },
                            onNavigateToAddToPlaylist = { song -> navController.navigate("add_to_playlist/${song.id}") },
                            onVoiceSearchClick = onVoiceSearchClick,
                            isVoiceSearching = isVoiceSearching
                        )
                    }
                    composable("library") {
                        LibraryScreen(
                            localSongs = localSongs,
                            onSongClick = { song, list -> musicPlayerViewModel.playSong(song, list) },
                            onNavigateToAddToPlaylist = { song -> navController.navigate("add_to_playlist/${song.id}") },
                            onNavigateToPlaylists = { navController.navigate("playlists") },
                            onNavigateToArtist = { artist -> navController.navigate("artist_detail/${artist.name}") },
                            onNavigateToAlbum = { album -> navController.navigate("album_detail/${album.name}/${album.artist}") }
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
                            onSongClick = { song, list -> musicPlayerViewModel.playSong(song, list) },
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(
                        "album_detail/{albumName}/{artistName}",
                        arguments = listOf(
                            navArgument("albumName") { type = NavType.StringType },
                            navArgument("artistName") { type = NavType.StringType }
                        )
                    ) { backStackEntry ->
                        val albumName = backStackEntry.arguments?.getString("albumName") ?: ""
                        val artistName = backStackEntry.arguments?.getString("artistName") ?: ""
                        val albumSongs = (searchViewModel.uiState.collectAsState().value.songs + localSongs)
                            .filter { it.albumName == albumName && it.artist == artistName }
                            .distinctBy { it.id }

                        AlbumDetailScreen(
                            album = Album(name = albumName, artist = artistName, songs = albumSongs, imageUrl = albumSongs.firstOrNull()?.metaPoster),
                            onBack = { navController.popBackStack() },
                            onSongClick = { song, list -> musicPlayerViewModel.playSong(song, list) }
                        )
                    }
                    composable(
                        "artist_detail/{artistName}",
                        arguments = listOf(navArgument("artistName") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val artistName = backStackEntry.arguments?.getString("artistName") ?: ""
                        LaunchedEffect(artistName) { searchViewModel.loadArtistDetails(artistName) }
                        val uiState by searchViewModel.uiState.collectAsState()
                        uiState.selectedArtist?.let { artist ->
                            ArtistDetailScreen(
                                artist = artist,
                                onBack = { navController.popBackStack() },
                                onSongClick = { song, list -> musicPlayerViewModel.playSong(song, list) },
                                onPlayAllClick = { list -> if (list.isNotEmpty()) musicPlayerViewModel.playSong(list.first(), list) }
                            )
                        }
                    }
                    composable("player") {
                        NowPlayingScreen(
                            viewModel = musicPlayerViewModel,
                            onBack = { navController.popBackStack() },
                            onNavigateToArtist = { artist -> navController.navigate("artist_detail/${artist.name}") },
                            onNavigateToAlbum = { album -> navController.navigate("album_detail/${album.name}/${album.artist}") },
                            onNavigateToAddToPlaylist = { song -> navController.navigate("add_to_playlist/${song.id}") }
                        )
                    }
                    composable(
                        "add_to_playlist/{songId}",
                        arguments = listOf(navArgument("songId") { type = NavType.LongType })
                    ) { backStackEntry ->
                        val songId = backStackEntry.arguments?.getLong("songId") ?: -1L
                        val song = (searchViewModel.uiState.collectAsState().value.songs + localSongs).find { it.id == songId }
                        song?.let {
                            AddToPlaylistScreen(song = it, repository = musicRepository, onBack = { navController.popBackStack() }, onPlaylistSelected = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}
