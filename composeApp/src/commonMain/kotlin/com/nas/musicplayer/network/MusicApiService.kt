package com.nas.musicplayer.network

interface MusicApiService {
    suspend fun getTop100(): SearchResponse
    suspend fun search(searchQuery: String): SearchResponse
    suspend fun getLyrics(artist: String, title: String): LrcResponse? // 가사 검색 API 추가
}
