package com.nas.musicplayer.db

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
fun getDatabase(): AppDatabase {
    val documentDirectory = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null
    )
    val dbFile = (documentDirectory?.path ?: "") + "/music_database.db"

    return Room.databaseBuilder<AppDatabase>(
        name = dbFile,
        factory = { AppDatabaseConstructor.initialize() }
    ).setDriver(BundledSQLiteDriver())
        .build()
}
