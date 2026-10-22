package com.curveon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every English text is in every language the rider can pick, with the same placeholders, so
 * switching language can neither show a missing text nor crash on a format that doesn't match its
 * arguments.
 */
class TranslationsTest {

    private fun load(dir: String): Map<String, List<String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$dir/strings.xml"))
        val out = LinkedHashMap<String, List<String>>()
        val strings = doc.getElementsByTagName("string")
        for (i in 0 until strings.length) {
            val e = strings.item(i) as org.w3c.dom.Element
            if (e.getAttribute("translatable") != "false") out[e.getAttribute("name")] = listOf(e.textContent)
        }
        val plurals = doc.getElementsByTagName("plurals")
        for (i in 0 until plurals.length) {
            val e = plurals.item(i) as org.w3c.dom.Element
            val items = e.getElementsByTagName("item")
            out["plurals/" + e.getAttribute("name")] = (0 until items.length).map { items.item(it).textContent }
        }
        return out
    }

    private val placeholder = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdf]""")

    private fun placeholders(text: String) = placeholder.findAll(text).map { it.value.replace(Regex("""\.\d+|0\d"""), "") }.toSortedSet()

    private val languages = AppLanguage.entries.filter { it != AppLanguage.ENGLISH }.map { it.tag }

    @Test
    fun `every language has every english text`() {
        val en = load("values")
        languages.forEach { assertEquals(it, en.keys.sorted(), load("values-$it").keys.sorted()) }
    }

    @Test
    fun `placeholders match between languages`() {
        val en = load("values")
        for (lang in languages) {
            val tr = load("values-$lang")
            for ((name, texts) in en) {
                val want = placeholders(texts.last())
                tr[name]!!.forEach { t ->
                    // A "one" may leave the count out ("Inkluderer en ferje").
                    if (name.startsWith("plurals/")) assertTrue("$lang $name", placeholders(t).all { it in want })
                    else assertEquals("$lang $name", want, placeholders(t))
                }
            }
        }
    }
}
