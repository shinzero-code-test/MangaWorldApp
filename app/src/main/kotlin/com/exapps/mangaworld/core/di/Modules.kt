package com.exapps.mangaworld.core.di

import android.content.Context
import androidx.room.Room
import com.exapps.mangaworld.core.data.*
import com.exapps.mangaworld.core.data.local.MangaDatabase
import com.exapps.mangaworld.core.data.local.dao.*
import com.exapps.mangaworld.core.data.remote.scraper.*
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.domain.repository.SettingsRepository
import com.exapps.mangaworld.domain.repository.*
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import com.exapps.mangaworld.core.firebase.FirebaseNetworkInterceptor
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext ctx: Context, firebaseNetworkInterceptor: FirebaseNetworkInterceptor): OkHttpClient {
        val cacheDir = File(ctx.cacheDir, "http_cache")
        val cache = Cache(cacheDir, 50L * 1024 * 1024) // 50MB

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        return OkHttpClient.Builder()
            .cache(cache)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .addNetworkInterceptor(firebaseNetworkInterceptor)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", BaseScraperImpl.USER_AGENT)
                    .header("Accept-Language", "ar,en;q=0.9")
                    .build()
                chain.proceed(req)
            }
            .build()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object ScraperModule {

    @Provides
    @Singleton
    @IntoMap
    @StringKey("olympus")
    fun provideOlympusScraper(client: OkHttpClient, settingsRepo: SettingsRepository): MangaScraper = OlympusScraper(client, settingsRepo)

    @Provides
    @Singleton
    @IntoMap
    @StringKey("azora")
    fun provideAzoraScraper(client: OkHttpClient, settingsRepo: SettingsRepository): MangaScraper = AzoraScraper(client, settingsRepo)

    @Provides
    @Singleton
    @IntoMap
    @StringKey("starz")
    fun provideStarzScraper(client: OkHttpClient, settingsRepo: SettingsRepository): MangaScraper = StarzScraper(client, settingsRepo)

    @Provides
    @Singleton
    @IntoMap
    @StringKey("mangasid")
    fun provideMangaSidScraper(client: OkHttpClient, settingsRepo: SettingsRepository): MangaScraper = MangaSidScraper(client, settingsRepo)

    @Provides
    @Singleton
    @IntoMap
    @StringKey("meshmanga")
    fun provideMeshmangaScraper(client: OkHttpClient, settingsRepo: SettingsRepository): MangaScraper = MeshmangaScraper(client, settingsRepo)
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): MangaDatabase =
        Room.databaseBuilder(ctx, MangaDatabase::class.java, "mangaworld.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideFavoriteDao(db: MangaDatabase) = db.favoriteDao()
    @Provides fun provideHistoryDao(db: MangaDatabase) = db.readingHistoryDao()
    @Provides fun provideReadChapterDao(db: MangaDatabase) = db.readChapterDao()
    @Provides fun provideProgressDao(db: MangaDatabase) = db.readingProgressDao()
    @Provides fun provideReaderAnnotationDao(db: MangaDatabase) = db.readerAnnotationDao()
    @Provides fun provideCacheDao(db: MangaDatabase) = db.mangaCacheDao()
    @Provides fun provideDownloadTaskDao(db: MangaDatabase) = db.downloadTaskDao()
    @Provides fun provideDownloadedMangaDao(db: MangaDatabase) = db.downloadedMangaDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds @Singleton
    abstract fun bindMangaRepository(impl: MangaRepositoryImpl): MangaRepository

    @Binds @Singleton
    abstract fun bindLibraryRepository(impl: LibraryRepositoryImpl): LibraryRepository

    @Binds @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository
}
