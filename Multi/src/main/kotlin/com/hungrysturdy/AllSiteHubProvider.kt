package com.hungrysturdy

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

data class SiteItem(
    val title: String,
    val url: String,
    val domain: String,
    val logoUrl: String,
    val badge: String?,
    val category: String
)

data class CategorySection(
    val id: String,
    val name: String,
    val items: List<SiteItem>
)

class AllSiteHubProvider(
    private val mainUrl: String = "https://www.allsitehub.site"
) {

    // Tumhari custom sites ki list yahan hai
    private val customDirectoryList = listOf(
        SiteItem("Arrow TV", "https://arrowtv.net/", "arrowtv.net", "", "FEATURED", "Movies & Shows"),
        SiteItem("RED FLIX", "https://redflix.club/", "redflix.club", "", "TRUSTED", "Movies & Shows"),
        SiteItem("1FLEX", "https://www.1flex.org/", "1flex.org", "", "TRUSTED", "Movies & Shows"),
        SiteItem("YOUSHOWS", "https://youshows.org/", "youshows.org", "", "NEW", "Movies & Shows"),
        SiteItem("PrimeShows", "https://primeshows.org/", "primeshows.org", "", "", "Movies & Shows"),
        SiteItem("ANIME TV", "https://animetvplus.xyz/", "animetvplus.xyz", "", "", "Anime"),
        SiteItem("ENMA", "https://www.enma.lol/", "enma.lol", "", "TRUSTED", "Anime"),
        SiteItem("AnimeRulz", "https://animerulz.co.in/", "animerulz.co.in", "", "NEW", "Anime"),
        SiteItem("Netflix India", "https://www.netflix.com/in/", "netflix.com", "https://images.ctfassets.net/y2ske730sjqp/1aONibCke6niZhgPxuiilC/2c401b05a07288746ddf3bd3943fbc76/BrandAssets_Logos_01-Wordmark.jpg", "PAID", "Movies & Shows"),
        SiteItem("FilmyFly", "https://filmyfly.info/", "filmyfly.info", "", "NEW", "Movies & Shows")
    )

    fun parseDirectory(html: String): List<CategorySection> {
        val document: Document = Jsoup.parse(html, mainUrl)
        val scrapedSections = parseDocument(document).toMutableList()
        
        return injectCustomSites(scrapedSections)
    }

    private fun parseDocument(document: Document): List<CategorySection> {
        val sections = mutableListOf<CategorySection>()
        
        // HTML se section.category-section-anchor ko select karta hai[span_1](start_span)[span_1](end_span)
        val sectionElements = document.select("section.category-section-anchor")

        for (section in sectionElements) {
            val sectionId = section.id()
            val categoryName = section.selectFirst("h2")?.text()?.trim() ?: "Unknown"

            // HTML se div.sites-grid > a.sc-card ko select karta hai[span_2](start_span)[span_2](end_span)
            val siteItems = section.select("div.sites-grid > a.sc-card").mapNotNull { card ->
                parseCard(card, categoryName)
            }

            if (siteItems.isNotEmpty()) {
                sections.add(
                    CategorySection(
                        id = sectionId,
                        name = categoryName,
                        items = siteItems
                    )
                )
            }
        }

        return sections
    }

    private fun parseCard(card: Element, categoryName: String): SiteItem? {
        val href = card.attr("href").trim().ifEmpty { return null }
        
        val rawAria = card.attr("aria-label").removePrefix("Visit ").trim()
        val imgElement = card.selectFirst("img.sc-logo-img")
        val rawAlt = imgElement?.attr("alt")?.removeSuffix(" logo")?.trim()
        val domainText = card.selectFirst("div.sc-domain-left > span")?.text()?.trim() ?: ""

        val title = when {
            rawAria.isNotEmpty() -> rawAria
            !rawAlt.isNullOrEmpty() -> rawAlt
            else -> domainText
        }

        val rawLogo = imgElement?.attr("src")?.trim() ?: ""
        val logoUrl = when {
            rawLogo.startsWith("http") || rawLogo.startsWith("data:") -> rawLogo
            rawLogo.startsWith("/") -> "$mainUrl$rawLogo"
            rawLogo.isNotEmpty() -> "$mainUrl/$rawLogo"
            else -> ""
        }

        val badge = card.selectFirst("span.sc-badge")?.text()?.trim()?.takeIf { it.isNotEmpty() }

        return SiteItem(
            title = title,
            url = href,
            domain = domainText,
            logoUrl = logoUrl,
            badge = badge,
            category = categoryName
        )
    }

    private fun injectCustomSites(scrapedSections: MutableList<CategorySection>): List<CategorySection> {
        val existingUrls = scrapedSections.flatMap { it.items }.map { it.url }.toSet()

        for (customSite in customDirectoryList) {
            // Check karega ki URL already exist karta hai ya nahi
            if (!existingUrls.contains(customSite.url)) {
                
                val sectionIndex = scrapedSections.indexOfFirst { it.name == customSite.category }
                
                if (sectionIndex != -1) {
                    val currentSection = scrapedSections[sectionIndex]
                    val updatedItems = currentSection.items.toMutableList().apply { add(customSite) }
                    scrapedSections[sectionIndex] = currentSection.copy(items = updatedItems)
                } else {
                    scrapedSections.add(
                        CategorySection(
                            id = "cat-${customSite.category.lowercase().replace(" ", "-")}",
                            name = customSite.category,
                            items = listOf(customSite)
                        )
                    )
                }
            }
        }
        return scrapedSections
    }

    fun searchSites(sections: List<CategorySection>, query: String): List<SiteItem> {
        val cleanQuery = query.trim().lowercase()
        if (cleanQuery.isEmpty()) return sections.flatMap { it.items }

        return sections.flatMap { it.items }.filter { item ->
            item.title.lowercase().contains(cleanQuery) ||
            item.domain.lowercase().contains(cleanQuery) ||
            item.category.lowercase().contains(cleanQuery)
        }
    }
}
