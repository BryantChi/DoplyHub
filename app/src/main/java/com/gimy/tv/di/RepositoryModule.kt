package com.gimy.tv.di

import com.gimy.tv.data.repository.*
import com.gimy.tv.data.scraper.*
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.repository.*
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindVodRepository(impl: VodRepositoryImpl): VodRepository

    @Binds
    abstract fun bindFavoriteRepository(impl: FavoriteRepositoryImpl): FavoriteRepository

    @Binds
    abstract fun bindWatchHistoryRepository(impl: WatchHistoryRepositoryImpl): WatchHistoryRepository

    @Binds
    abstract fun bindSearchHistoryRepository(impl: SearchHistoryRepositoryImpl): SearchHistoryRepository

    @Binds @IntoMap @SourceTypeKey(SourceType.GIMYMAX)
    abstract fun bindGimyMaxIntoMap(s: GimyMaxSource): SiteSource

    @Binds @IntoMap @SourceTypeKey(SourceType.GIMYTV)
    abstract fun bindGimyTvIntoMap(s: GimyTvSource): SiteSource

    @Binds @IntoMap @SourceTypeKey(SourceType.MOVIEFFM)
    abstract fun bindMovieffmIntoMap(s: MovieffmSource): SiteSource

    @Binds @IntoMap @SourceTypeKey(SourceType.GIMY_TW)
    abstract fun bindGimyTwIntoMap(s: GimyTwSource): SiteSource

    @Binds @IntoMap @SourceTypeKey(SourceType.EYNY_TV)
    abstract fun bindEynyTvIntoMap(s: EynyTvSource): SiteSource

    @Binds @IntoMap @SourceTypeKey(SourceType.IMAPLE_TV)
    abstract fun bindImapleTvIntoMap(s: ImapleTvSource): SiteSource

    @Binds @IntoMap @SourceTypeKey(SourceType.MOMOVOD)
    abstract fun bindMomovodIntoMap(s: MomovodSource): SiteSource

    @Binds @IntoMap @SourceTypeKey(SourceType.KUBO123)
    abstract fun bindKubo123IntoMap(s: Kubo123Source): SiteSource

    @Binds @IntoMap @SourceTypeKey(SourceType.JABLE_TV)
    abstract fun bindJableTvIntoMap(s: JableTvSource): SiteSource

    @Binds @IntoMap @SourceTypeKey(SourceType.XNXX)
    abstract fun bindXnxxIntoMap(s: XnxxSource): SiteSource

    @Binds @IntoMap @SourceTypeKey(SourceType.FORUM5278)
    abstract fun bindForum5278IntoMap(s: Forum5278Source): SiteSource
}
