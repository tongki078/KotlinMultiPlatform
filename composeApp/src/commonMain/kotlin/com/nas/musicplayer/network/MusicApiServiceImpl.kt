package com.nas.musicplayer.network

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode

class MusicApiServiceImpl(private val client: io.ktor.client.HttpClient) : MusicApiService {
    private val BASE_URL = "https://music.yommi.mywire.org/gds_dviewer/normal/explorer/"
    private val API_KEY = "gommikey"
    private val LYRICS_GET_URL = "https://lrclib.net/api/get"
    private val LYRICS_SEARCH_URL = "https://lrclib.net/api/search"

    override suspend fun getTop100(): SearchResponse {
        return client.get("${BASE_URL}top100?apikey=$API_KEY").body()
    }

    override suspend fun search(searchQuery: String): SearchResponse {
        return client.get("${BASE_URL}search?query=$searchQuery&apikey=$API_KEY").body()
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
                // 1단계 실패 시 2단계 Search API로 재시도
                searchLyricsFallback(cleanArtist, cleanTitle)
            }
        } catch (e: Exception) {
            searchLyricsFallback(cleanArtist, cleanTitle)
        }
    }

    /**
     * Get API가 실패했을 때 Search API를 사용하여 가장 유사한 결과의 가사를 가져옵니다.
     */
    private suspend fun searchLyricsFallback(artist: String, title: String): LrcResponse? {
        println("Lyrics Request (Step 2 - Search Fallback): q=$artist $title")
        return try {
            val searchResults = client.get(LYRICS_SEARCH_URL) {
                parameter("q", "$artist $title")
            }.body<List<LrcResponse>>()

            // 검색 결과 중 동기화 가사(syncedLyrics)가 있는 첫 번째 항목을 우선 선택
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
