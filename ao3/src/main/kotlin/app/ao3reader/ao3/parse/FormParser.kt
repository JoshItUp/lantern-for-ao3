package app.ao3reader.ao3.parse

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

data class Ao3Form(val action: HttpUrl, val method: String, val fields: List<Pair<String, String>>)

object FormParser {
    private val skipTypes = setOf("submit", "button", "image", "reset", "file")

    fun parse(form: Element, baseUrl: HttpUrl): Ao3Form {
        val fields = ArrayList<Pair<String, String>>()
        for (el in form.select("input, textarea, select")) {
            val name = el.attr("name")
            if (name.isEmpty() || el.hasAttr("disabled")) continue
            when (el.tagName()) {
                "textarea" -> fields.add(name to el.wholeText().removePrefix("\n"))
                "select" -> {
                    val opts = el.select("option")
                    val chosen = opts.firstOrNull { o: Element -> o.hasAttr("selected") } ?: opts.firstOrNull()
                    if (chosen != null) {
                        fields.add(name to (if (chosen.hasAttr("value")) chosen.attr("value") else chosen.text()))
                    }
                }
                else -> {
                    val type = el.attr("type").lowercase()
                    if (type in skipTypes) continue
                    if ((type == "checkbox" || type == "radio") && !el.hasAttr("checked")) continue
                    val value = if ((type == "checkbox" || type == "radio") && !el.hasAttr("value")) "on" else el.attr("value")
                    fields.add(name to value)
                }
            }
        }
        val override = fields.firstOrNull { it.first == "_method" }?.second?.lowercase()
        val declared = form.attr("method").lowercase().ifEmpty { "get" }
        val method = if (declared == "post" && !override.isNullOrEmpty()) override else declared
        val actionAttr = form.attr("action")
        val action = (if (actionAttr.isEmpty()) baseUrl else baseUrl.resolve(actionAttr)) ?: baseUrl
        return Ao3Form(action, method, fields)
    }

    fun find(html: String, baseUrl: HttpUrl, selector: String): Ao3Form? {
        val el = Jsoup.parse(html).selectFirst(selector) ?: return null
        return parse(el, baseUrl)
    }
}
