package com.gimy.tv

import android.app.Application
import com.gimy.tv.data.endpoint.EndpointResolver
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class DoplyApp : Application() {

    @Inject lateinit var endpointResolver: EndpointResolver

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appScope.launch { endpointResolver.warmUp() }
    }
}
