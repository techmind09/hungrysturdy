package com.hungrysturdy

import com.lagradost.cloudstream3.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AllSiteHubProvider : MainAPI() {
    override var mainUrl = "https://www.allsitehub.site"
    override var name = "AllSiteHub"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private data class SiteItem(
        val title: String,
        val url: String,
        val domain: String,
        val logoUrl: String,
        val badge: String?,
        val category: String
    )

    private data class CategorySection(
        val id: String,
        val name: String,
        val items: List<SiteItem>
    )

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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val scrapedSections = parseDocument(document).toMutableList()
        
        val finalSections = injectCustomSites(scrapedSections)
        
        val homePageLists = finalSections.map { section ->
            val searchResponses = section.items.map { item ->
                newMovieSearchResponse(item.title, item.url, TvType.Movie) {
                    this.posterUrl = item.logoUrl
                }
            }
            HomePageList(section.name, searchResponses)
        }
        
        return newHomePageResponse(homePageLists, hasNext = false)
    }

    private fun parseDocument(document: Document): List<CategorySection> {
        val sections = mutableListOf<CategorySection>()
        val sectionElements = document.select("section.category-section-anchor")

        for (section in sectionElements) {
            val sectionId = section.id()
            val categoryName = section.selectFirst("h2")?.text()?.trim() ?: "Unknown"

            val siteItems = section.select("div.sites-grid > a.sc-card").mapNotNull { card ->
                parseCard(card, categoryName)
            }

            if (siteItems.isNotEmpty()) {
                sections.add(CategorySection(sectionId, categoryName, siteItems))
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

        return SiteItem(title, href, domainText, logoUrl, badge, categoryName)
    }

    private fun injectCustomSites(scrapedSections: MutableList<CategorySection>): List<CategorySection> {
        val existingUrls = scrapedSections.flatMap { it.items }.map { it.url }.toSet()

        for (customSite in customDirectoryList) {
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
}
