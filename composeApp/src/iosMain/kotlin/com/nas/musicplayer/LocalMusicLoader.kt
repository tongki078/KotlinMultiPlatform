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

        val cachePath = NSSearchPathForDirectoriesInDomains(
            NSCachesDirectory,
            NSUserDomainMask,
            true
        ).firstOrNull() as? String

        try {
            val files = fileManager.contentsOfDirectoryAtPath(documentsPath, null)
            
            files?.forEach { 
                val fileName = it as String
                val lowerName = fileName.lowercase()
                
                if (lowerName.endsWith(".mp3") || lowerName.endsWith(".m4a") || lowerName.endsWith(".wav")) {
                    val fullPath = "$documentsPath/$fileName"
                    val url = NSURL.fileURLWithPath(fullPath)
                    val asset = AVAsset.assetWithURL(url)
                    
                    var title: String? = null
                    var artist: String? = null
                    var artworkPath: String? = null
                    
                    // 1. 파일 메타데이터에서 정보 추출
                    asset.commonMetadata.forEach { item ->
                        val metadataItem = item as AVMetadataItem
                        when (metadataItem.commonKey()) {
                            AVMetadataCommonKeyTitle -> title = metadataItem.value() as? String
                            AVMetadataCommonKeyArtist -> artist = metadataItem.value() as? String
                            AVMetadataCommonKeyArtwork -> {
                                if (cachePath != null) {
                                    val data = metadataItem.value() as? NSData
                                    if (data != null) {
                                        val artFileName = "art_${fileName.hashCode()}.jpg"
                                        val artFullPath = "$cachePath/$artFileName"
                                        data.writeToFile(artFullPath, true)
                                        artworkPath = artFullPath
                                    }
                                }
                            }
                        }
                    }

                    // 2. 파일 메타데이터에 이미지가 없는 경우, 다운로드 시 저장된 별도 이미지 파일 확인
                    if (artworkPath == null && cachePath != null) {
                        val baseName = fileName.substringBeforeLast(".")
                        val localArtPath = "$cachePath/$baseName.jpg"
                        if (fileManager.fileExistsAtPath(localArtPath)) {
                            artworkPath = localArtPath
                        }
                    }

                    songList.add(
                        Song(
                            id = fileName.hashCode().toLong(),
                            name = title ?: fileName.substringBeforeLast("."),
                            artist = artist ?: "Local Device",
                            albumName = "Local Album",
                            streamUrl = fullPath,
                            isDir = false,
                            metaPoster = artworkPath
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
