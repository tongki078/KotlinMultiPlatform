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
            files?.filter { 
                val fileName = it as String
                fileName.endsWith(".mp3", true) || fileName.endsWith(".m4a", true) 
            }?.forEach { 
                val fileName = it as String
                val fullPath = "$documentsPath/$fileName"
                val url = NSURL.fileURLWithPath(fullPath)
                
                val asset = AVAsset.assetWithURL(url)
                var artworkPath: String? = null
                
                // Extract artwork
                val artworkItem = asset.commonMetadata.filter { item ->
                    val metadataItem = item as AVMetadataItem
                    metadataItem.commonKey() == AVMetadataCommonKeyArtwork
                }.firstOrNull() as? AVMetadataItem

                if (artworkItem != null && cachePath != null) {
                    val data = artworkItem.value() as? NSData
                    if (data != null) {
                        val artFileName = "art_${fileName.hashCode()}.jpg"
                        val artFullPath = "$cachePath/$artFileName"
                        data.writeToFile(artFullPath, true)
                        artworkPath = artFullPath
                    }
                }

                songList.add(
                    Song(
                        id = fileName.hashCode().toLong(),
                        name = fileName.removeSuffix(".mp3").removeSuffix(".m4a"),
                        artist = "Local Device",
                        albumName = "Documents",
                        streamUrl = fullPath,
                        isDir = false,
                        metaPoster = artworkPath
                    )
                )
            }
        } catch (e: Exception) {
            println("iOS LocalMusicLoader Error: ${e.message}")
        }
        
        return songList
    }
}
