package eu.kanade.tachiyomi.ui.source.browse

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.ui.source.getSearchMangaWithChineseVariants

open class BrowseSourcePager(
    val source: CatalogueSource,
    val query: String,
    val filters: FilterList,
) : Pager() {
    override suspend fun requestNextPage() {
        val page = currentPage

        val mangasPage =
            if (query.isBlank() && filters.isEmpty()) {
                source.getPopularManga(page)
            } else {
                source.getSearchMangaWithChineseVariants(page, query, filters)
            }

        if (mangasPage.mangas.isNotEmpty()) {
            onPageReceived(mangasPage)
        } else {
            throw NoResultsException()
        }
    }
}
