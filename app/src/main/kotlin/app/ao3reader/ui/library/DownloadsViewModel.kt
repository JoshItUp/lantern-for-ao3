package app.ao3reader.ui.library

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.ao3reader.AppContainer
import app.ao3reader.ao3.model.Block
import app.ao3reader.ao3.model.WorkBlurb
import app.ao3reader.data.db.DownloadEntity
import app.ao3reader.data.db.DownloadJob
import app.ao3reader.data.download.DownloadRepository
import app.ao3reader.ui.search.SearchViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the Downloads tab needs from [DownloadRepository] (a seam for tests). */
interface DownloadsSource {
    fun observeAll(): Flow<List<DownloadEntity>>
    suspend fun request(workId: Long, title: String, authors: List<String>)
    suspend fun delete(workId: Long)
}

fun DownloadRepository.asDownloadsSource(): DownloadsSource {
    val repo = this
    return object : DownloadsSource {
        override fun observeAll() = repo.observeAll()
        override suspend fun request(workId: Long, title: String, authors: List<String>) = repo.request(workId, title, authors)
        override suspend fun delete(workId: Long) = repo.delete(workId)
    }
}

/** One saved work. [blurb] is null for a row without a copy (first download queued or failed). */
@Immutable
data class DownloadRow(val entity: DownloadEntity, val blurb: WorkBlurb?, val summary: List<Block>) {
    /** A job is running: Update and Delete are not offered (a delete would race the commit). */
    val busy: Boolean get() = entity.job == DownloadJob.QUEUED || entity.job == DownloadJob.DOWNLOADING
}

@Immutable
data class DownloadsUiState(
    /** Most recently downloaded first; rows without a copy last. */
    val rows: List<DownloadRow> = emptyList(),
    /** Stored bytes over every row. */
    val totalBytes: Long = 0,
    /** Rows with a readable copy. */
    val copies: Int = 0,
    /** The first list arrived: an empty list really is empty. */
    val loaded: Boolean = false,
    /** The work whose inline "Delete download?" is showing. */
    val confirmingDelete: Long? = null,
)

private const val KB = 1024L
private const val MB = 1024L * 1024L

/** < 1 MB: "N KB" (rounded); otherwise "X.Y MB". */
fun formatSize(bytes: Long): String =
    if (bytes < MB) "${Math.round(bytes.toDouble() / KB)} KB"
    else String.format(Locale.US, "%.1f MB", bytes.toDouble() / MB)

fun chaptersLine(e: DownloadEntity): String =
    if (e.chaptersStored == 1) "1 chapter" else "${e.chaptersStored} chapters"

/** Chapters AO3 has that the copy doesn't, when known. */
fun newChapters(e: DownloadEntity): Int? =
    e.chaptersPostedRemote?.let { it - e.chaptersStored }?.takeIf { it > 0 }

/** The tab's header: "3 works · 1.5 MB". */
fun totalLine(works: Int, bytes: Long): String =
    "${if (works == 1) "1 work" else "$works works"} · ${formatSize(bytes)}"

private val DateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)

/** "21 Nov 2026", in the device's zone. */
fun downloadDate(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DateFormat.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

/** The Library's Downloads tab: works saved for offline reading, from Room. Works signed out. */
class DownloadsViewModel(
    private val source: DownloadsSource,
    private val json: Json,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow(DownloadsUiState())
    val state: StateFlow<DownloadsUiState> = _state.asStateFlow()

    /** Decoded rows by work, reused while a row's stored blurb is unchanged. Touched only off the main thread, in order. */
    private val decoded = HashMap<Long, Pair<String?, Pair<WorkBlurb?, List<Block>>>>()

    init {
        viewModelScope.launch {
            source.observeAll().collect { list ->
                val rows = withContext(parseDispatcher) { prepare(list) }
                _state.update { s ->
                    s.copy(
                        rows = rows,
                        totalBytes = list.sumOf { it.sizeBytes },
                        copies = list.count { it.hasCopy },
                        loaded = true,
                        // A row that went away (or started a job) can't be confirmed any more.
                        confirmingDelete = s.confirmingDelete?.takeIf { id -> rows.any { it.entity.workId == id && !it.busy } },
                    )
                }
            }
        }
    }

    private fun prepare(list: List<DownloadEntity>): List<DownloadRow> {
        val sorted = list.sortedWith(
            compareByDescending<DownloadEntity, Long?>(nullsFirst()) { it.downloadedAt }.thenByDescending { it.requestedAt },
        )
        val ids = HashSet<Long>()
        val rows = sorted.map { e ->
            ids += e.workId
            val key = e.blurbJson.takeIf { e.hasCopy }
            val cached = decoded[e.workId]
            val parts = if (cached != null && cached.first == key) cached.second else decode(key).also { decoded[e.workId] = key to it }
            DownloadRow(e, parts.first, parts.second)
        }
        decoded.keys.retainAll(ids)
        return rows
    }

    private fun decode(blurbJson: String?): Pair<WorkBlurb?, List<Block>> {
        val blurb = blurbJson?.let { runCatching { json.decodeFromString(WorkBlurb.serializer(), it) }.getOrNull() }
        return blurb to (blurb?.let { SearchViewModel.convertSummary(it.summaryHtml) } ?: emptyList())
    }

    private fun row(workId: Long): DownloadRow? = _state.value.rows.firstOrNull { it.entity.workId == workId }

    /** Shows the inline confirmation; nothing is deleted yet. */
    fun askDelete(workId: Long) {
        if (row(workId)?.busy != false) return
        _state.update { it.copy(confirmingDelete = workId) }
    }

    fun cancelDelete() {
        _state.update { it.copy(confirmingDelete = null) }
    }

    fun confirmDelete() {
        val id = _state.value.confirmingDelete ?: return
        _state.update { it.copy(confirmingDelete = null) }
        if (row(id)?.busy != false) return
        viewModelScope.launch { source.delete(id) }
    }

    /** Update a copy, or retry a failed download. */
    fun update(workId: Long) {
        val r = row(workId) ?: return
        if (r.busy) return
        val e = r.entity
        val authors = r.blurb?.authors ?: e.authors.split(", ").filter { it.isNotBlank() }
        viewModelScope.launch { source.request(e.workId, r.blurb?.title ?: e.title, authors) }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                DownloadsViewModel(container.downloadRepo.asDownloadsSource(), Json { ignoreUnknownKeys = true })
            }
        }
    }
}
