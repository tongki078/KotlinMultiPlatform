package com.nas.musicplayer.network

import io.ktor.client.call.body
import io.ktor.client.request.get

class MusicApiServiceImpl(private val client: io.ktor.client.HttpClient) : MusicApiService {
    private val BASE_URL = "https://music.yommi.mywire.org/gds_dviewer/normal/explorer/"
    private val API_KEY = "gommikey"

    override suspend fun getTop100(): SearchResponse {
        return client.get("${BASE_URL}top100?apikey=$API_KEY").body()
    }

    override suspend fun search(searchQuery: String): SearchResponse {
        return client.get("${BASE_URL}search?query=$searchQuery&apikey=$API_KEY").body()
    }
}
