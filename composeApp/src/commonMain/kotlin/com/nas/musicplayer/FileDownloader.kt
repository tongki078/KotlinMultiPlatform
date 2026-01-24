package com.nas.musicplayer

interface FileDownloader {
    fun downloadFile(url: String, imageUrl: String?, fileName: String, onResult: (Boolean, String?) -> Unit)
    fun deleteFile(filePath: String): Boolean
}

expect fun getFileDownloader(): FileDownloader
