package com.nas.musicplayer

import androidx.lifecycle.viewModelScope
import com.nas.musicplayer.db.PlaylistEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class PlaylistUiState(
    val playlist: PlaylistEntity? = null
)

class PlaylistViewModel(
    private val playlistId: Int,
    private val repository: MusicRepository
) : BaseViewModel() {

    val uiState: StateFlow<PlaylistUiState> = repository.allPlaylists
        .map { playlists ->
            val playlist = playlists.find { it.id == playlistId }
            PlaylistUiState(playlist = playlist)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PlaylistUiState()
        )
}
