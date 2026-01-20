package com.nas.musicplayer.network

interface MusicApiService {
    suspend fun getTop100(): SearchResponse
    suspend fun search(searchQuery: String): SearchResponse
}
