package com.nas.musicplayer.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nas.musicplayer.Song
import com.nas.musicplayer.network.BrowseItem
import com.nas.musicplayer.network.MusicApiService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderBrowseScreen(
    apiService: MusicApiService,
    initialPath: String,
    title: String,
    onSongClick: (Song, List<Song>) -> Unit,
    onBack: () -> Unit
) {
    val appleRed = Color(0xFFFA2D48)
    var currentPath by remember { mutableStateOf(initialPath) }
    var items by remember { mutableStateOf<List<BrowseItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    
    val pathHistory = remember { mutableStateListOf<Pair<String, String>>() } // Path, Title

    LaunchedEffect(currentPath) {
        isLoading = true
        try {
            items = apiService.browseLibrary(currentPath)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(if (pathHistory.isEmpty()) title else pathHistory.last().second, fontWeight = FontWeight.Bold)
                        if (currentPath != initialPath) {
                            Text(currentPath, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (pathHistory.isNotEmpty()) {
                            val last = pathHistory.removeAt(pathHistory.size - 1)
                            currentPath = if (pathHistory.isEmpty()) initialPath else pathHistory.last().first
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = appleRed)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = appleRed)
            }
        } else {
            val folders = items.filter { it.is_dir }
            val songs = items.filter { !it.is_dir }

            if (folders.isNotEmpty()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .background(MaterialTheme.colorScheme.background),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    items(folders) { folder ->
                        FolderGridItem(folder = folder, onClick = {
                            pathHistory.add(currentPath to (if (pathHistory.isEmpty()) title else pathHistory.last().second))
                            currentPath = folder.path
                        })
                    }
                }
            } else if (songs.isNotEmpty()) {
                val songList = songs.map { 
                    Song(
                        id = 0, // 서버에서 ID를 주지 않으므로 임시값
                        name = it.name,
                        artist = it.artist ?: "Unknown",
                        albumName = it.albumName ?: "Unknown",
                        streamUrl = it.stream_url ?: "",
                        parentPath = it.path,
                        metaPoster = it.meta_poster
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    items(songList) { song ->
                        SongListItem(
                            song = song,
                            onItemClick = { onSongClick(song, songList) },
                            onMoreClick = { /* 필요 시 구현 */ }
                        )
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("폴더가 비어 있습니다.", color = Color.Gray)
                }
            }
        }
    }
}

@Composable
fun FolderGridItem(folder: BrowseItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp)),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp)
        ) {
            if (folder.cover != null) {
                AsyncImage(
                    model = folder.cover,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color.Gray)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = folder.name,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
