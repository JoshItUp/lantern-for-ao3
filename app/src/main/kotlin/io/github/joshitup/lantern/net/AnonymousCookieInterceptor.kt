package io.github.joshitup.lantern.net

import io.github.joshitup.lantern.ao3.net.Anonymous
import okhttp3.Interceptor
import okhttp3.Response

/** The cookies that carry the user's AO3 login. Everything else (Cloudflare's included) is left alone. */
internal val AO3_SESSION_COOKIES = setOf("_otwarchive_session", "remember_user_token")

private fun cookieName(pair: String): String = pair.substringBefore('=').trim()

/**
 * Network interceptor (it must run after OkHttp's bridge has added the jar's cookies, and before the
 * bridge stores the response's): for requests tagged [Anonymous] it strips the AO3 session cookies
 * from the outgoing `Cookie` header, and drops any `Set-Cookie` for them so a logged-out response
 * cannot overwrite the stored session.
 */
class AnonymousCookieInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.tag(Anonymous::class.java) == null) return chain.proceed(request)

        val kept = request.headers("Cookie")
            .flatMap { it.split(";") }
            .map { it.trim() }
            .filter { it.isNotEmpty() && cookieName(it) !in AO3_SESSION_COOKIES }
        val outgoing = request.newBuilder().removeHeader("Cookie").apply {
            if (kept.isNotEmpty()) header("Cookie", kept.joinToString("; "))
        }.build()

        val response = chain.proceed(outgoing)
        val setCookies = response.headers("Set-Cookie")
        val allowed = setCookies.filter { cookieName(it) !in AO3_SESSION_COOKIES }
        if (allowed.size == setCookies.size) return response
        return response.newBuilder().removeHeader("Set-Cookie").apply {
            allowed.forEach { addHeader("Set-Cookie", it) }
        }.build()
    }
}
