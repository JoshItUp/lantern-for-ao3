package io.github.joshitup.lantern.data

import io.github.joshitup.lantern.ao3.net.FetchResult
import io.github.joshitup.lantern.ao3.net.Fetcher
import kotlinx.coroutines.CompletableDeferred
import okhttp3.HttpUrl

class FakeFetcher(val responses: MutableMap<String, FetchResult> = mutableMapOf()) : Fetcher {
    val calls = mutableListOf<String>()
    /** The `accept` argument of each call, in order. */
    val accepts = mutableListOf<String?>()
    /** The `interactive` argument of each call, in order. */
    val interactives = mutableListOf<Boolean>()
    /** When set, get() suspends until it completes (a slow network fetch). */
    var gate: CompletableDeferred<Unit>? = null
    /** When set, answers first (null falls through to [responses]); e.g. a challenge only an interactive fetch gets past. */
    var respond: ((url: String, interactive: Boolean) -> FetchResult?)? = null
    /** The `anonymous` argument of each call, in order. */
    val anonymouses = mutableListOf<Boolean>()
    /** Each post() call: url, fields and accept. */
    val posts = mutableListOf<Triple<String, List<Pair<String, String>>, String?>>()
    /** The `interactive` argument of each post() call, in order. */
    val postInteractives = mutableListOf<Boolean>()
    /** Per-URL gates: get() of that URL suspends until the gate completes. */
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
    /** Answers post(); null (the default) answers NotFound. */
    var postResponse: ((url: String, fields: List<Pair<String, String>>) -> FetchResult)? = null
    /** The `forceNetwork` argument of each call, in order. */
    val forceNetworks = mutableListOf<Boolean>()
    override suspend fun get(url: HttpUrl, accept: String?, interactive: Boolean, anonymous: Boolean, forceNetwork: Boolean): FetchResult {
        calls += url.toString()
        forceNetworks += forceNetwork
        anonymouses += anonymous
        accepts += accept
        interactives += interactive
        gate?.await()
        gates[url.toString()]?.await()
        return respond?.invoke(url.toString(), interactive) ?: responses[url.toString()] ?: FetchResult.NotFound
    }

    override suspend fun post(url: HttpUrl, fields: List<Pair<String, String>>, accept: String?, interactive: Boolean): FetchResult {
        posts += Triple(url.toString(), fields, accept)
        postInteractives += interactive
        return postResponse?.invoke(url.toString(), fields) ?: FetchResult.NotFound
    }

    fun ok(url: HttpUrl, html: String) {
        responses[url.toString()] = FetchResult.Ok(html, url)
    }
}

const val CHAPTER_HTML = """<html><body><div id="workskin"><div class="preface group"><h2 class="title">My Work</h2>
<h3 class="byline"><a rel="author">Auth</a></h3></div><div id="chapters"><div class="userstuff module"><p>Hello</p></div></div></div></body></html>"""
