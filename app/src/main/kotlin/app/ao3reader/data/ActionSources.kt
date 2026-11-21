package app.ao3reader.data

import app.ao3reader.ao3.parse.BookmarkInput
import app.ao3reader.ao3.parse.ListActions
import app.ao3reader.ao3.parse.WorkActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.HttpUrl

/** What the reader needs from [ActionRepository] (a seam for ViewModel tests); see it for the contract. */
interface WorkActionSource {
    /** Null: signed out (the actions are not offered). */
    val actingAccount: StateFlow<String?>
    val state: StateFlow<Map<Long, WorkActions>>
    fun existingBookmark(workId: Long): BookmarkInput?
    suspend fun refresh(workId: Long): Loaded<WorkActions>
    suspend fun kudos(workId: Long): Loaded<Unit>
    suspend fun toggleMarkForLater(workId: Long): Loaded<Boolean>
    suspend fun toggleSubscribe(workId: Long): Loaded<Boolean>
    suspend fun saveBookmark(workId: Long, input: BookmarkInput): Loaded<Unit>
    suspend fun comment(workId: Long, chapterId: Long?, text: String): Loaded<Unit>
}

/** What a series/author work list needs from [ActionRepository] (a seam for ViewModel tests). */
interface ListActionSource {
    val actingAccount: StateFlow<String?>
    val listState: StateFlow<Map<String, ListActions>>
    /** The [listState] key of [listUrl] ([ActionRepository.listKey]). */
    fun listKey(listUrl: HttpUrl): String? = ActionRepository.listKey(listUrl)
    suspend fun refreshList(listUrl: HttpUrl): Loaded<ListActions>
    suspend fun toggleListSubscribe(listUrl: HttpUrl): Loaded<Boolean>
}

/**
 * Runs account actions in an app-wide scope, so leaving a screen never cancels a POST half way, and
 * tracks which are in flight ([running]) so a screen opened again still shows them as busy.
 * One action per key at a time.
 */
class ActionRunner(private val scope: CoroutineScope) {
    private val _running = MutableStateFlow<Set<String>>(emptySet())
    val running: StateFlow<Set<String>> = _running.asStateFlow()

    /** Starts [block] under [key]; null (nothing started) while an action with that key is running. */
    fun <T> launch(key: String, block: suspend () -> T): Deferred<T>? {
        while (true) {
            val cur = _running.value
            if (key in cur) return null
            if (_running.compareAndSet(cur, cur + key)) break
        }
        return scope.async {
            try { block() } finally { _running.update { it - key } }
        }
    }

    companion object {
        fun workKey(workId: Long, action: String) = "work:$workId:$action"
        fun listKey(key: String) = "list:$key"
    }
}
