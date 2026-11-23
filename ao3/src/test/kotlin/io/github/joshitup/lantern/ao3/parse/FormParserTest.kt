package io.github.joshitup.lantern.ao3.parse

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class FormParserTest {
    private val base = "https://archiveofourown.org/works/1".toHttpUrl()
    private fun form(inner: String, attrs: String = "action=\"/x/y\" method=\"post\"") =
        FormParser.find("<html><body><form id=\"f\" $attrs>$inner</form></body></html>", base, "#f")!!

    @Test fun `keeps hidden fields and token, excludes submit`() {
        val f = form("""<input type="hidden" name="authenticity_token" value="abc"><input type="text" name="q" value="v"><textarea name="t">body</textarea><input type="submit" name="commit" value="Go"><button name="b">x</button>""")
        assertEquals(listOf("authenticity_token" to "abc", "q" to "v", "t" to "body"), f.fields)
        assertEquals("post", f.method)
    }

    @Test fun `hidden _method delete gives delete`() {
        val f = form("""<input type="hidden" name="_method" value="delete">""")
        assertEquals("delete", f.method)
    }

    @Test fun `checkboxes only when checked, radios likewise`() {
        val f = form("""<input type="hidden" name="c" value="0"><input type="checkbox" name="c" value="1"><input type="checkbox" name="d" value="1" checked><input type="radio" name="r" value="a"><input type="radio" name="r" value="b" checked>""")
        assertEquals(listOf("c" to "0", "d" to "1", "r" to "b"), f.fields)
    }

    @Test fun `select uses selected else first option`() {
        val f = form("""<select name="a"><option value="1">one</option><option value="2">two</option></select><select name="b"><option value="1">one</option><option value="2" selected>two</option></select>""")
        assertEquals(listOf("a" to "1", "b" to "2"), f.fields)
    }

    @Test fun `disabled and unnamed fields skipped`() {
        val f = form("""<input type="text" name="a" value="1" disabled><input type="text" value="2"><input type="text" name="b" value="3">""")
        assertEquals(listOf("b" to "3"), f.fields)
    }

    @Test fun `relative action resolved, get default, missing form null`() {
        val f = form("", "action=\"/x/y\"")
        assertEquals("https://archiveofourown.org/x/y".toHttpUrl(), f.action)
        assertEquals("get", f.method)
        assertNull(FormParser.find("<p>none</p>", base, "form"))
    }

    @Test fun `delete form keeps _method once and last wins`() {
        val f = form("""<input type="hidden" name="_method" value="delete">""")
        assertEquals("delete", f.method)
        assertEquals(1, f.fields.count { it == ("_method" to "delete") })
        val g = form("""<input type="hidden" name="_method" value="put"><input type="hidden" name="_method" value="delete">""")
        assertEquals("delete", g.method)
    }

    @Test fun `multiple select emits selected only, skips disabled`() {
        val f = form("""<select name="m" multiple><option value="1" selected>a</option><option value="2">b</option><option value="3" selected disabled>c</option><option value="4" selected>d</option></select><select name="n" multiple><option value="1">a</option></select><select name="s"><option value="x" disabled>x</option><option value="y">y</option></select>""")
        assertEquals(listOf("m" to "1", "m" to "4", "s" to "y"), f.fields)
    }
    // Real signed-in captures (anonymised: owner, tokens "x", pseud id 1000001).
    private fun res(n: String) = javaClass.getResource("/fixtures/real_signed_in/$n")!!.readText()
    private val plainUrl = "https://archiveofourown.org/works/1000128/chapters/2000169".toHttpUrl()
    private val bmUrl = "https://archiveofourown.org/works/1000112/chapters/2000104".toHttpUrl()

    @Test fun `real kudos form`() {
        val f = FormParser.find(res("chapter_plain.html"), plainUrl, "form#new_kudo")!!
        assertEquals("https://archiveofourown.org/kudos".toHttpUrl(), f.action)
        assertEquals("post", f.method)
        assertEquals(listOf("authenticity_token" to "x", "kudo[commentable_id]" to "1000128", "kudo[commentable_type]" to "Work"), f.fields)
    }

    @Test fun `real new bookmark form posts to the work, existing one puts to the bookmark`() {
        val create = FormParser.find(res("chapter_plain.html"), plainUrl, "#bookmark-form form")!!
        assertEquals("https://archiveofourown.org/works/1000128/bookmarks".toHttpUrl(), create.action)
        assertEquals("post", create.method)
        assertEquals(listOf("authenticity_token" to "x", "bookmark[pseud_id]" to "1000001", "bookmark[bookmarker_notes]" to "",
            "bookmark[tag_string]" to "", "bookmark[collection_names]" to "", "bookmark[private]" to "0", "bookmark[rec]" to "0"), create.fields)

        val update = FormParser.find(res("chapter_bookmarked.html"), bmUrl, "#bookmark-form form")!!
        assertEquals("https://archiveofourown.org/bookmarks/3000104".toHttpUrl(), update.action)
        assertEquals("put", update.method)
        assertEquals(listOf("_method" to "put", "authenticity_token" to "x", "bookmark[pseud_id]" to "1000001", "bookmark[bookmarker_notes]" to "",
            "bookmark[tag_string]" to "", "bookmark[collection_names]" to "", "bookmark[private]" to "0", "bookmark[private]" to "1",
            "bookmark[rec]" to "0"), update.fields)
    }

    @Test fun `real mark for later form is a patch button_to`() {
        val f = FormParser.find(res("chapter_plain.html"), plainUrl, "li.mark form")!!
        assertEquals("https://archiveofourown.org/works/1000128/mark_for_later".toHttpUrl(), f.action)
        assertEquals("patch", f.method)
        assertEquals(listOf("_method" to "patch", "authenticity_token" to "x"), f.fields)
    }

    @Test fun `real subscribe form`() {
        val f = FormParser.find(res("chapter_plain.html"), plainUrl, "li.subscribe form")!!
        assertEquals("https://archiveofourown.org/users/owner/subscriptions".toHttpUrl(), f.action)
        assertEquals("post", f.method)
        assertEquals(listOf("authenticity_token" to "x", "subscription[subscribable_id]" to "1000128",
            "subscription[subscribable_type]" to "Work"), f.fields)
    }

    @Test fun `real comment form`() {
        val f = FormParser.find(res("chapter_plain.html"), plainUrl, "form#comment_for_2000169")!!
        assertEquals("https://archiveofourown.org/chapters/2000169/comments".toHttpUrl(), f.action)
        assertEquals("post", f.method)
        assertEquals(listOf("authenticity_token" to "x", "comment[pseud_id]" to "1000001", "comment[comment_content]" to "",
            "controller_name" to "chapters"), f.fields)
    }

    @Test fun `real unsubscribe and delete-from-history forms are deletes`() {
        val base = "https://archiveofourown.org/users/owner/subscriptions".toHttpUrl()
        val u = FormParser.find(res("subscriptions.html"), base, "dl.subscription form.edit_subscription")!!
        assertEquals("https://archiveofourown.org/users/owner/subscriptions/1".toHttpUrl(), u.action)
        assertEquals("delete", u.method)
        assertEquals(listOf("_method" to "delete", "authenticity_token" to "x"), u.fields)

        val h = FormParser.find(res("readings_p1.html"), base, "li.reading form.ajax-remove")!!
        assertEquals("https://archiveofourown.org/users/owner/readings/6000107".toHttpUrl(), h.action)
        assertEquals("delete", h.method)
        assertEquals(listOf("_method" to "delete", "authenticity_token" to "x", "reading" to "6000107"), h.fields)
    }
}
