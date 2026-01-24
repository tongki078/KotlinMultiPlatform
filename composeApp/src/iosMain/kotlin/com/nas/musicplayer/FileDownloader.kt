package com.nas.musicplayer

import platform.Foundation.*
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
class IosFileDownloader : FileDownloader {
    override fun downloadFile(url: String, imageUrl: String?, fileName: String, onResult: (Boolean, String?) -> Unit) {
        val safeFileName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        
        imageUrl?.let { imgUrl ->
            val baseName = if (safeFileName.contains(".")) safeFileName.substringBeforeLast(".") else safeFileName
            downloadImage(imgUrl, "$baseName.jpg")
        }
        
        downloadInternal(url, safeFileName, onResult)
    }

    override fun deleteFile(filePath: String): Boolean {
        val fileManager = NSFileManager.defaultManager
        val url = NSURL.fileURLWithPath(filePath)
        
        // 노래 파일 삭제
        val musicDeleted = if (fileManager.fileExistsAtPath(filePath)) {
            fileManager.removeItemAtURL(url, null)
        } else {
            false
        }

        // 연관된 이미지 파일 삭제 시도
        val baseName = filePath.substringBeforeLast(".")
        val imagePath = "$baseName.jpg"
        if (fileManager.fileExistsAtPath(imagePath)) {
            fileManager.removeItemAtURL(NSURL.fileURLWithPath(imagePath), null)
        }

        return musicDeleted
    }

    private fun downloadInternal(url: String, fileName: String, onResult: (Boolean, String?) -> Unit) {
        val nsUrl = NSURL.URLWithString(url) ?: run {
            onResult(false, "유효하지 않은 URL입니다.")
            return
        }

        val task = NSURLSession.sharedSession.downloadTaskWithURL(nsUrl) { location, _, error ->
            if (error != null) {
                dispatch_async(dispatch_get_main_queue()) {
                    onResult(false, error.localizedDescription)
                }
                return@downloadTaskWithURL
            }

            if (location != null) {
                val fileManager = NSFileManager.defaultManager
                val documentsPath = NSSearchPathForDirectoriesInDomains(
                    NSDocumentDirectory,
                    NSUserDomainMask,
                    true
                ).first() as String
                
                val destinationPath = "$documentsPath/$fileName"
                val destinationUrl = NSURL.fileURLWithPath(destinationPath)

                if (fileManager.fileExistsAtPath(destinationPath)) {
                    fileManager.removeItemAtURL(destinationUrl, null)
                }

                val success = fileManager.moveItemAtURL(location, destinationUrl, null)
                
                dispatch_async(dispatch_get_main_queue()) {
                    if (success) {
                        onResult(true, "보관함에 추가되었습니다.")
                    } else {
                        onResult(false, "파일 저장 실패 (이동 중 오류)")
                    }
                }
            } else {
                dispatch_async(dispatch_get_main_queue()) {
                    onResult(false, "다운로드된 파일을 찾을 수 없습니다.")
                }
            }
        }
        task.resume()
    }

    private fun downloadImage(imageUrl: String, imageFileName: String) {
        val nsUrl = NSURL.URLWithString(imageUrl) ?: return
        val task = NSURLSession.sharedSession.downloadTaskWithURL(nsUrl) { location, _, _ ->
            if (location != null) {
                val fileManager = NSFileManager.defaultManager
                val documentsPath = NSSearchPathForDirectoriesInDomains(
                    NSDocumentDirectory,
                    NSUserDomainMask,
                    true
                ).first() as String
                
                val destinationPath = "$documentsPath/$imageFileName"
                val destinationUrl = NSURL.fileURLWithPath(destinationPath)

                if (fileManager.fileExistsAtPath(destinationPath)) {
                    fileManager.removeItemAtURL(destinationUrl, null)
                }
                fileManager.moveItemAtURL(location, destinationUrl, null)
            }
        }
        task.resume()
    }
}

actual fun getFileDownloader(): FileDownloader = IosFileDownloader()
