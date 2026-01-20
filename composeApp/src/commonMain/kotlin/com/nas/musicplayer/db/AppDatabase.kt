package com.nas.musicplayer.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters

@Database(entities = [PlaylistEntity::class, RecentSearch::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
@ConstructedBy(AppDatabaseConstructor::class)
expect abstract class AppDatabase : RoomDatabase {
    abstract fun playlistDao(): PlaylistDao
    abstract fun recentSearchDao(): RecentSearchDao
}

expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase>
