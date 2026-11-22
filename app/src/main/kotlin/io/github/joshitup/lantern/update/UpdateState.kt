package io.github.joshitup.lantern.update

/** Where a download-and-install is. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data object Installing : UpdateState
    data object NeedsPermission : UpdateState
    data class Failed(val message: String) : UpdateState
    data object Done : UpdateState
}
