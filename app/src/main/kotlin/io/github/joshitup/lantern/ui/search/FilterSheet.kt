package io.github.joshitup.lantern.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.joshitup.lantern.ao3.model.Category
import io.github.joshitup.lantern.ao3.model.Completion
import io.github.joshitup.lantern.ao3.model.Crossover
import io.github.joshitup.lantern.ao3.model.Rating
import io.github.joshitup.lantern.ao3.model.SearchQuery
import io.github.joshitup.lantern.ao3.model.SortColumn
import io.github.joshitup.lantern.ao3.model.Warning
import kotlinx.coroutines.flow.Flow

/** A filter toggle's state: tapping cycles off → included → excluded → off. */
enum class TriState { OFF, INCLUDED, EXCLUDED }

fun <T> triState(v: T, included: Set<T>, excluded: Set<T>): TriState = when (v) {
    in included -> TriState.INCLUDED
    in excluded -> TriState.EXCLUDED
    else -> TriState.OFF
}

/**
 * The (included, excluded) sets after tapping [v]: off → included → excluded → off. With [singleInclude]
 * (ratings) an include replaces any other include; exclusions are never touched by another value's tap.
 */
fun <T> cycle(v: T, included: Set<T>, excluded: Set<T>, singleInclude: Boolean = false): Pair<Set<T>, Set<T>> =
    when (triState(v, included, excluded)) {
        TriState.OFF -> (if (singleInclude) setOf(v) else included + v) to excluded
        TriState.INCLUDED -> (included - v) to (excluded + v)
        TriState.EXCLUDED -> included to (excluded - v)
    }

/** Reset: every filter back to its default, keeping only the search bar's text. */
fun resetFilters(q: SearchQuery): SearchQuery = SearchQuery(anyField = q.anyField)

/** How many filter groups differ from defaults (any-field text excluded: it lives in the search bar). */
fun activeFilterCount(q: SearchQuery): Int {
    val d = SearchQuery()
    return listOf(
        q.title != d.title, q.creators != d.creators, q.fandoms.isNotEmpty(), q.characters.isNotEmpty(),
        q.relationships.isNotEmpty(), q.freeforms.isNotEmpty(), q.excludedTags.isNotEmpty(),
        q.excludedCharacters.isNotEmpty(), q.excludedRelationships.isNotEmpty(),
        q.ratings.isNotEmpty() || q.excludedRatings.isNotEmpty(), q.warnings.isNotEmpty() || q.excludedWarnings.isNotEmpty(),
        q.categories.isNotEmpty() || q.excludedCategories.isNotEmpty(),
        q.completion != d.completion, q.crossover != d.crossover, q.wordsFrom != null || q.wordsTo != null,
        q.language.isNotBlank(), q.updatedWithin.isNotBlank(),
        q.sort != d.sort || q.sortDescending != d.sortDescending,
    ).count { it }
}

private val SORT_LABELS = mapOf(
    SortColumn.BEST_MATCH to "Best match", SortColumn.UPDATED to "Updated", SortColumn.POSTED to "Posted",
    SortColumn.KUDOS to "Kudos", SortColumn.HITS to "Hits", SortColumn.BOOKMARKS to "Bookmarks",
    SortColumn.COMMENTS to "Comments", SortColumn.WORDS to "Words", SortColumn.TITLE to "Title",
    SortColumn.AUTHOR to "Author",
)

private val UPDATED_CHOICES = listOf("" to "Any time", "< 7 days" to "Past week", "< 30 days" to "Past month",
    "< 365 days" to "Past year")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    initial: SearchQuery,
    autocomplete: (kind: String, term: String) -> Flow<List<String>>,
    onApply: (SearchQuery) -> Unit,
    onSaveAsPreset: (SearchQuery) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var q by remember { mutableStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Filters", style = MaterialTheme.typography.titleLarge)

                Field("Any field", q.anyField) { q = q.copy(anyField = it) }
                Field("Title", q.title) { q = q.copy(title = it) }
                Field("Creators", q.creators) { q = q.copy(creators = it) }

                TagInput("Fandoms", "fandom", q.fandoms, autocomplete) { q = q.copy(fandoms = it) }
                TagInput("Relationships", "relationship", q.relationships, autocomplete) { q = q.copy(relationships = it) }
                TagInput("Characters", "character", q.characters, autocomplete) { q = q.copy(characters = it) }
                TagInput("Additional tags", "freeform", q.freeforms, autocomplete) { q = q.copy(freeforms = it) }
                TagInput("Exclude tags", "tag", q.excludedTags, autocomplete) { q = q.copy(excludedTags = it) }

                Section("Rating") {
                    Rating.entries.forEach { r ->
                        Choice(r.label, r in q.ratings) {
                            val (inc, exc) = cycle(r, q.ratings, q.excludedRatings, singleInclude = true)
                            q = q.copy(ratings = inc, excludedRatings = exc)
                        }
                    }
                }
                Section("Warnings") {
                    Warning.entries.forEach { w ->
                        Choice(w.label, w in q.warnings) { q = q.copy(warnings = q.warnings.toggle(w)) }
                    }
                }
                Section("Categories") {
                    Category.entries.forEach { c ->
                        Choice(c.label, c in q.categories) { q = q.copy(categories = q.categories.toggle(c)) }
                    }
                }

                Label("Status")
                Segmented(
                    listOf(Completion.ANY to "Any", Completion.COMPLETE to "Complete", Completion.IN_PROGRESS to "In progress"),
                    q.completion,
                ) { q = q.copy(completion = it) }
                Label("Crossovers")
                Segmented(
                    listOf(Crossover.ANY to "Include", Crossover.INCLUDE_ONLY to "Only", Crossover.EXCLUDE to "Exclude"),
                    q.crossover,
                ) { q = q.copy(crossover = it) }

                Label("Words")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField("From", q.wordsFrom, Modifier.weight(1f)) { q = q.copy(wordsFrom = it) }
                    NumberField("To", q.wordsTo, Modifier.weight(1f)) { q = q.copy(wordsTo = it) }
                }

                Field("Language code (e.g. en)", q.language) { q = q.copy(language = it.trim()) }

                Section("Updated") {
                    UPDATED_CHOICES.forEach { (value, label) ->
                        Choice(label, q.updatedWithin == value) { q = q.copy(updatedWithin = value) }
                    }
                }
                if (UPDATED_CHOICES.none { it.first == q.updatedWithin } || q.updatedWithin.isEmpty()) {
                    Field("Updated within (AO3 syntax, e.g. < 2 weeks)", q.updatedWithin) { q = q.copy(updatedWithin = it) }
                }

                Section("Sort by") {
                    SORT_LABELS.forEach { (col, label) ->
                        Choice(label, q.sort == col) { q = q.copy(sort = col) }
                    }
                }
                Segmented(listOf(true to "Descending", false to "Ascending"), q.sortDescending) {
                    q = q.copy(sortDescending = it)
                }
                Spacer(Modifier.padding(4.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { q = resetFilters(q) }) { Text("Reset") }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { onSaveAsPreset(q) }) { Text("Save as preset") }
                Button(onClick = { onApply(q) }) { Text("Apply") }
            }
        }
    }
}

private const val MIN_SUGGEST_CHARS = 2
private const val MAX_SUGGESTIONS = 8

private fun <T> Set<T>.toggle(v: T): Set<T> = if (v in this) this - v else this + v

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Label(title)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label, maxLines = 1) }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NumberField(label: String, value: Int?, modifier: Modifier, onChange: (Int?) -> Unit) {
    OutlinedTextField(
        value = value?.toString() ?: "",
        onValueChange = { s -> val digits = s.filter(Char::isDigit).take(9); onChange(digits.toIntOrNull()) },
        label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

/** Chip input: entered tags as removable chips, a text field, and AO3 autocomplete suggestions. */
@Composable
private fun TagInput(
    label: String,
    kind: String,
    values: List<String>,
    autocomplete: (String, String) -> Flow<List<String>>,
    onChange: (List<String>) -> Unit,
) {
    var text by rememberSaveable(kind) { mutableStateOf("") }
    var suggestions by remember(kind) { mutableStateOf(emptyList<String>()) }
    // Restarting on every keystroke cancels the previous flow (its debounce wait or a request still
    // queued for the fetcher), so only the latest term is ever requested.
    LaunchedEffect(kind, text) {
        if (text.trim().length < MIN_SUGGEST_CHARS) suggestions = emptyList()
        autocomplete(kind, text).collect { suggestions = it }
    }
    fun add(tag: String) {
        val t = tag.trim()
        if (t.isNotEmpty() && t !in values) onChange(values + t)
        text = ""
        suggestions = emptyList()
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (values.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                values.forEach { v ->
                    Text(
                        "$v  ✕",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp))
                            .clickable { onChange(values - v) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
        OutlinedTextField(
            value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add(text) }),
        )
        val shown = suggestions.filter { it !in values }.take(MAX_SUGGESTIONS)
        // The sheet's scroll only brings the focused field into view; scroll again so the list
        // below it isn't left behind the keyboard.
        val bringIntoView = remember { BringIntoViewRequester() }
        LaunchedEffect(shown) {
            if (shown.isEmpty()) return@LaunchedEffect
            withFrameNanos { } // let the list be measured and placed first
            bringIntoView.bringIntoView()
        }
        if (shown.isNotEmpty() && text.trim().length >= MIN_SUGGEST_CHARS) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(bringIntoView)
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp)),
            ) {
                shown.forEach { s ->
                    Text(
                        s,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().clickable { add(s) }.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}
