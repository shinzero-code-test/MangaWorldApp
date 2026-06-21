package com.exapps.mangaworld.core.di

import android.content.Context
import androidx.room.Room
import com.exapps.mangaworld.BuildConfig
import com.exapps.mangaworld.core.data.*
import com.exapps.mangaworld.core.data.local.MangaDatabase
import com.exapps.mangaworld.core.data.local.dao.*
import com.exapps.mangaworld.core.data.remote.scraper.*
import com.exapps.mangaworld.core.firebase.FirebaseCommunityRepository
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
import coil.ImageLoader
import coil.disk.DiskCache
import okhttp3.Cache
import okhttp3.ConnectionPool
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
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                    else HttpLoggingInterceptor.Level.NONE
        }

        return OkHttpClient.Builder()
            .cache(cache)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .addInterceptor(firebaseNetworkInterceptor)
            .addInterceptor(logging)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", BaseScraperImpl.USER_AGENT)
                    .header("Accept-Language", "ar,en;q=0.9")
                    .build()
                chain.proceed(req)
            }
            .build()
    }

    @Provides
    @Singleton
    fun provideImageLoader(@ApplicationContext ctx: Context, okHttpClient: OkHttpClient): ImageLoader =
        ImageLoader.Builder(ctx)
            .okHttpClient(okHttpClient)
            .diskCache {
                DiskCache.Builder()
                    .directory(File(ctx.cacheDir, "coil_image_cache"))
                    .maxSizeBytes(250L * 1024L * 1024L)
                    .build()
            }
            .crossfade(true)
            .build()
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

    // ─── v4.0.0 — Madara Theme Sources ────────────────────────────────────────

    @Provides @Singleton @IntoMap @StringKey("asq3")
    fun provideAsq3Scraper(client: OkHttpClient, settingsRepo: SettingsRepository): MangaScraper = Asq3Scraper(client, settingsRepo)

    @Provides @Singleton @IntoMap @StringKey("lekmanga")
    fun provideLekMangaScraper(client: OkHttpClient, settingsRepo: SettingsRepository): MangaScraper = LekMangaScraper(client, settingsRepo)

    @Provides @Singleton @IntoMap @StringKey("lekmangaonline")
    fun provideLekMangaOnlineScraper(client: OkHttpClient, settingsRepo: SettingsRepository): MangaScraper = LekMangaOnlineScraper(client, settingsRepo)

    @Provides @Singleton @IntoMap @StringKey("likemanga")
    fun provideLikeMangaScraper(client: OkHttpClient, settingsRepo: SettingsRepository): MangaScraper = LikeMangaScraper(client, settingsRepo)

    @Provides @Singleton @IntoMap @StringKey("linkmanga")
    fun provideLinkMangaScraper(client: OkHttpClient, settingsRepo: SettingsRepository): MangaScraper = LinkMangaScraper(client, settingsRepo)

    @Provides @Singleton @IntoMap @StringKey("mangaleko")
    fun provideMangaLekoScraper(client: OkHttpClient, settingsRepo: SettingsRepository): MangaScraper = MangaLekoScraper(client, settingsRepo)

    @Provides @Singleton @IntoMap @StringKey("mangalionz")
    fun provideMangaLionzScraper(client: OkHttpClient, settingsRepo: SettingsRepository): MangaScraper = MangaLionzScraper(client, settingsRepo)

    // ─── v4.0.0 — MangaReader Theme Sources ───────────────────────────────────

    @Provides @Singleton @IntoMap @StringKey("areascans")
    fun provideAreaScansScraper(client: OkHttpClient, settingsRepo: SettingsRepository): MangaScraper = AreaScansScraper(client, settingsRepo)

    @Provides @Singleton @IntoMap @StringKey("hijala")
    fun provideHijalaScraper(client: OkHttpClient, settingsRepo: SettingsRepository): MangaScraper = HijalaScraper(client, settingsRepo)

    @Provides @Singleton @IntoMap @StringKey("lavascans")
    fun provideLavaScansScraper(client: OkHttpClient, settingsRepo: SettingsRepository): MangaScraper = LavaScansScraper(client, settingsRepo)

    @Provides @Singleton @IntoMap @StringKey("stellarsaber")
    fun provideStellarSaberScraper(client: OkHttpClient, settingsRepo: SettingsRepository): MangaScraper = StellarSaberScraper(client, settingsRepo)

    // ─── v4.0.0 — Custom Theme Sources ────────────────────────────────────────

    @Provides @Singleton @IntoMap @StringKey("procomic")
    fun provideProComicScraper(client: OkHttpClient, settingsRepo: SettingsRepository): MangaScraper = ProComicScraper(client, settingsRepo)

    @Provides @Singleton @IntoMap @StringKey("rockmanga")
    fun provideRockMangaScraper(client: OkHttpClient, settingsRepo: SettingsRepository): MangaScraper = RockMangaScraper(client, settingsRepo)
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): MangaDatabase =
        Room.databaseBuilder(ctx, MangaDatabase::class.java, "mangaworld.db")
            .addMigrations(MangaDatabase.MIGRATION_8_9, MangaDatabase.MIGRATION_9_10)
            .fallbackToDestructiveMigrationOnDowngrade()
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

    @Binds @Singleton
    abstract fun bindCommunityRepository(impl: FirebaseCommunityRepository): CommunityRepository
}
