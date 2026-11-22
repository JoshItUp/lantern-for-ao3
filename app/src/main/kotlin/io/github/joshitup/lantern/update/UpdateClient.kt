package io.github.joshitup.lantern.update

import io.github.joshitup.lantern.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Reads GitHub releases. Uses its own OkHttp client (no AO3 cookies or interceptors) and sends no identifiers.
 * Every unusable response (non-2xx, non-JSON, draft, prerelease, bad tag, no APK) is null; only network failures throw [java.io.IOException].
 */
class UpdateClient(
    private val http: OkHttpClient,
    private val baseUrl: HttpUrl = "https://api.github.com/".toHttpUrl(),
    private val userAgent: String,
    private val repo: String = BuildConfig.UPDATE_REPO,
) : ReleaseSource {

    override suspend fun latest(): ReleaseInfo? = fetch("releases/latest")

    override suspend fun byTag(tag: String): ReleaseInfo? = fetch("releases/tags/$tag")

    /** Streaming GET of a release asset; the caller closes the response. Blocking. */
    fun download(url: String): Response = http.newCall(request(url.toHttpUrl(), "application/octet-stream")).execute()

    private suspend fun fetch(path: String): ReleaseInfo? = withContext(Dispatchers.IO) {
        val url = baseUrl.newBuilder().addPathSegments("repos/$repo/$path").build()
        http.newCall(request(url, "application/vnd.github+json")).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = resp.body.string()
            val dto = try {
                json.decodeFromString<ReleaseDto>(body)
            } catch (e: IllegalArgumentException) {
                return@use null // not JSON, or not a release
            }
            toInfo(dto)
        }
    }

    private fun request(url: HttpUrl, accept: String) =
        Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", accept).build()

    private fun toInfo(dto: ReleaseDto): ReleaseInfo? {
        if (dto.draft || dto.prerelease) return null
        val version = ReleaseVersion.parseTag(dto.tagName) ?: return null
        val apk = dto.assets.firstOrNull {
            it.contentType == APK_TYPE || it.name.endsWith(".apk", ignoreCase = true)
        } ?: return null
        return ReleaseInfo(version, dto.name ?: dto.tagName, dto.body.orEmpty(), apk.url, apk.size, dto.htmlUrl.orEmpty())
    }

    @Serializable
    private class ReleaseDto(
        @SerialName("tag_name") val tagName: String,
        val name: String? = null,
        val body: String? = null,
        @SerialName("html_url") val htmlUrl: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<AssetDto> = emptyList(),
    )

    @Serializable
    private class AssetDto(
        val name: String,
        @SerialName("browser_download_url") val url: String,
        val size: Long = 0,
        @SerialName("content_type") val contentType: String? = null,
    )

    companion object {
        private const val APK_TYPE = "application/vnd.android.package-archive"
        private val json = Json { ignoreUnknownKeys = true }

        /** The separate client for update traffic: no cookie jar, no AO3 interceptors, follows redirects. */
        fun httpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
