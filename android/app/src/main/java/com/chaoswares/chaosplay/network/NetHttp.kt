package com.chaoswares.chaosplay.network

import android.annotation.SuppressLint
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * One HTTP stack for the whole app: Jellyfin API calls, WebDAV PROPFIND,
 * subtitle downloads and Media3 playback all go through the same OkHttp
 * client, so credentials and certificate decisions are applied in one place.
 *
 * - [authorise] registers headers for a URL prefix (WebDAV Basic auth). The
 *   player's data source adds them per request, so tokens and passwords
 *   never appear in a URL that history or logs could keep.
 * - [trustHost] marks a host whose self-signed certificate the user
 *   explicitly accepted (spec 19.8). Every other host gets normal
 *   certificate validation.
 */
object NetHttp {

    private val headersByPrefix = ConcurrentHashMap<String, Map<String, String>>()
    private val trustedHosts = ConcurrentHashMap.newKeySet<String>()

    const val USER_AGENT = "CPlay/1.2 (Android)"

    fun authorise(urlPrefix: String, headers: Map<String, String>) {
        if (urlPrefix.isBlank()) return
        headersByPrefix[urlPrefix] = headers
    }

    fun forget(urlPrefix: String) {
        headersByPrefix.remove(urlPrefix)
    }

    /** Headers for [url]: the longest registered prefix wins. */
    fun headersFor(url: String): Map<String, String> =
        headersByPrefix.entries
            .filter { url.startsWith(it.key) }
            .maxByOrNull { it.key.length }
            ?.value
            ?: emptyMap()

    fun trustHost(host: String, trusted: Boolean) {
        if (host.isBlank()) return
        if (trusted) trustedHosts += host.lowercase() else trustedHosts -= host.lowercase()
    }

    private val systemTrust: X509TrustManager by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /**
     * Trusts the system store; for hosts the user explicitly accepted, a
     * chain the system rejects is allowed too. The host check happens in the
     * hostname verifier below because a trust manager does not see the host.
     */
    @SuppressLint("CustomX509TrustManager")
    private val lenientTrust = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            systemTrust.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            try {
                systemTrust.checkServerTrusted(chain, authType)
            } catch (e: Exception) {
                if (trustedHosts.isEmpty()) throw e
                // Accepted only if the connection's host is user-trusted; the
                // verifier rejects any other host using an untrusted chain.
                lastUntrustedChain.set(true)
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = systemTrust.acceptedIssuers
    }

    private val lastUntrustedChain = ThreadLocal<Boolean>()

    private val verifier = HostnameVerifier { host, session ->
        val untrusted = lastUntrustedChain.get() == true
        lastUntrustedChain.remove()
        if (host.lowercase() in trustedHosts) return@HostnameVerifier true
        if (untrusted) return@HostnameVerifier false
        HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
    }

    val client: OkHttpClient by lazy {
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(lenientTrust), null) }
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .sslSocketFactory(ssl.socketFactory, lenientTrust)
            .hostnameVerifier(verifier)
            .addInterceptor { chain ->
                val request = chain.request()
                val builder = request.newBuilder().header("User-Agent", USER_AGENT)
                headersFor(request.url.toString()).forEach { (k, v) ->
                    if (request.header(k) == null) builder.header(k, v)
                }
                chain.proceed(builder.build())
            }
            .build()
    }

    /** Media3 HTTP data source on the shared client, with per-URL credentials. */
    @OptIn(UnstableApi::class)
    fun mediaDataSourceFactory(): DataSource.Factory {
        val okHttp = OkHttpDataSource.Factory(client).setUserAgent(USER_AGENT)
        return ResolvingDataSource.Factory(okHttp) { spec ->
            val extra = headersFor(spec.uri.toString())
            if (extra.isEmpty()) spec else spec.withAdditionalHeaders(extra)
        }
    }
}
