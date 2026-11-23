package io.github.joshitup.lantern.update

import android.content.Intent

/** Where a download-and-install is. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data object Installing : UpdateState
    /** Android wants the user to confirm; the UI launches [intent] on resume if the receiver's launch was blocked. */
    data class AwaitingConfirmation(val intent: Intent) : UpdateState
    data object NeedsPermission : UpdateState
    data class Failed(val message: String) : UpdateState
}
