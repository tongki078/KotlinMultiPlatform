package com.nas.musicplayer

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.*
import platform.AVFoundation.*
import platform.CoreGraphics.*
import platform.UIKit.*
import com.nas.musicplayer.Song

object LocalMusicLoader {
    @OptIn(ExperimentalForeignApi::class)
    fun loadLocalMusic(): List<Song> {
        val songList = mutableListOf<Song>()
        val fileManager = NSFileManager.defaultManager
        
        val documentsPath = NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory,
            NSUserDomainMask,
            true
        ).firstOrNull() as? String ?: return emptyList()

        try {
            val files = fileManager.contentsOfDirectoryAtPath(documentsPath, null)
            
            files?.forEach { 
                val fileName = it as String
                val lowerName = fileName.lowercase()
                
                // .mp3, .m4a, .wav 파일만 처리 (이미지 파일 제외)
                if (lowerName.endsWith(".mp3") || lowerName.endsWith(".m4a") || lowerName.endsWith(".wav")) {
                    val fullPath = "$documentsPath/$fileName"
                    val url = NSURL.fileURLWithPath(fullPath)
                    
                    // 파일 이름에서 타이틀과 아티스트 분리 시도 ("Artist - Title.mp3")
                    val baseName = fileName.substringBeforeLast(".")
                    var displayTitle = baseName
                    var displayArtist = "보관함"

                    if (baseName.contains(" - ")) {
                        val parts = baseName.split(" - ", limit = 2)
                        displayArtist = parts[0].trim()
                        displayTitle = parts[1].trim()
                    }

                    // 이미지 파일 경로 확인 (노래 파일과 동일한 이름의 .jpg)
                    val artworkPath = "$documentsPath/$baseName.jpg"
                    val finalArtwork = if (fileManager.fileExistsAtPath(artworkPath)) {
                        artworkPath
                    } else {
                        null
                    }

                    songList.add(
                        Song(
                            id = fileName.hashCode().toLong(),
                            name = displayTitle,
                            artist = displayArtist,
                            albumName = "다운로드",
                            streamUrl = fullPath,
                            isDir = false,
                            metaPoster = finalArtwork
                        )
                    )
                }
            }
        } catch (e: Exception) {
            NSLog("iOS LocalMusicLoader Error: %s", e.message ?: "Unknown")
        }
        
        return songList
    }
}
