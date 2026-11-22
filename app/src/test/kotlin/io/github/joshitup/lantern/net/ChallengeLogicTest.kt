package io.github.joshitup.lantern.net

import org.junit.Assert.*
import org.junit.Test

class ChallengeLogicTest {
    @Test fun `body text just a moment is not a challenge`() =
        assertFalse(isChallengePage("Chapter 1", "<p>Wait, just a moment, he said.</p>"))

    @Test fun `title just a moment is a challenge`() =
        assertTrue(isChallengePage("Just a moment...", "<html></html>"))

    @Test fun `robot body is a challenge`() =
        assertTrue(isChallengePage("AO3", "our systems think you may be a robot"))

    @Test fun `robot sentence is matched case-insensitively`() =
        assertTrue(isChallengePage("AO3", "<p>Our systems think you may be a Robot.</p>"))

    @Test fun `chapter quoting the robot sentence is not a challenge`() {
        val chapter = """<html><head><title>Chapter 3</title></head><body><div id="workskin"><div class="userstuff">
            <p>The screen said: our systems think you may be a robot.</p></div></div></body></html>"""
        assertFalse(isChallengePage("Chapter 3", chapter))
    }

    @Test fun `partial phrase is not a challenge`() =
        assertFalse(isChallengePage("Chapter 1", "<p>I think you may be a robot, she said.</p>"))

    @Test fun `challenge window allows ao3 and cloudflare over https only`() {
        assertTrue(isAllowedChallengeUrl("https://archiveofourown.org/works/1"))
        assertTrue(isAllowedChallengeUrl("https://www.archiveofourown.org/"))
        assertTrue(isAllowedChallengeUrl("https://ARCHIVEOFOUROWN.ORG/x"))
        assertTrue(isAllowedChallengeUrl("https://challenges.cloudflare.com/cdn-cgi/challenge-platform/h/b"))
        assertFalse(isAllowedChallengeUrl("http://archiveofourown.org/"))
        assertFalse(isAllowedChallengeUrl("https://archiveofourown.org.evil.com/"))
        assertFalse(isAllowedChallengeUrl("https://evilarchiveofourown.org/"))
        assertFalse(isAllowedChallengeUrl("https://example.com/?archiveofourown.org"))
        assertFalse(isAllowedChallengeUrl("https://cloudflare.com/"))
        assertFalse(isAllowedChallengeUrl("intent://archiveofourown.org#Intent;scheme=https;end"))
        assertFalse(isAllowedChallengeUrl("file:///sdcard/x.html"))
        assertFalse(isAllowedChallengeUrl("javascript:alert(1)"))
        assertFalse(isAllowedChallengeUrl(""))
        assertFalse(isAllowedChallengeUrl(null))
    }

    @Test fun `json quoted result decodes`() {
        val raw = "\"[\\\"Just a moment...\\\",\\\"<p class=\\\\\\\"x\\\\\\\">hi\\n</p>\\\"]\""
        val (t, h) = decodeTitleAndHtml(raw)!!
        assertEquals("Just a moment...", t)
        assertEquals("<p class=\"x\">hi\n</p>", h)
        assertNull(decodeTitleAndHtml("null"))
    }

    @Test fun `stale token does not complete current request`() {
        val (t1, d1) = ChallengeRegistry.begin()
        ChallengeRegistry.end(t1)
        val (t2, d2) = ChallengeRegistry.begin()
        ChallengeRegistry.complete(t1, false)
        assertFalse(d2.isCompleted)
        assertFalse(ChallengeRegistry.isCurrent(t1))
        assertTrue(ChallengeRegistry.isCurrent(t2))
        ChallengeRegistry.complete(t2, true)
        assertTrue(d2.isCompleted)
        assertFalse(d1.isCompleted)
    }
}
