package com.nas.musicplayer.ui.music

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.nas.musicplayer.*
import com.nas.musicplayer.db.RecentSearch
import com.nas.musicplayer.network.*
import com.nas.musicplayer.network.Artist as NetworkArtist
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.takeFrom
import io.ktor.http.appendPathSegments
import io.ktor.http.encodeURLPath
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Theme(
    val name: String, 
    val path: String,
    @SerialName("image_url") val imageUrl: String? = null
)

@Serializable
data class IntegratedSearchResponse(
    val artists: List<NetworkArtist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val songs: List<Song> = emptyList()
)

data class MusicSearchUiState(
    val searchResults: List<Song> = emptyList(),
    val searchArtists: List<NetworkArtist> = emptyList(),
    val searchAlbums: List<Album> = emptyList(),
    val top100Songs: List<Song> = emptyList(),
    val themes: List<Theme> = emptyList(),
    val collectionThemes: List<Theme> = emptyList(),
    val artistThemes: List<Theme> = emptyList(),
    val genreThemes: List<Theme> = emptyList(),
    val isLoading: Boolean = false,
    val searchQuery: String = "",
    val selectedArtist: com.nas.musicplayer.Artist? = null,
    val isArtistLoading: Boolean = false,
    val selectedAlbum: Album? = null,
    val isAlbumLoading: Boolean = false,
    val recentSearches: List<RecentSearch> = emptyList(),
    val albums: List<Album> = emptyList(),
    val downloadingSongIds: Set<Long> = emptySet(),
    val downloadedSongKeys: Set<String> = emptySet()
)

class MusicSearchViewModel(val repository: MusicRepository) : ViewModel() {

    private val musicApiService = MusicApiServiceImpl(httpClient)
    private var allLocalSongs: List<Song> = emptyList()
    private val pythonBaseUrl = "http://192.168.0.2:4444"

    private val _uiState = MutableStateFlow(MusicSearchUiState())
    val uiState: StateFlow<MusicSearchUiState> = _uiState.asStateFlow()

    val artistGrid = mutableStateListOf<com.nas.musicplayer.network.Artist>()
    private var artistPage = 1
    private var isLastPage = false
    private var isPagingLoading = false
    private var isTop100Loading = false

    init {
        loadMainData()
        
        viewModelScope.launch {
            repository.recentSearches.collect { searches ->
                _uiState.update { it.copy(recentSearches = searches) }
            }
        }
    }

    companion object {
        fun Factory(musicRepository: MusicRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { MusicSearchViewModel(musicRepository) }
        }
    }

    fun getApiService(): MusicApiService = musicApiService

    // [수정] 메인 화면: 모든 테마 섹션(차트, 모음, 가수, 장르) 로딩 복구
    fun loadMainData() {
        viewModelScope.launch {
            if (_uiState.value.themes.isNotEmpty()) return@launch
            
            _uiState.update { it.copy(isLoading = true) }
            try {
                // 병렬로 카테고리별 테마 로드
                val chartsDef = async { httpClient.get("$pythonBaseUrl/api/themes/charts?page=1").body<List<Theme>>() }
                val collectionsDef = async { httpClient.get("$pythonBaseUrl/api/themes/collections?page=1").body<List<Theme>>() }
                val artistsDef = async { httpClient.get("$pythonBaseUrl/api/themes/artists?page=1").body<List<Theme>>() }
                val genresDef = async { httpClient.get("$pythonBaseUrl/api/themes/genres?page=1").body<List<Theme>>() }
                
                _uiState.update { it.copy(
                    themes = chartsDef.await(),
                    collectionThemes = collectionsDef.await(),
                    artistThemes = artistsDef.await(),
                    genreThemes = genresDef.await(),
                    isLoading = false
                ) }

                // Top 100 로드
                loadTop100()
                
            } catch (e: Exception) {
                println("loadMainData Error: ${e.message}")
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }
    
    fun loadTop100() {
        if (isTop100Loading) return
        isTop100Loading = true
        viewModelScope.launch {
            try {
                val songs = httpClient.get("$pythonBaseUrl/api/top100").body<List<Song>>()
                _uiState.update { it.copy(top100Songs = songs.map { s -> cleanSongInfo(s) }) }
            } catch (e: Exception) {
                println("Error loading Top 100: ${e.message}")
            } finally {
                isTop100Loading = false
            }
        }
    }

    fun loadArtistsPaged(folderType: String, isRefresh: Boolean = false) {
        if (isPagingLoading) return
        if (isRefresh) { artistPage = 1; artistGrid.clear(); isLastPage = false }
        if (isLastPage) return
        isPagingLoading = true
        viewModelScope.launch {
            try {
                val response = httpClient.get("$pythonBaseUrl/api/library/artists_paged/$folderType") {
                    parameter("page", artistPage)
                }.body<List<com.nas.musicplayer.network.Artist>>()
                if (response.isEmpty()) isLastPage = true else { artistGrid.addAll(response); artistPage++ }
            } catch (e: Exception) { println("Error: ${e.message}") } finally { isPagingLoading = false }
        }
    }

    fun loadArtistDetails(artistName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isArtistLoading = true, selectedArtist = null) }
            try {
                val albums = httpClient.get("$pythonBaseUrl/api/library/albums_by_artist/${artistName.encodeURLPath()}").body<List<Album>>()
                val artistInfo = com.nas.musicplayer.Artist(
                    name = artistName,
                    imageUrl = albums.firstOrNull { !it.imageUrl.isNullOrBlank() }?.imageUrl,
                    albums = albums,
                    popularSongs = emptyList()
                )
                _uiState.update { it.copy(selectedArtist = artistInfo, isArtistLoading = false) }
            } catch (e: Exception) { _uiState.update { it.copy(isArtistLoading = false) } }
        }
    }

    fun loadAlbumDetails(albumName: String, artistName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAlbumLoading = true, selectedAlbum = null) }
            try {
                val songs = httpClient.get("$pythonBaseUrl/api/library/songs_by_album/${artistName.encodeURLPath()}/${albumName.encodeURLPath()}").body<List<Song>>()
                val cleanedSongs = songs.map { cleanSongInfo(it) }
                val albumInfo = Album(
                    name = albumName,
                    artist = artistName,
                    songs = cleanedSongs,
                    imageUrl = cleanedSongs.firstOrNull { !it.metaPoster.isNullOrBlank() && it.metaPoster != "FAIL" }?.metaPoster
                )
                _uiState.update { it.copy(selectedAlbum = albumInfo, isAlbumLoading = false) }
            } catch (e: Exception) { _uiState.update { it.copy(isAlbumLoading = false) } }
        }
    }

    fun performSearch(query: String = _uiState.value.searchQuery) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) return
        _uiState.update { it.copy(searchQuery = trimmedQuery, isLoading = true, searchResults = emptyList(), searchArtists = emptyList(), searchAlbums = emptyList()) }
        viewModelScope.launch {
            repository.addRecentSearch(trimmedQuery, Clock.System.now().toEpochMilliseconds())
            try {
                val response = httpClient.get("$pythonBaseUrl/api/library/search_integrated") { 
                    parameter("q", trimmedQuery) 
                }.body<IntegratedSearchResponse>()
                
                val finalResult = response.songs.map { cleanSongInfo(it) }.distinctBy { it.id }
                
                _uiState.update { it.copy(
                    searchResults = finalResult,
                    searchArtists = response.artists,
                    searchAlbums = response.albums,
                    isLoading = false
                ) }
            } catch (e: Exception) { 
                _uiState.update { it.copy(isLoading = false) } 
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        if (query.isEmpty()) {
            _uiState.update { it.copy(searchResults = emptyList(), searchArtists = emptyList(), searchAlbums = emptyList()) }
        }
    }

    private fun cleanSongInfo(song: Song): Song {
        val fileName = song.name ?: ""
        var cleanName = fileName.replace(Regex("""\.(mp3|flac|m4a|wav|dsf)$""", RegexOption.IGNORE_CASE), "").trim()
        cleanName = cleanName.replace(Regex("""^\d+[\s\.\-_]+"""), "").trim()
        
        val cleaned = if (cleanName.contains(" - ")) {
            val parts = cleanName.split(" - ", limit = 2)
            song.copy(name = parts[1].trim(), artist = parts[0].trim(), isDir = false)
        } else {
            song.copy(name = cleanName, isDir = false)
        }

        return if (cleaned.id == 0L) {
            val uniqueKey = "${cleaned.artist}-${cleaned.name}-${cleaned.streamUrl}"
            cleaned.copy(id = uniqueKey.hashCode().toLong())
        } else {
            cleaned
        }
    }

    fun setLocalSongs(songs: List<Song>) { allLocalSongs = songs; updateDownloadedKeys() }
    private fun updateDownloadedKeys() {
        val keys = allLocalSongs.map { generateMatchKey(it.artist, it.name ?: "") }.toSet()
        _uiState.update { it.copy(downloadedSongKeys = keys) }
    }
    fun isSongDownloaded(song: Song): Boolean = _uiState.value.downloadedSongKeys.contains(generateMatchKey(song.artist, song.name ?: ""))
    private fun generateMatchKey(artist: String, title: String): String = "${artist.replace(Regex("[^a-zA-Z0-9가-힣]"), "").lowercase()}-${title.replace(Regex("[^a-zA-Z0-9가-힣]"), "").lowercase()}"
    fun startDownloading(id: Long) { _uiState.update { it.copy(downloadingSongIds = it.downloadingSongIds + id) } }
    fun stopDownloading(id: Long) { _uiState.update { it.copy(downloadingSongIds = it.downloadingSongIds - id) } }

    fun deleteRecentSearch(q: String) { viewModelScope.launch { repository.deleteRecentSearch(q) } }
    fun clearAllRecentSearches() { viewModelScope.launch { repository.clearAllRecentSearches() } }

    fun loadThemeDetails(t: Theme) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, searchResults = emptyList()) }
            try {
                val songs = httpClient.get { url { takeFrom(pythonBaseUrl); appendPathSegments("api", "theme-details"); appendPathSegments(t.path.split("/")) } }.body<List<Song>>()
                _uiState.update { it.copy(searchResults = songs.map { cleanSongInfo(it) }, isLoading = false) }
            } catch (e: Exception) { _uiState.update { it.copy(isLoading = false) } }
        }
    }
}
