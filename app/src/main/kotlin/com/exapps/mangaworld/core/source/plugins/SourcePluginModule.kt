package com.exapps.mangaworld.core.source.plugins

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

/**
 * Phase 1 DI: one `@Binds` line per built-in source plugin. Adding a source means
 * adding one plugin class plus one line here — the enum entry, drawable, strings and
 * provider method it replaces are gone (rockmanga was removed outright: dead upstream).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SourcePluginModule {

    @Binds @IntoMap @StringKey("olympus")
    abstract fun bindOlympus(plugin: OlympusPlugin): SourcePlugin

    @Binds @IntoMap @StringKey("azora")
    abstract fun bindAzora(plugin: AzoraPlugin): SourcePlugin

    @Binds @IntoMap @StringKey("starz")
    abstract fun bindStarz(plugin: StarzPlugin): SourcePlugin

    @Binds @IntoMap @StringKey("mangasid")
    abstract fun bindMangaSid(plugin: MangaSidPlugin): SourcePlugin

    @Binds @IntoMap @StringKey("meshmanga")
    abstract fun bindMeshmanga(plugin: MeshmangaPlugin): SourcePlugin

    @Binds @IntoMap @StringKey("asq3")
    abstract fun bindAsq3(plugin: Asq3Plugin): SourcePlugin

    @Binds @IntoMap @StringKey("lekmanga")
    abstract fun bindLekManga(plugin: LekMangaPlugin): SourcePlugin

    @Binds @IntoMap @StringKey("lekmangaonline")
    abstract fun bindLekMangaOnline(plugin: LekMangaOnlinePlugin): SourcePlugin

    @Binds @IntoMap @StringKey("likemanga")
    abstract fun bindLikeManga(plugin: LikeMangaPlugin): SourcePlugin

    @Binds @IntoMap @StringKey("linkmanga")
    abstract fun bindLinkManga(plugin: LinkMangaPlugin): SourcePlugin

    @Binds @IntoMap @StringKey("mangaleko")
    abstract fun bindMangaLeko(plugin: MangaLekoPlugin): SourcePlugin

    @Binds @IntoMap @StringKey("mangalionz")
    abstract fun bindMangaLionz(plugin: MangaLionzPlugin): SourcePlugin

    @Binds @IntoMap @StringKey("areascans")
    abstract fun bindAreaScans(plugin: AreaScansPlugin): SourcePlugin

    @Binds @IntoMap @StringKey("hijala")
    abstract fun bindHijala(plugin: HijalaPlugin): SourcePlugin

    @Binds @IntoMap @StringKey("lavascans")
    abstract fun bindLavaScans(plugin: LavaScansPlugin): SourcePlugin

    @Binds @IntoMap @StringKey("stellarsaber")
    abstract fun bindStellarSaber(plugin: StellarSaberPlugin): SourcePlugin

    @Binds @IntoMap @StringKey("procomic")
    abstract fun bindProComic(plugin: ProComicPlugin): SourcePlugin
}
