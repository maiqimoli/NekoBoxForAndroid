package moe.matsuri.nb4a

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.preference.KeyValuePair

@Database(entities = [KeyValuePair::class], version = 1)
abstract class TempDatabase : RoomDatabase() {

    companion object {
        private val instance by lazy {
            Room.inMemoryDatabaseBuilder(SagerNet.application, TempDatabase::class.java)
                // Ephemeral PreferenceDataStore backing; its AndroidX contract is synchronous.
                .allowMainThreadQueries()
                .build()
        }

        val profileCacheDao get() = instance.profileCacheDao()

    }

    abstract fun profileCacheDao(): KeyValuePair.Dao
}
