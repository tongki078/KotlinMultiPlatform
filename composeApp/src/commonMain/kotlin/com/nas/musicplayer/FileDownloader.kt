package com.nas.musicplayer

interface FileDownloader {
    fun downloadFile(url: String, imageUrl: String?, fileName: String, onResult: (Boolean, String?) -> Unit)
}

expect fun getFileDownloader(): FileDownloader
