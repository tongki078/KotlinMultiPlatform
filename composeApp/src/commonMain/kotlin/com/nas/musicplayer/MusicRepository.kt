package com.nas.musicplayer

import com.nas.musicplayer.Song
import com.nas.musicplayer.db.PlaylistDao
import com.nas.musicplayer.db.PlaylistEntity
import com.nas.musicplayer.db.RecentSearch
import com.nas.musicplayer.db.RecentSearchDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class MusicRepository(
    private val playlistDao: PlaylistDao,
    private val recentSearchDao: RecentSearchDao
) {

    val allPlaylists: Flow<List<PlaylistEntity>> = playlistDao.getAllPlaylists()
    val recentSearches: Flow<List<RecentSearch>> = recentSearchDao.getRecentSearches()

    // [수정] 메모리 과부하 방지를 위해 전체 곡 리스트 로딩을 비활성화 (Flow 연산자 제거)
    val allSongs: Flow<List<Song>> = flowOf(emptyList())

    suspend fun createPlaylist(name: String): Long {
        val newPlaylist = PlaylistEntity(name = name, songs = emptyList())
        return playlistDao.insertPlaylist(newPlaylist)
    }

    suspend fun addSongToPlaylist(playlistId: Int, song: Song) {
        val playlist = playlistDao.getPlaylistById(playlistId) ?: return
        if (playlist.songs.none { it.id == song.id }) {
            val updatedSongs = playlist.songs.toMutableList().apply { add(song) }
            playlistDao.updatePlaylist(playlist.copy(songs = updatedSongs))
        }
    }

    suspend fun deletePlaylistById(playlistId: Int) {
        playlistDao.getPlaylistById(playlistId)?.let {
            playlistDao.deletePlaylist(it)
        }
    }

    suspend fun addRecentSearch(query: String, timestamp: Long) {
        if (query.isBlank()) return
        recentSearchDao.insertSearch(RecentSearch(query, timestamp))
    }

    suspend fun deleteRecentSearch(query: String) {
        recentSearchDao.deleteSearch(query)
    }

    suspend fun clearAllRecentSearches() {
        recentSearchDao.deleteAll()
    }
}
