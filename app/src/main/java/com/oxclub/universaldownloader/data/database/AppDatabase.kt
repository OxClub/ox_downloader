package com.oxclub.universaldownloader.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.oxclub.universaldownloader.domain.model.DownloadStatus
import com.oxclub.universaldownloader.domain.model.FileCategory

class Converters {
    @TypeConverter
    fun statusToString(value: DownloadStatus): String = value.name

    @TypeConverter
    fun stringToStatus(value: String): DownloadStatus =
        DownloadStatus.entries.firstOrNull { it.name == value } ?: DownloadStatus.FAILED

    @TypeConverter
    fun categoryToString(value: FileCategory): String = value.name

    @TypeConverter
    fun stringToCategory(value: String): FileCategory =
        FileCategory.entries.firstOrNull { it.name == value } ?: FileCategory.OTHER
}

@Database(entities = [DownloadEntity::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
}
