package io.github.joshitup.lantern.data

import io.github.joshitup.lantern.ao3.net.FetchResult

sealed interface Loaded<out T> {
    data class Ok<T>(val value: T) : Loaded<T>
    data class Error(val result: FetchResult) : Loaded<Nothing>
}
