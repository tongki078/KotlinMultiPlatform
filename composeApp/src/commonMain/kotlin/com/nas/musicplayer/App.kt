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
    onVoiceQueryConsumed: () -> Unit = {},
    onRefreshLocalSongs: () -> Unit = {}
) {
    val searchViewModel: MusicSearchViewModel = viewModel(
        factory = MusicSearchViewModel.Factory(musicRepository)
    )
    
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val uiState by searchViewModel.uiState.collectAsState()

    LaunchedEffect(localSongs) {
        searchViewModel.setLocalSongs(localSongs)
    }
    
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentRoute = currentDestination?.route

    val currentSong by musicPlayerViewModel.currentSong.collectAsState()
    val isPlaying by musicPlayerViewModel.isPlaying.collectAsState()

    val onDeleteSong: (Song) -> Unit = { song ->
        val success = getFileDownloader().deleteFile(song.streamUrl ?: "")
        if (success) {
            onRefreshLocalSongs()
            scope.launch { snackbarHostState.showSnackbar("삭제되었습니다.") }
        }
    }

    val onDownloadSong: (Song) -> Unit = { song ->
        if (!searchViewModel.isSongDownloaded(song)) {
            searchViewModel.startDownloading(song.id)
            musicPlayerViewModel.downloadSong(song) { success, message ->
                searchViewModel.stopDownloading(song.id)
                if (success) onRefreshLocalSongs()
                scope.launch { snackbarHostState.showSnackbar(message ?: "완료") }
            }
        }
    }

    MaterialTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                val route = currentRoute ?: ""
                val isFullScreen = route == "player" || route.startsWith("add_to_playlist")
                
                if (!isFullScreen) {
                    Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
                        currentSong?.let { song ->
                            Box(modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 16.dp)) {
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
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(bottom = if (currentRoute == "player") 0.dp else innerPadding.calculateBottomPadding())) {
                NavHost(navController = navController, startDestination = "search") {
                    composable("search") {
                        MusicSearchScreen(
                            viewModel = searchViewModel,
                            onSongClick = { song -> musicPlayerViewModel.playSong(song, uiState.songs) },
                            onNavigateToPlaylists = { navController.navigate("playlists") },
                            onNavigateToArtist = { artist -> navController.navigate("artist_detail/${artist.name}") },
                            onNavigateToAlbum = { album -> navController.navigate("album_detail/${album.name}/${album.artist}") },
                            onNavigateToAddToPlaylist = { song -> navController.navigate("add_to_playlist/${song.id}") },
                            onNavigateToTheme = { theme -> 
                                searchViewModel.loadThemeDetails(theme)
                                navController.navigate("theme_detail/${theme.name}")
                            },
                            onNavigateToArtistGrid = { folderName -> navController.navigate("artist_grid/$folderName") },
                            onVoiceSearchClick = onVoiceSearchClick,
                            onDownloadSong = onDownloadSong,
                            onDeleteSong = onDeleteSong,
                            downloadingSongIds = uiState.downloadingSongIds,
                            isVoiceSearching = isVoiceSearching,
                            onNavigateToLibrary = { 
                                navController.navigate("library") {
                                    popUpTo(navController.graph.findStartDestination().route ?: "search") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                    composable("artist_grid/{folderType}") { backStackEntry ->
                        val folderType = backStackEntry.arguments?.getString("folderType") ?: "국내"
                        LaunchedEffect(folderType) {
                            searchViewModel.loadArtistsPaged(folderType, isRefresh = true)
                        }
                        ArtistGridScreen(
                            artists = searchViewModel.artistGrid,
                            onLoadMore = { searchViewModel.loadArtistsPaged(folderType) },
                            onArtistClick = { artistName -> navController.navigate("artist_detail/$artistName") },
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("theme_detail/{themeName}") { backStackEntry ->
                        val themeName = backStackEntry.arguments?.getString("themeName") ?: ""
                        ThemeDetailScreen(
                            themeName = themeName,
                            viewModel = searchViewModel,
                            onBack = { navController.popBackStack() },
                            onSongClick = { song, list -> musicPlayerViewModel.playSong(song, list) },
                            onShowSongOptions = { /* Sheet handle */ }
                        )
                    }
                    composable("library") {
                        LibraryScreen(
                            localSongs = localSongs,
                            onSongClick = { song, list -> musicPlayerViewModel.playSong(song, list) },
                            onNavigateToAddToPlaylist = { song -> navController.navigate("add_to_playlist/${song.id}") },
                            onNavigateToPlaylists = { navController.navigate("playlists") },
                            onNavigateToArtist = { artist -> navController.navigate("artist_detail/${artist.name}") },
                            onNavigateToAlbum = { album -> navController.navigate("album_detail/${album.name}/${album.artist}") },
                            onNavigateToDownloadedSongs = { navController.navigate("downloaded_songs") },
                            onDownloadSong = onDownloadSong,
                            onDeleteSong = onDeleteSong,
                            downloadingSongIds = uiState.downloadingSongIds,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("downloaded_songs") {
                        DownloadedSongsScreen(
                            localSongs = localSongs,
                            onBack = { navController.popBackStack() },
                            onSongClick = { song, list -> musicPlayerViewModel.playSong(song, list) },
                            onNavigateToArtist = { artist -> navController.navigate("artist_detail/${artist.name}") },
                            onNavigateToAlbum = { album -> navController.navigate("album_detail/${album.name}/${album.artist}") },
                            onNavigateToAddToPlaylist = { song -> navController.navigate("add_to_playlist/${song.id}") },
                            onDeleteSong = onDeleteSong
                        )
                    }
                    composable("playlists") {
                        PlaylistsListScreen(
                            repository = musicRepository,
                            onPlaylistClick = { id -> navController.navigate("playlist_detail/$id") },
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("playlist_detail/{playlistId}", arguments = listOf(navArgument("playlistId") { type = NavType.IntType })) { backStackEntry ->
                        val playlistId = backStackEntry.arguments?.getInt("playlistId") ?: -1
                        PlaylistViewScreen(
                            viewModel = remember(playlistId) { PlaylistViewModel(playlistId, musicRepository) },
                            playerViewModel = musicPlayerViewModel,
                            onSongClick = { song, list -> musicPlayerViewModel.playSong(song, list) },
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("album_detail/{albumName}/{artistName}") { backStackEntry ->
                        val albumName = backStackEntry.arguments?.getString("albumName") ?: ""
                        val artistName = backStackEntry.arguments?.getString("artistName") ?: ""
                        LaunchedEffect(albumName, artistName) { searchViewModel.loadAlbumDetails(albumName, artistName) }
                        if (uiState.isAlbumLoading && uiState.selectedAlbum == null) MusicLoadingScreen()
                        else uiState.selectedAlbum?.let {
                            AlbumDetailScreen(it, { navController.popBackStack() }, { s, l -> musicPlayerViewModel.playSong(s, l) }, onDownloadSong, uiState.downloadingSongIds)
                        }
                    }
                    composable("artist_detail/{artistName}") { backStackEntry ->
                        val artistName = backStackEntry.arguments?.getString("artistName") ?: ""
                        LaunchedEffect(artistName) { searchViewModel.loadArtistDetails(artistName) }
                        if (uiState.isArtistLoading && uiState.selectedArtist == null) MusicLoadingScreen()
                        else uiState.selectedArtist?.let { artist ->
                            ArtistDetailScreen(
                                artist = artist,
                                onBack = { navController.popBackStack() },
                                onAlbumClick = { album ->
                                    navController.navigate("album_detail/${album.name}/${artist.name}")
                                }
                            )
                        }
                    }
                    composable("player") {
                        NowPlayingScreen(musicPlayerViewModel, { navController.popBackStack() }, { a -> navController.navigate("artist_detail/${a.name}") }, { a -> navController.navigate("album_detail/${a.name}/${a.artist}") }, { s -> navController.navigate("add_to_playlist/${s.id}") }, onDownloadSong)
                    }
                    composable("add_to_playlist/{songId}", arguments = listOf(navArgument("songId") { type = NavType.LongType })) { backStackEntry ->
                        val songId = backStackEntry.arguments?.getLong("songId") ?: -1L
                        val song = (uiState.songs + localSongs).find { it.id == songId }
                        song?.let { AddToPlaylistScreen(it, musicRepository, { navController.popBackStack() }, { navController.popBackStack() }) }
                    }
                }
            }
        }
    }
}
