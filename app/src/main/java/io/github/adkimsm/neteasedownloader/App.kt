package io.github.adkimsm.neteasedownloader

import android.app.Application
import io.github.adkimsm.neteasedownloader.data.CookieStore
import io.github.adkimsm.neteasedownloader.net.NcmApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var cookieStore: CookieStore
        private set
    lateinit var ncmApi: NcmApi
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        cookieStore = CookieStore(this)
        ncmApi = NcmApi(cookieStore)
        appScope.launch { cookieStore.init() }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
