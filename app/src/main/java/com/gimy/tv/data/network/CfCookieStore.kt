package com.gimy.tv.data.network

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private val Context.cfCookieDataStore: DataStore<Preferences> by preferencesDataStore(name = "cf_cookies")

/**
 * Per-host cookie jar backed by DataStore.
 *
 * OkHttp had no CookieJar at all before this — every request went out cookie-less, which is
 * why a solved challenge could never be reused. Persisting matters more than it looks:
 * cf_clearance survives an app restart, so the 6–8 second solve is paid once per device
 * rather than once per launch.
 *
 * No TTL is enforced. Cloudflare decides how long a clearance lives and changes it at will;
 * guessing an expiry only risks discarding a good cookie. The single source of truth for
 * "expired" is the next challenge response.
 */
@Singleton
class CfCookieStore @Inject constructor(
    @ApplicationContext context: Context,
) : CookieJar {

    private val dataStore = context.cfCookieDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** host → "name=value; name=value" — the same shape CookieManager hands back. */
    private val memory = ConcurrentHashMap<String, String>()

    @Volatile
    private var loaded = false

    suspend fun warmUp() {
        if (loaded) return
        runCatching {
            val prefs = dataStore.data.first()
            prefs.asMap().forEach { (key, value) ->
                val host = key.name.removePrefix(KEY_PREFIX)
                if (key.name.startsWith(KEY_PREFIX) && value is String && value.isNotBlank()) {
                    memory[host] = value
                }
            }
        }
        loaded = true
    }

    /** Stores a raw "a=1; b=2" header string, as returned by Android's CookieManager. */
    fun putRaw(host: String, rawCookieHeader: String) {
        if (rawCookieHeader.isBlank()) return
        memory[host] = rawCookieHeader
        scope.launch {
            runCatching {
                dataStore.edit { it[stringPreferencesKey("$KEY_PREFIX$host")] = rawCookieHeader }
            }
        }
    }

    fun rawFor(host: String): String? = memory[host]

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val raw = memory[url.host] ?: return emptyList()
        return raw.split(';').mapNotNull { pair ->
            val trimmed = pair.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            Cookie.parse(url, "$trimmed; Path=/; Domain=${url.host}")
        }
    }

    /**
     * Responses may refresh the clearance. Merge rather than replace so a Set-Cookie that
     * only carries a session id does not wipe the cf_clearance we solved for.
     */
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val merged = linkedMapOf<String, String>()
        memory[url.host]?.split(';')?.forEach { pair ->
            val (n, v) = pair.trim().split('=', limit = 2).let {
                if (it.size == 2) it[0] to it[1] else return@forEach
            }
            merged[n] = v
        }
        cookies.forEach { merged[it.name] = it.value }
        putRaw(url.host, merged.entries.joinToString("; ") { "${it.key}=${it.value}" })
    }

    private companion object {
        const val KEY_PREFIX = "cookies_"
    }
}
