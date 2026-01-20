package com.nas.musicplayer

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform