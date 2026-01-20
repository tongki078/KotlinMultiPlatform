package com.nas.musicplayer.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recent_searches")
data class RecentSearch(
    @PrimaryKey val query: String,
    val timestamp: Long = 0L // System.currentTimeMillis() is not available in commonMain easily without expect/actual or a library, but for now we keep the field.
)
