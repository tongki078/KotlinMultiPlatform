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
        
        val musicDeleted = if (fileManager.fileExistsAtPath(filePath)) {
            fileManager.removeItemAtURL(url, null)
        } else {
            false
        }

        val baseName = filePath.substringBeforeLast(".")
        val imagePath = "$baseName.jpg"
        if (fileManager.fileExistsAtPath(imagePath)) {
            fileManager.removeItemAtURL(NSURL.fileURLWithPath(imagePath), null)
        }

        return musicDeleted
    }

    private fun downloadInternal(url: String, fileName: String, onResult: (Boolean, String?) -> Unit) {
        // [중요] 원격 URL에 괄호나 공백이 있을 경우를 대비해 인코딩 처리
        val decoded = (url as NSString).stringByRemovingPercentEncoding() ?: url
        val allowedChars = NSCharacterSet.URLQueryAllowedCharacterSet().mutableCopy() as NSMutableCharacterSet
        allowedChars.addCharactersInString(":#") 
        val encodedUrl = (decoded as NSString).stringByAddingPercentEncodingWithAllowedCharacters(allowedChars) ?: decoded

        val nsUrl = NSURL.URLWithString(encodedUrl) ?: run {
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
        // 이미지 URL도 동일하게 인코딩 처리
        val decoded = (imageUrl as NSString).stringByRemovingPercentEncoding() ?: imageUrl
        val encodedUrl = (decoded as NSString).stringByAddingPercentEncodingWithAllowedCharacters(NSCharacterSet.URLQueryAllowedCharacterSet()) ?: decoded
        
        val nsUrl = NSURL.URLWithString(encodedUrl) ?: return
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
