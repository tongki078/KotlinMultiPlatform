package com.nas.musicplayer

import platform.Foundation.*
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
class IosFileDownloader : FileDownloader {
    override fun downloadFile(url: String, imageUrl: String?, fileName: String, onResult: (Boolean, String?) -> Unit) {
        // 이미지 다운로드가 필요한 경우 처리 (옵션)
        imageUrl?.let { imgUrl ->
            downloadImage(imgUrl, "img_$fileName.jpg")
        }
        
        downloadInternal(url, fileName, onResult)
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
                
                // 파일 이름에 포함될 수 있는 특수문자 제거 (보수적 처리)
                val safeFileName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val destinationPath = "$documentsPath/$safeFileName"
                val destinationUrl = NSURL.fileURLWithPath(destinationPath)

                // 임시 파일(location)은 이 핸들러가 종료되면 즉시 삭제되므로,
                // dispatch_async 외부에서 즉시 파일 이동을 완료해야 합니다.
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
                val cachePath = NSSearchPathForDirectoriesInDomains(
                    NSCachesDirectory,
                    NSUserDomainMask,
                    true
                ).first() as String
                
                val safeImageName = imageFileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val destinationPath = "$cachePath/$safeImageName"
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
