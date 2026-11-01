package eu.kanade.tachiyomi.ui.source

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.util.lang.ChineseText
import kotlinx.coroutines.CancellationException

/**
 * Some Chinese catalogue sites only search in one script. Query each distinct
 * spelling and merge by source-local manga URL so both forms work in-app.
 *
 * Do not transform searches for Japanese/other-language sources. Preserve the
 * original query as the first request and do not touch the source's filters.
 */
suspend fun CatalogueSource.getSearchMangaWithChineseVariants(
    page: Int,
    query: String,
    filters: FilterList,
): MangasPage {
    val variants =
        if (lang.startsWith("zh", ignoreCase = true) || lang.equals("all", ignoreCase = true)) {
            ChineseText.searchVariants(query)
        } else {
            listOf(query)
        }

    if (variants.size == 1) return getSearchManga(page, query, filters)

    val pages = mutableListOf<MangasPage>()
    var firstError: Exception? = null
    for (variant in variants) {
        try {
            pages += getSearchManga(page, variant, filters)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (firstError == null) firstError = error
        }
    }
    if (pages.isEmpty()) throw (firstError ?: IllegalStateException("No search response"))

    val merged = pages.flatMap { it.mangas }.distinctBy { it.url }
    return MangasPage(merged, pages.any { it.hasNextPage })
}
