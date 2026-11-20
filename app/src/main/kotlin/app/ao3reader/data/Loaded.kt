package app.ao3reader.data

import app.ao3reader.ao3.net.FetchResult

sealed interface Loaded<out T> {
    data class Ok<T>(val value: T) : Loaded<T>
    data class Error(val result: FetchResult) : Loaded<Nothing>
}
