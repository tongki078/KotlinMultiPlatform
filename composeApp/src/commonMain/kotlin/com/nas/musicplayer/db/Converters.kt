package com.nas.musicplayer.db

import androidx.room.TypeConverter
import com.nas.musicplayer.Song
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class Converters {
    @TypeConverter
    fun fromString(value: String): List<Song> {
        return Json.decodeFromString(value)
    }

    @TypeConverter
    fun fromList(list: List<Song>): String {
        return Json.encodeToString(list)
    }
}
