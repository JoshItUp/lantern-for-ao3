package io.github.joshitup.lantern.ui.common

import io.github.joshitup.lantern.ao3.net.FetchResult
import io.github.joshitup.lantern.data.ActionFailedException
import io.github.joshitup.lantern.data.ActionMayHaveTakenEffectException
import io.github.joshitup.lantern.data.ActionUnconfirmedException
import io.github.joshitup.lantern.data.Loaded
import io.github.joshitup.lantern.data.NotSignedInException
import org.junit.Assert.assertEquals
import org.junit.Test

class ActionFeedbackTest {
    private fun err(r: FetchResult) = Loaded.Error(r)
    private fun failed(e: Throwable?) = err(FetchResult.Failed(e))

    @Test fun `confirmations name what happened`() {
        assertEquals("Kudos sent", actionFeedback(WorkAction.KUDOS, Loaded.Ok(Unit)).text)
        assertEquals("Kudos sent", actionFeedback(WorkAction.KUDOS, Loaded.Ok(true)).text)
        assertEquals("Kudos already given", actionFeedback(WorkAction.KUDOS, Loaded.Ok(false)).text)
        assertEquals("Bookmarked", actionFeedback(WorkAction.BOOKMARK, Loaded.Ok(Unit)).text)
        assertEquals("Saved for later", actionFeedback(WorkAction.LATER, Loaded.Ok(true)).text)
        assertEquals("Removed from Later", actionFeedback(WorkAction.LATER, Loaded.Ok(false)).text)
        assertEquals("Subscribed", actionFeedback(WorkAction.SUBSCRIBE, Loaded.Ok(true)).text)
        assertEquals("Unsubscribed", actionFeedback(WorkAction.SUBSCRIBE, Loaded.Ok(false)).text)
        assertEquals("Comment posted", actionFeedback(WorkAction.COMMENT, Loaded.Ok(Unit)).text)
    }

    @Test fun `errors say plainly what to do`() {
        assertEquals("Couldn't confirm with AO3 — check the work page.",
            actionFeedback(WorkAction.SUBSCRIBE, failed(ActionUnconfirmedException("x"))).text)
        assertEquals("Your comment may not have posted — check the work before trying again.",
            actionFeedback(WorkAction.COMMENT, failed(ActionUnconfirmedException("x"))).text)
        assertEquals("The connection dropped — this may have gone through. Check before retrying.",
            actionFeedback(WorkAction.COMMENT, failed(ActionMayHaveTakenEffectException(FetchResult.Failed(null)))).text)
        assertEquals("AO3 is blocking requests right now.", actionFeedback(WorkAction.KUDOS, err(FetchResult.Blocked)).text)
        assertEquals("AO3 asked us to slow down. Try again in 12 s.", actionFeedback(WorkAction.KUDOS, err(FetchResult.RateLimited(12))).text)
        assertEquals(ActionMessage("Sign in again to do that.", toAccount = true), actionFeedback(WorkAction.LATER, failed(NotSignedInException())))
        assertEquals("AO3 didn't accept that. Try again from the work page.", actionFeedback(WorkAction.KUDOS, failed(ActionFailedException("no"))).text)
        assertEquals("Couldn't reach AO3.", actionFeedback(WorkAction.KUDOS, failed(java.io.IOException())).text)
        assertEquals("Couldn't reach AO3.", actionFeedback(WorkAction.KUDOS, err(FetchResult.NotFound)).text)
    }
}
