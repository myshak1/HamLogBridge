package pl.hamlogbridge.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [QsoEntity::class, UploadEntity::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun qsoDao(): QsoDao
    abstract fun uploadDao(): UploadDao

    companion object {
        @Volatile private var instance: AppDb? = null

        fun get(ctx: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                ctx.applicationContext, AppDb::class.java, "hamlogbridge.db"
            ).fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}
