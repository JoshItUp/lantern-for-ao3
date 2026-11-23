package io.github.joshitup.lantern.update

import kotlinx.serialization.Serializable

/** A usable GitHub release: a stable `vX.Y.Z` tag with an APK asset. Persisted as JSON by the update state. */
@Serializable
data class ReleaseInfo(
    val version: ReleaseVersion,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val htmlUrl: String,
)

sealed interface CheckResult {
    data class Available(val release: ReleaseInfo) : CheckResult
    data object UpToDate : CheckResult
    data class Failed(val reason: String) : CheckResult
}

/** Where releases come from; [UpdateClient] is the real one, tests use fakes. */
interface ReleaseSource {
    suspend fun latest(): ReleaseInfo?
    suspend fun byTag(tag: String): ReleaseInfo?
}
