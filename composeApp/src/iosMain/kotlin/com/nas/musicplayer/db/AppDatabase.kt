package com.nas.musicplayer.db

import androidx.room.Room
import androidx.room.RoomDatabase
import platform.Foundation.NSHomeDirectory

actual abstract class AppDatabase : RoomDatabase() {
    actual abstract fun playlistDao(): PlaylistDao
    actual abstract fun recentSearchDao(): RecentSearchDao
}

fun getDatabase(): AppDatabase {
    val dbFile = NSHomeDirectory() + "/music_database.db"
    return Room.databaseBuilder<AppDatabase>(
        name = dbFile,
        factory = { AppDatabaseConstructor.initialize() }
    ).build()
}
