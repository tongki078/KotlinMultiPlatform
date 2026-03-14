package com.nas.musicplayer.network

import com.nas.musicplayer.Song
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode

class MusicApiServiceImpl(private val client: io.ktor.client.HttpClient) : MusicApiService {
    private val BASE_URL = "http://192.168.0.2:4444/" 
    private val API_KEY = "gommikey"
    private val LYRICS_GET_URL = "https://lrclib.net/api/get"
    private val LYRICS_SEARCH_URL = "https://lrclib.net/api/search"

    override suspend fun getArtists(folderType: String): List<Artist> {
        return client.get("${BASE_URL}api/library/artists/$folderType").body()
    }

    override suspend fun getTop100(): List<Song> {
        return client.get("${BASE_URL}api/top100?apikey=$API_KEY").body()
    }

    override suspend fun search(searchQuery: String): List<Song> {
        return client.get("${BASE_URL}api/search?q=$searchQuery&apikey=$API_KEY").body()
    }

    override suspend fun getLyrics(artist: String, title: String): LrcResponse? {
        val cleanTitle = cleanSearchTerm(title)
        val cleanArtist = cleanSearchTerm(artist)
        
        println("Lyrics Request (Step 1 - Get): artist=$cleanArtist, title=$cleanTitle")

        return try {
            val response = client.get(LYRICS_GET_URL) {
                parameter("artist_name", cleanArtist)
                parameter("track_name", cleanTitle)
            }

            if (response.status == HttpStatusCode.OK) {
                response.body<LrcResponse>()
            } else {
                searchLyricsFallback(cleanArtist, cleanTitle)
            }
        } catch (e: Exception) {
            searchLyricsFallback(cleanArtist, cleanTitle)
        }
    }

    private suspend fun searchLyricsFallback(artist: String, title: String): LrcResponse? {
        println("Lyrics Request (Step 2 - Search Fallback): q=$artist $title")
        return try {
            val searchResults = client.get(LYRICS_SEARCH_URL) {
                parameter("q", "$artist $title")
            }.body<List<LrcResponse>>()

            searchResults.firstOrNull { it.syncedLyrics != null } 
                ?: searchResults.firstOrNull()
        } catch (e: Exception) {
            println("Lyrics final search failed: ${e.message}")
            null
        }
    }

    private fun cleanSearchTerm(term: String): String {
        return term.replace(Regex("\\(.*?\\)"), "") 
            .replace(Regex("\\[.*?\\]"), "") 
            .replace(Regex(" - Topic$"), "")
            .trim()
    }
}
