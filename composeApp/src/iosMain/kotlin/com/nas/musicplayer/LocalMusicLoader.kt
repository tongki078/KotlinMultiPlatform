package com.nas.musicplayer

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.*
import com.nas.musicplayer.Song

object LocalMusicLoader {
    @OptIn(ExperimentalForeignApi::class)
    fun loadLocalMusic(): List<Song> {
        val songList = mutableListOf<Song>()
        val fileManager = NSFileManager.defaultManager
        
        // App의 Documents 디렉토리 경로 가져오기
        val documentsPath = NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory,
            NSUserDomainMask,
            true
        ).firstOrNull() as? String ?: return emptyList()

        try {
            val files = fileManager.contentsOfDirectoryAtPath(documentsPath, null)
            files?.filter { 
                val fileName = it as String
                fileName.endsWith(".mp3", true) || fileName.endsWith(".m4a", true) 
            }?.forEach { 
                val fileName = it as String
                val fullPath = "$documentsPath/$fileName"
                
                songList.add(
                    Song(
                        id = fileName.hashCode().toLong(),
                        name = fileName.removeSuffix(".mp3").removeSuffix(".m4a"),
                        artist = "Local Device",
                        albumName = "Documents",
                        streamUrl = fullPath,
                        isDir = false
                    )
                )
            }
        } catch (e: Exception) {
            println("iOS LocalMusicLoader Error: ${e.message}")
        }
        
        return songList
    }
}
