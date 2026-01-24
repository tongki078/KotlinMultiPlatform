package com.nas.musicplayer

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment

class AndroidFileDownloader(private val context: Context) : FileDownloader {
    
    private val callbacks = mutableMapOf<Long, (Boolean, String?) -> Unit>()

    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: -1
                callbacks.remove(id)?.let { callback ->
                    callback(true, "보관함에 추가되었습니다.")
                }
            }
        }
        
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    override fun downloadFile(url: String, imageUrl: String?, fileName: String, onResult: (Boolean, String?) -> Unit) {
        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setDescription("음악 다운로드 중...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = downloadManager.enqueue(request)
            
            callbacks[downloadId] = onResult
        } catch (e: Exception) {
            onResult(false, e.message)
        }
    }
}

private var androidDownloader: FileDownloader? = null

fun initAndroidDownloader(context: Context) {
    if (androidDownloader == null) {
        androidDownloader = AndroidFileDownloader(context)
    }
}

actual fun getFileDownloader(): FileDownloader = androidDownloader ?: throw Exception("Downloader not initialized")
