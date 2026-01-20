package com.nas.musicplayer.ui.music

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nas.musicplayer.Album
import com.nas.musicplayer.Artist
import com.nas.musicplayer.Song

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onSongClick: (Song, List<Song>) -> Unit,
    onNavigateToAddToPlaylist: (Song) -> Unit,
    onNavigateToPlaylists: () -> Unit,
    onNavigateToArtist: (Artist) -> Unit,
    onNavigateToAlbum: (Album) -> Unit
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("보관함") }) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            Text("보관함 화면", modifier = Modifier.padding(16.dp))
            // TODO: Implement the actual library list or grid here
        }
    }
}
