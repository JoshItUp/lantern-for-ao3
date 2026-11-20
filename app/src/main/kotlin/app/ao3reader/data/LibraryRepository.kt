package app.ao3reader.data

import app.ao3reader.account.Session
import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.ao3.parse.Ao3Urls
import app.ao3reader.ao3.parse.LibraryPage
import app.ao3reader.ao3.parse.LibraryParser
import app.ao3reader.ao3.parse.SubscriptionPage
import app.ao3reader.data.db.AppDatabase
import app.ao3reader.data.db.LibraryPageEntity
import app.ao3reader.data.db.ReadMarkEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class LibrarySection { HISTORY, MARKED_FOR_LATER, BOOKMARKS, SUBSCRIPTIONS }

/** Returned (inside [FetchResult.Failed]) when a library call is made without a signed-in account. */
class NotSignedInException : IllegalStateException("not signed in")

private val libraryJson = Json { ignoreUnknownKeys = true }

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryRepository(
    private val session: Session,
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Page 1 of [section] as last loaded by the current account; null if never loaded, unreadable, or [page] > 1 (not persisted). */
    fun cached(section: LibrarySection, page: Int): Flow<LibraryPage?> {
        if (page != 1 || section == LibrarySection.SUBSCRIPTIONS) return flowOf(null)
        val dao = db.libraryPageDao()
        // Follows the signed-in account: another account never sees these rows.
        return session.actingAccount.map { it?.lowercase() }.distinctUntilChanged().flatMapLatest { user ->
            if (user == null) flowOf(null)
            else dao.observe(user, section.name, page).distinctUntilChanged().map { row -> if (row == null) null else user to row }
        }.map { hit ->
            if (hit == null) return@map null
            val (user, row) = hit
            try {
                libraryJson.decodeFromString<LibraryPage>(row.json)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                dao.delete(user, section.name, page)
                null
            }
        }
    }

    /** SUBSCRIPTIONS is not supported here (use [subscriptions]): it returns an IllegalArgumentException error. */
    suspend fun load(section: LibrarySection, page: Int): Loaded<LibraryPage> {
        if (section == LibrarySection.SUBSCRIPTIONS) {
            return Loaded.Error(FetchResult.Failed(IllegalArgumentException("use subscriptions()")))
        }
        val user = username() ?: return notSignedIn()
        val key = user.lowercase()
        val url = when (section) {
            LibrarySection.HISTORY -> Ao3Urls.readings(user, page, false)
            LibrarySection.MARKED_FOR_LATER -> Ao3Urls.readings(user, page, true)
            else -> Ao3Urls.bookmarks(user, page)
        }
        val r = session.authedGet(url)
        if (r !is FetchResult.Ok) return Loaded.Error(r)
        // A signed-out page (re-login impossible or failed) parses as an empty or public-only list: never show or cache it.
        if (!session.state.value.signedIn) return notSignedIn()
        val parsed = withContext(parseDispatcher) {
            if (section == LibrarySection.BOOKMARKS) LibraryParser.bookmarks(r.html, page)
            else LibraryParser.readings(r.html, page)
        }
        withContext(ioDispatcher) {
            if (page == 1) {
                db.libraryPageDao().upsert(
                    LibraryPageEntity(key, section.name, page, libraryJson.encodeToString(parsed), clock())
                )
            }
            if (section == LibrarySection.HISTORY && parsed.works.isNotEmpty()) {
                val now = clock()
                db.readMarkDao().upsertAll(parsed.works.map { ReadMarkEntity(it.workId, now) })
            }
        }
        return Loaded.Ok(parsed)
    }

    /** Never cached: the items hold URLs. */
    suspend fun subscriptions(page: Int): Loaded<SubscriptionPage> {
        val user = username() ?: return notSignedIn()
        val r = session.authedGet(Ao3Urls.subscriptions(user, page))
        if (r !is FetchResult.Ok) return Loaded.Error(r)
        if (!session.state.value.signedIn) return notSignedIn()
        return Loaded.Ok(withContext(parseDispatcher) { LibraryParser.subscriptions(r.html, page, r.finalUrl) })
    }

    /** Drops every cached page (on sign-out). Read marks are kept: they are about works, not the account. */
    suspend fun clear() = withContext(ioDispatcher) { db.libraryPageDao().clear() }

    /** The acting account, so a lapsed session still reaches [Session.authedGet] and its silent re-login. */
    private fun username(): String? = session.actingAccount.value

    private fun notSignedIn() = Loaded.Error(FetchResult.Failed(NotSignedInException()))
}
