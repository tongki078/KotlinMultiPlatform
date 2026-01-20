package com.nas.musicplayer.db

import androidx.room.Room
import androidx.room.RoomDatabaseConstructor

actual object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase = Room.generated_AppDatabaseConstructor().initialize()
}
