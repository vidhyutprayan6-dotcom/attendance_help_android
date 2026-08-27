package attendance.help.device.data

import android.content.Context
import androidx.room.Room
import attendance.help.device.data.local.AppDatabase
import attendance.help.device.data.local.PeerDao
import attendance.help.device.data.local.SessionRepositoryImpl
import attendance.help.device.domain.repository.SessionRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindModule {
    @Binds
    @Singleton
    abstract fun bindSessionRepository(impl: SessionRepositoryImpl): SessionRepository
}

@Module
@InstallIn(SingletonComponent::class)
object DataProvideModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "attendance_help.db").build()

    @Provides
    fun providePeerDao(db: AppDatabase): PeerDao = db.peerDao()
}
