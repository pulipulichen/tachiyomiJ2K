package eu.kanade.tachiyomi.util.lang

import android.app.Application
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

/**
 * Traditional/simplified search using the bundled OpenCC dictionaries.
 *
 * The dictionaries are distributed under Apache-2.0 and work on every Android
 * version supported by this app. No platform-specific ICU transliterators or
 * additional runtime dependencies are needed.
 */
object ChineseText {
    // Load only when the user actually searches for Chinese text.
    private val toSimplified by lazy { load("TSPhrases.txt", "TSCharacters.txt") }
    private val toTraditional by lazy { load("STPhrases.txt", "STCharacters.txt") }

    private class Node {
        val children = HashMap<Char, Node>()
        var replacement: String? = null
    }

    private class Dictionary {
        private val root = Node()

        fun add(
            key: String,
            value: String,
        ) {
            var node = root
            for (char in key) {
                node = node.children.getOrPut(char) { Node() }
            }
            node.replacement = value
        }

        fun convert(value: String): String {
            val result = StringBuilder(value.length)
            var index = 0
            while (index < value.length) {
                var node = root
                var matchLength = 0
                var matchValue: String? = null
                var position = index
                while (position < value.length) {
                    node = node.children[value[position]] ?: break
                    position++
                    if (node.replacement != null) {
                        matchLength = position - index
                        matchValue = node.replacement
                    }
                }
                if (matchValue != null) {
                    result.append(matchValue)
                    index += matchLength
                } else {
                    result.append(value[index])
                    index++
                }
            }
            return result.toString()
        }
    }

    private fun load(
        phrasesFile: String,
        charactersFile: String,
    ): Dictionary {
        val result = Dictionary()
        val assets = Injekt.get<Application>().assets

        // Characters are the fallback; longer phrase matches take precedence.
        for (name in listOf(charactersFile, phrasesFile)) {
            assets.open("opencc/$name").bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    if (!line.startsWith("#")) {
                        val delimiter = line.indexOf('\t')
                        if (delimiter > 0) {
                            val key = line.substring(0, delimiter)
                            // OpenCC can list alternate outputs: use the first.
                            val replacement = line.substring(delimiter + 1).substringBefore(' ')
                            if (replacement.isNotEmpty()) result.add(key, replacement)
                        }
                    }
                }
            }
        }
        return result
    }

    private fun containsHan(text: String): Boolean =
        text.any { it in '\u3400'..'\u9fff' || it in '\uf900'..'\ufaff' }

    fun simplified(value: String): String =
        if (containsHan(value)) toSimplified.convert(value) else value

    fun traditional(value: String): String =
        if (containsHan(value)) toTraditional.convert(value) else value

    fun containsEquivalent(
        text: String,
        search: String,
    ): Boolean {
        if (text.contains(search, ignoreCase = true)) return true
        if (!containsHan(search)) return false
        return simplified(text).contains(simplified(search), ignoreCase = true)
    }

    /**
     * Preserve the query for display/search history and include both scripts.
     */
    fun searchVariants(query: String): List<String> =
        if (query.isBlank() || !containsHan(query)) {
            listOf(query)
        } else {
            listOf(query, simplified(query), traditional(query))
                .distinctBy { it.lowercase(Locale.ROOT) }
        }
}
