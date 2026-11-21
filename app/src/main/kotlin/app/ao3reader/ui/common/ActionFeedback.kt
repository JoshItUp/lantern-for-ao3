package app.ao3reader.ui.common

import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.data.ActionFailedException
import app.ao3reader.data.ActionMayHaveTakenEffectException
import app.ao3reader.data.ActionUnconfirmedException
import app.ao3reader.data.Loaded
import app.ao3reader.data.NotSignedInException

/** The account actions, as the UI names them. */
enum class WorkAction { KUDOS, BOOKMARK, LATER, SUBSCRIBE, COMMENT }

/** One line of feedback for a snackbar; [toAccount] adds an action that opens the Account screen. */
data class ActionMessage(val text: String, val toAccount: Boolean = false)

/**
 * Plain feedback for the result of [action]. A toggle's Ok value is the state it ended in.
 * Never a dialog: the screen shows it in a snackbar.
 */
fun actionFeedback(action: WorkAction, result: Loaded<*>): ActionMessage = when (result) {
    is Loaded.Ok -> ActionMessage(
        when (action) {
            WorkAction.KUDOS -> "Kudos sent"
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
            if (action == WorkAction.COMMENT) "Your comment may not have posted — check the work before trying again."
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
