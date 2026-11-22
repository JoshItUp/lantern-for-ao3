package io.github.joshitup.lantern.ui.common

import io.github.joshitup.lantern.ao3.net.FetchResult
import io.github.joshitup.lantern.data.ActionFailedException
import io.github.joshitup.lantern.data.ActionMayHaveTakenEffectException
import io.github.joshitup.lantern.data.ActionUnconfirmedException
import io.github.joshitup.lantern.data.Loaded
import io.github.joshitup.lantern.data.NotSignedInException

/** The account actions, as the UI names them. */
enum class WorkAction { KUDOS, BOOKMARK, LATER, SUBSCRIBE, COMMENT }

/** Shown when a comment post may or may not have gone through (also on a reopened, guarded sheet). */
const val COMMENT_MAY_NOT_HAVE_POSTED = "Your comment may not have posted — check the work before trying again."

/** One line of feedback for a snackbar; [toAccount] adds an action that opens the Account screen. */
data class ActionMessage(val text: String, val toAccount: Boolean = false)

/**
 * Plain feedback for the result of [action]. A toggle's Ok value is the state it ended in.
 * Never a dialog: the screen shows it in a snackbar.
 */
fun actionFeedback(action: WorkAction, result: Loaded<*>): ActionMessage = when (result) {
    is Loaded.Ok -> ActionMessage(
        when (action) {
            // Ok(false): AO3 already showed kudos given, nothing was sent.
            WorkAction.KUDOS -> if (result.value == false) "Kudos already given" else "Kudos sent"
            WorkAction.BOOKMARK -> "Bookmarked"
            WorkAction.LATER -> if (result.value == true) "Saved for later" else "Removed from Later"
            WorkAction.SUBSCRIBE -> if (result.value == true) "Subscribed" else "Unsubscribed"
            WorkAction.COMMENT -> "Comment posted"
        }
    )
    is Loaded.Error -> errorFeedback(action, result.result)
}

private fun errorFeedback(action: WorkAction, r: FetchResult): ActionMessage = when (r) {
    FetchResult.Blocked -> ActionMessage("AO3 is blocking requests right now.")
    is FetchResult.RateLimited -> ActionMessage("AO3 asked us to slow down. Try again in ${r.retryAfterSec} s.")
    is FetchResult.Failed -> when (r.cause) {
        is ActionUnconfirmedException -> ActionMessage(
            if (action == WorkAction.COMMENT) COMMENT_MAY_NOT_HAVE_POSTED
            else "Couldn't confirm with AO3 — check the work page."
        )
        is ActionMayHaveTakenEffectException ->
            ActionMessage("The connection dropped — this may have gone through. Check before retrying.")
        is NotSignedInException -> ActionMessage("Sign in again to do that.", toAccount = true)
        is ActionFailedException -> ActionMessage("AO3 didn't accept that. Try again from the work page.")
        else -> ActionMessage("Couldn't reach AO3.")
    }
    FetchResult.NotFound, is FetchResult.Ok -> ActionMessage("Couldn't reach AO3.")
}
