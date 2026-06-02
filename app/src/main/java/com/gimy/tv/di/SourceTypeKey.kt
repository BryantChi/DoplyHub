package com.gimy.tv.di

import com.gimy.tv.domain.model.SourceType
import dagger.MapKey

@MapKey
annotation class SourceTypeKey(val value: SourceType)
