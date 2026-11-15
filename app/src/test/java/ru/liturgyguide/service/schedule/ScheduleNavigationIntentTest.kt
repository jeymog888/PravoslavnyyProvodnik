package ru.liturgyguide.service.schedule

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.util.Random

/** Controlled HTML/transport and recording extractor stubs. No live HTTP, OCR or image-content oracle. */
class ScheduleNavigationIntentTest {
    private val root = "https://guided.example.org/"
    private val target = root + "panel"
    private val now = Instant.parse("2026-11-14T12:00:00Z")
    private val today = LocalDate.parse("2026-11-14")
    private val clean = ScheduleParseResult(ScheduleParseDisposition.UNSUPPORTED)
    private fun parish(site: String = root, id: String = "osm:node:42") = Parish(id = id,
        name = "Церковь Николая Чудотворца", shortName = "Никольский храм", city = "Москва", address = "Первая улица, 1",
        phone = "", website = site, timezone = "Europe/Moscow", sourceUrl = "https://www.openstreetmap.org/node/42",
        scheduleSource = "Данные OpenStreetMap", scheduleValidFrom = "", scheduleValidUntil = "", schedule = emptyList(),
        scheduleCoverage = "", scheduleNote = "")
    private fun page(content: String, area: String = "main", title: String = "Храм") =
        "<html><head><title>$title</title></head><body><$area>$content</$area></body></html>"
    private fun response(url: String, body: String, type: String = "text/html; charset=UTF-8") =
        ScheduleResponse(url, body.toByteArray(Charsets.UTF_8), type)
    private fun baseline(response: ScheduleResponse) = response.html()?.let(ScheduleDocumentLinks::find).orEmpty()
    private fun parent(label: String = "Расписание", href: String = target, area: String = "main") =
        response(root, page("<a href='$href'>$label</a>", area))
    private fun issue(parent: ScheduleResponse, attempt: Any, selected: Parish = parish(), approvedRoot: String = root,
        parsed: ScheduleParseResult = clean) = ScheduleNavigationIntent.issue(selected, approvedRoot, attempt,
        parent, parsed, baseline(parent))
    private fun discovered(child: ScheduleResponse, parent: ScheduleResponse = parent(), selected: Parish = parish(),
        approvedRoot: String = root, parsed: ScheduleParseResult = clean): List<ScheduleDocumentLink> {
        val attempt = Any()
        val old = baseline(parent)
        val intended = old.single { !it.document }
        val proof = requireNotNull(issue(parent, attempt, selected, approvedRoot)[intended.url])
        return proof.discover(selected, approvedRoot, attempt, intended, child, parsed, baseline(child))
    }
    private fun image(path: String = "/opaque.png", more: String = "") =
        "<img src='$path' width='700' height='1000' alt='' $more>"
    private fun prefix(old: List<ScheduleDocumentLink>, result: List<ScheduleDocumentLink>) {
        assertEquals(old, result.take(old.size)); assertTrue(result.size <= 12)
    }

    private class Cache : ScheduleCacheStore {
        val values = mutableMapOf<String, String>()
        val safety = mutableListOf<String>()
        var writes = 0
        override fun read(parishId: String) = values[parishId]
        override fun write(parishId: String, value: String) { writes++; values[parishId] = value }
        override fun writeSafety(parishId: String, value: String) { safety += value; write(parishId, value) }
    }
    private inner class Script {
        val calls = mutableListOf<String>()
        val extracted = mutableListOf<String>()
        val services = root + "services"
        val finalTarget = target + "/"
        val media = root + "opaque.png"
        val shops = (1..3).map { root + "old$it.png" }
        var oldCount = 2
        var issuerLabel = "Расписание"
        var rootBody: String? = null
        var targetBody: String? = null
        var getBefore: suspend (String) -> Unit = {}
        var extractBefore: suspend (ScheduleResponse) -> Unit = {}
        var document: (ScheduleResponse) -> ScheduleDocument? = { validDocument(it.url) }
        fun defaultRoot() = page("<a href='$services'>Богослужения</a>")
        fun servicesBody() = page("<article><h1>Богослужения</h1><a href='$target'>$issuerLabel</a>" +
            shops.take(oldCount).joinToString("") { "<a href='$it'>Изображение</a>" } + "</article>")
        val transport = ScheduleTransport { url ->
            calls += url; getBefore(url)
            when (url) {
                root -> response(root, rootBody ?: defaultRoot())
                services -> response(services, servicesBody())
                target -> response(finalTarget, targetBody ?: page("<article><h1></h1>${image()}</article>"))
                media -> ScheduleResponse(url, byteArrayOf(1, 2, 3), "image/png")
                in shops -> ScheduleResponse(url, byteArrayOf(3, 2, 1), "image/png")
                else -> throw IOException("Unmapped controlled unit URL")
            }
        }
        val extractor = ScheduleDocumentExtractor { result ->
            extracted += result.url; extractBefore(result)
            if (result.url in shops) ScheduleDocument(result.url, "image", listOf(ScheduleDocumentPage(
                listOf(ScheduleDocumentLine("Церковная лавка"), ScheduleDocumentLine("Открыта ежедневно с 8:00 до 18:00")))))
            else document(result)
        }
        fun repo(cache: Cache = Cache()) = InternetScheduleRepository(transport, cache, extractor,
            siteResolver = ScheduleSiteResolver { null })
    }
    private fun validDocument(url: String) = ScheduleDocument(url, "image", listOf(ScheduleDocumentPage(
        listOf(ScheduleDocumentLine("2026-11-15 09:00 Литургия")))))

    @Test fun positiveGenericLabelsAddOnlyDirectBinaryCandidatesAndActualTargetProvenance() {
        val random = Random(72391)
        for (label in listOf("Расписание", "  РАСПИСАНИЕ\u00a0БОГОСЛУЖЕНИЙ ", "Расписание служб")) {
            val host = "https://n${random.nextInt(100_000)}.example.org/"
            val dest = host + "p${random.nextInt(100_000)}"
            val issuer = response(host, page("<article><a href='$dest'>$label</a></article>"))
            val selected = parish(host); val attempt = Any()
            val oldIssuer = baseline(issuer)
            val hint = requireNotNull(ScheduleNavigationIntent.issue(selected, host, attempt, issuer, clean, oldIssuer)[dest])
            val child = response(dest + "/", page("<article><h1></h1><a href='/opaque.pdf'>Файл</a>" +
                image("/opaque.png") + "</article>"))
            assertTrue(baseline(child).isEmpty())
            val added = hint.discover(selected, host, attempt, oldIssuer.single(), child, clean, baseline(child))
            assertEquals(listOf(host + "opaque.pdf", host + "opaque.png"), added.map { it.url })
            assertTrue(added.all { it.document && it.linkedFrom == dest + "/" })
            assertEquals(oldIssuer, baseline(issuer))
        }
    }

    @Test fun exactTargetPermitsOnlyLiteralResponseOrOneAddedTrailingSlash() {
        for (received in listOf(target, target + "/"))
            assertEquals(1, discovered(response(received, page(image()))).size)
        val destinations = listOf(target + "?x=1", target + "/other", root + "sibling", target.replace("https:", "http:"),
            target.replace("guided.", "www.guided."), "https://foreign.example.net/panel")
        for (received in destinations) {
            val child = response(received, page(image()))
            assertEquals(received, baseline(child), discovered(child))
        }
        val encodedParent = parent(href = root + "a%2Fb?x=a%26b")
        for (received in listOf(root + "a/b?x=a%26b", root + "a%2Fb?x=a&b", root + "a%2Fb?x=a%26B")) {
            val child = response(received, page(image()))
            assertEquals(received, baseline(child), discovered(child, encodedParent))
        }
        for (href in listOf("#part", root, "http://127.0.0.1/panel", root + "opaque.pdf", target + "?page=2", target + "?share=vk"))
            assertTrue(href, issue(parent(href = href), Any()).isEmpty())
        val binary = ScheduleResponse(target, byteArrayOf(1), "image/png")
        assertEquals(emptyList<ScheduleDocumentLink>(), discovered(binary))
    }

    @Test fun visibleIssuerRequiresOneOwnAreaAndWholeVisibleAnchorText() {
        for (area in listOf("main", "article")) assertTrue(issue(parent(area = area), Any()).isNotEmpty())
        val wrappers = listOf("<nav>%s</nav>", "<header>%s</header>", "<footer>%s</footer>", "<aside>%s</aside>",
            "<div class='sidebar'>%s</div>", "<div class='sidebar-home'>%s</div>", "<div id='comments'>%s</div>",
            "<div id='header'>%s</div>", "<div id='footer'>%s</div>", "<div class='header-links'>%s</div>",
            "<div id='t-header'>%s</div>", "<div id='t-footer'>%s</div>", "<div class='t-tildalabel'>%s</div>",
            "<dialog>%s</dialog>", "<div role='menu'>%s</div>", "<div class='pagination'>%s</div>",
            "<div hidden>%s</div>", "<div aria-hidden='TRUE'>%s</div>", "<div style='display:none'>%s</div>",
            "<div style='visibility:hidden'>%s</div>")
        val anchor = "<a href='$target'>Расписание</a>"
        for (wrapper in wrappers) {
            val issuer = response(root, page(wrapper.format(anchor))); val old = baseline(issuer)
            assertTrue(wrapper, issue(issuer, Any()).isEmpty()); assertEquals(wrapper, old, baseline(issuer))
        }
        val lookalike = response(root, page("<div id='t-footer-note' class='header-links-note'>$anchor</div>"))
        assertTrue("lookalike is not an exact service wrapper", issue(lookalike, Any()).isNotEmpty())
        val invalid = listOf("<a href='$target'><span hidden>Расписание</span></a>",
            "<a href='$target'>Расписание <span style='display:none'>новости</span></a>",
            "<a href='/schedule-panel' title='Расписание'>Подробнее<img alt='Расписание'></a>",
            "<a href='$target'>Расписание - открыть</a>", "<a href='$target'>Расписание воскресной школы</a>",
            "<a href='$target' rel='next'>Расписание</a>")
        for (html in invalid) assertTrue(html, issue(response(root, page(html)), Any()).isEmpty())
        assertTrue(issue(response(root, "<html><body>$anchor</body></html>"), Any()).isEmpty())
        assertTrue(issue(response(root, page("<article>$anchor</article><article>Новости</article>")), Any()).isEmpty())
        assertTrue(issue(response(root, "<main>$anchor</main><main>Другое</main>"), Any()).isEmpty())
    }

    @Test fun visibleTargetExcludesNonemptyHeadingsAmbiguityAndAllHiddenOrDecorativeBinaryContexts() {
        val bad = listOf("<h1>Храм Николая</h1>${image()}", "<h2>Новости</h2>${image()}",
            "<div role='heading'>Описание</div>${image()}", "<div class='t-title'>Событие</div>${image()}",
            "<article>${image()}</article><article>Другая статья</article>", "<div hidden>${image()}</div>",
            "<div style='display:none'>${image()}</div>", "<aside>${image()}</aside>",
            "<div id='comments'>${image()}</div>", image("/logo.png"), image(more = "role='presentation'"),
            "<img src='/opaque.png' width='149' height='1000'>", "<a href='/opaque.pdf' hidden>Файл</a>",
            "<a href='/opaque.png'><img width='50' height='50' src='/small.png'></a>",
            "<a href='/opaque.png'><img class='brand-logo' src='/mark.png' width='700' height='1000'></a>",
            "<a href='/opaque.png'><img src='/plain.png' data-original='/brand-logo.png' width='700' height='1000'></a>")
        for (html in bad) {
            val child = response(target, page(html)); val old = baseline(child)
            assertEquals(html, old, discovered(child)); assertEquals(html, old, baseline(child))
        }
        val serviceWrappers = listOf("id='header'", "id='footer'", "class='header-links'", "id='t-header'", "id='t-footer'", "class='t-tildalabel'")
        for (wrapper in serviceWrappers) {
            val child = response(target, page("<div $wrapper><a href='/opaque.pdf'>Файл</a>${image()}</div>"))
            val old = baseline(child)
            assertTrue(wrapper, old.isEmpty()); assertEquals(wrapper, old, discovered(child)); assertEquals(old, baseline(child))
        }
        val ordinary = response(target, page("<a href='/plain.pdf'>Файл</a>${image()}"))
        assertEquals(listOf(root + "plain.pdf", root + "opaque.png"), discovered(ordinary).map { it.url })
        for (unused in listOf("/logo.png", "/-/empty/1.jpg")) {
            val child = response(target, page("<a href='/opaque.png'><img src='$unused' data-original='/published.png' width='700' height='1000'></a>"))
            val old = baseline(child)
            assertTrue(old.isEmpty())
            assertEquals("effective original must win over unused placeholder $unused",
                listOf(root + "opaque.png", root + "published.png"), discovered(child).map { it.url })
            assertEquals(old, baseline(child))
        }
        val lookalike = response(target, page("<div id='t-footer-note' class='header-links-note t-tildalabel-note'>${image()}</div>"))
        assertEquals(listOf(root + "opaque.png"), discovered(lookalike).map { it.url })
        val externalHeading = response(target, "<html><body><h1>Храм</h1><main>${image()}</main></body></html>")
        assertEquals(baseline(externalHeading), discovered(externalHeading))
        val lazy = response(target, page("<img src='/placeholder.png' data-src='/direct.webp' data-original='/published.jpg' " +
            "data-img-zoom-url='/zoom.png' srcset='/guessed.png 1600w' width='500' height='700'>"))
        assertEquals(listOf(root + "zoom.png"), discovered(lazy).map { it.url })
        assertFalse(discovered(response(target, page(image(), title = "Расписание богослужений"))).isEmpty())
    }

    @Test fun originalPlatformMarkersDenyOnlyNewCapabilityOnEitherSideWhileNormalScriptsDoNot() {
        for (marker in listOf("<script type='text/plain'></script>", "<script type='text/plain'>bad</script>",
            "<div id='comments'><script type='text/plain'>bad</script></div>")) {
            assertTrue(issue(response(root, page("<a href='$target'>Расписание</a>$marker")), Any()).isEmpty())
            val child = response(target, page(image() + marker))
            assertEquals(baseline(child), discovered(child))
        }
        val script = "<script>var ordinaryWidget = true;</script>"
        assertTrue(issue(response(root, page("<a href='$target'>Расписание</a>$script")), Any()).isNotEmpty())
        assertEquals(1, discovered(response(target, page(image() + script))).size)
        val tenant = "https://controlled.myprihod.ru/"
        val tenantTarget = tenant + "panel"
        val selected = parish(tenant)
        val style = "<link href='/multisite/church/css/schedule.css'>"
        val platform = "<div class='content-block schedule'><table><tr><td>bad</td></tr></table></div>"
        val issuer = response(tenant, page("<a href='$tenantTarget'>Расписание</a>$platform").replace("</head>", "$style</head>"))
        assertTrue(MyPrihodScheduleHtml.appliesTo(requireNotNull(issuer.html())))
        assertTrue(ScheduleNavigationIntent.issue(selected, tenant, Any(), issuer, clean, baseline(issuer)).isEmpty())
        val ordinaryIssuer = response(tenant, page("<a href='$tenantTarget'>Расписание</a>"))
        val attempt = Any(); val intended = baseline(ordinaryIssuer).single()
        val hint = requireNotNull(ScheduleNavigationIntent.issue(selected, tenant, attempt, ordinaryIssuer, clean, baseline(ordinaryIssuer))[tenantTarget])
        val child = response(tenantTarget, page(image() + platform).replace("</head>", "$style</head>"))
        assertTrue(MyPrihodScheduleHtml.appliesTo(requireNotNull(child.html())))
        assertEquals(baseline(child), hint.discover(selected, tenant, attempt, intended, child, clean, baseline(child)))
    }

    @Test fun duplicateAdmittedTargetKeepsFirstOccurrenceOrderAndCannotPromoteTheThirteenthTarget() {
        val issuer = response(root, "<html><body><nav><a href='$target'>Расписание</a></nav><main>" +
            "<a href='/schedule-other'>Расписание</a><a href='$target'>Расписание</a></main></body></html>")
        val old = baseline(issuer); val hints = issue(issuer, Any())
        assertEquals(target, old.first().url); assertTrue(hints.containsKey(target)); assertEquals(old, baseline(issuer))
        val full = (0..11).joinToString("") { "<a href='/schedule-$it'>Расписание</a>" } +
            "<a href='$target'>Расписание</a>"
        val capped = response(root, page(full))
        assertEquals(12, baseline(capped).size); assertFalse(issue(capped, Any()).containsKey(target))
        val two = response(target, page("<a href='/full.png'>Файл</a>${image("/preview.png")}"))
        assertEquals(listOf(root + "full.png", root + "preview.png"), discovered(two).map { it.url })
    }

    @Test fun admittedSlotsPreservePinnedAndBinaryPrefixAndBoundAnchorAndImageScans() {
        for (count in listOf(10, 12)) {
            val oldHtml = "<a href='/schedule-notice'>Расписание</a><a href='/schedule-old.pdf'>Файл</a>" +
                (2 until count).joinToString("") { "<a href='/schedule-$it'>Подробнее</a>" }
            val child = response(target, page(oldHtml + image("/one.png") + image("/two.png") + image("/three.png")))
            val old = baseline(child); assertEquals(count, old.size)
            val result = discovered(child); prefix(old, result); assertEquals(12, result.size)
            assertEquals(old, baseline(child))
        }
        val pinned = response(target, page("<a href='/schedule-cancel'>Расписание отменено</a>" +
            "<a href='/schedule-old.pdf'>Файл</a>" + image()))
        val pinnedOld = baseline(pinned)
        assertEquals(root + "schedule-cancel", pinnedOld.first().url)
        assertEquals(pinnedOld, discovered(pinned,
            parsed = ScheduleParseResult(ScheduleParseDisposition.REJECTED, unresolvedWithdrawal = true)))
        val first500 = (0 until 500).joinToString("") { "<a href='/ordinary-$it'>Обычная ссылка</a>" }
        val issuer = response(root, page(first500 + "<a href='$target'>Расписание</a>"))
        assertTrue(issue(issuer, Any()).isEmpty())
        val childAnchors = response(target, page(first500 + "<a href='/late.pdf'>Файл</a>"))
        assertEquals(baseline(childAnchors), discovered(childAnchors))
        val first30 = (0 until 30).joinToString("") { "<img src='/tiny-$it.png' width='50'>" }
        val childImages = response(target, page(first30 + image()))
        assertEquals(baseline(childImages), discovered(childImages))
    }

    @Test fun realRepositoryUsesThirdDocumentSlotAfterOriginalShopOrderAndKeepsSourceProvenance() = runBlocking {
        val script = Script(); val cache = Cache(); val repo = script.repo(cache)
        val result = repo.refresh(parish(), now, force = true)
        assertEquals(listOf(root, script.services, target) + script.shops.take(2) + script.media, script.calls)
        assertEquals(script.shops.take(2) + script.media, script.extracted)
        assertEquals(InternetScheduleStatus.READY, result.status)
        val publication = requireNotNull(result.publication)
        assertEquals(script.media, publication.sourceUrl); assertEquals(script.finalTarget, publication.linkedFrom)
        assertEquals(root, publication.website); assertFalse(publication.yearInferred)
        assertEquals(listOf(InternetService("2026-11-15", "09:00", "Литургия", "liturgy")), publication.events)
        assertEquals(result, script.repo(cache).cached(parish()))
    }

    @Test fun laterOwnScheduleCueEnrichesTheFirstPendingTargetWithoutReorderingOrRefetch() = runBlocking {
        val script = Script()
        script.rootBody = "<html><body><nav><a href='${script.services}'>Богослужения</a></nav>" +
            "<a href='$target'>Расписание</a><main><article>Новости</article><article>Приход</article></main></body></html>"
        val result = script.repo().refresh(parish(), now, force = true)
        assertEquals(listOf(root, script.services, target) + script.shops.take(2) + script.media, script.calls)
        assertEquals(InternetScheduleStatus.READY, result.status)
        val publication = requireNotNull(result.publication)
        assertEquals(script.media, publication.sourceUrl)
        assertEquals(script.finalTarget, publication.linkedFrom)
    }

    @Test fun pendingCueKeepsOriginalHtmlPublicationEdgeAndCannotRefetchAnAlreadyVisitedTarget() = runBlocking {
        val html = Script()
        html.rootBody = page("<a href='${html.services}'>Богослужения</a><a href='$target'>Расписание</a>", "nav")
        html.targetBody = page("<h1>Расписание богослужений</h1><p>15 ноября 2026</p><p>09:00 Литургия</p>")
        val publication = requireNotNull(html.repo().refresh(parish(), now, force = true).publication)
        assertEquals(root, publication.linkedFrom)
        assertEquals(html.finalTarget, publication.sourceUrl)
        assertEquals(1, html.calls.count { it == target })

        val visited = Script()
        visited.rootBody = page("<a href='$target'>Расписание</a><a href='${visited.services}'>Богослужения</a>", "nav")
        assertNull(visited.repo().refresh(parish(), now, force = true).publication)
        assertEquals(listOf(root, target, visited.services) + visited.shops.take(2), visited.calls)
        assertFalse(visited.calls.contains(visited.media))
    }

    @Test fun pendingTargetStillNeedsAnEligibleCueAndAnExactNonbinaryRequest() = runBlocking {
        val script = Script().apply { issuerLabel = "Подробнее о расписании" }
        script.rootBody = page("<a href='${script.services}'>Богослужения</a><a href='$target'>Расписание</a>", "nav")
        assertNull(script.repo().refresh(parish(), now, force = true).publication)
        assertFalse(script.calls.contains(script.media))

        val issuer = parent(); val token = Any(); val child = response(target, page(image()))
        for (link in listOf(ScheduleDocumentLink(target + "/", root, false),
            ScheduleDocumentLink(target + "?x=1", root, false), ScheduleDocumentLink(target, root, true))) {
            val hint = requireNotNull(issue(issuer, token)[target])
            assertEquals(baseline(child), hint.discover(parish(), root, token, link, child, clean, baseline(child)))
        }
    }

    @Test fun explicitlyPublishedWidthVariantsChooseOneReadableCandidateWithinTheDecoderBudget() {
        val variants = "/small.png 724w, /tiny.png 212w, /medium.png 1086w, /large.png 1448w, /oversized.png 1810w"
        fun picture(set: String) = "<img src='/small.png' width='724' height='1024' srcset='$set'>"
        val child = response(target, page(picture(variants) + picture("/small.png 724w, /other.png 1000w")))
        assertTrue(baseline(child).isEmpty())
        assertEquals(listOf(root + "large.png"), discovered(child).map { it.url })
        assertEquals(4_000_000, ScheduleDocumentExtractor.MAX_IMAGE_PIXELS)
        val labeled = response(target, page("<h1>Расписание</h1>" + picture(variants)))
        assertEquals(baseline(labeled), discovered(labeled))
    }

    @Test fun ambiguousVariantsAndExistingImageProvenanceKeepThePublishedBaseWithoutExtraRequests() {
        val valid = "/small.png 724w, /large.png 1448w"
        val bad = listOf("/small.png 1x, /large.png 2x", "/small.png 700w, /large.png 1448w",
            "/small.png 724w, /a.png 1448w, /b.png 1448w", "/small.png 724w, /large.png 1448w, /large.png 1000w",
            "/small.png 724w, https://foreign.example.net/large.png 1448w", "/small.png 724w, /large.png 999999w",
            "/small.png 724w, /large.png 1448w,", "/small.png 724w, /large.pdf 1448w",
            "/small.png 724w, /-/empty/large.png 1448w", "/small.png 724w, /large.png#part 1448w", valid.repeat(400))
        for (set in bad) {
            val child = response(target, page("<img src='/small.png' width='724' height='1024' srcset='$set'>"))
            assertEquals(set.take(100), listOf(root + "small.png"), discovered(child).map { it.url })
        }
        for (extra in listOf("width='0' height='1024'", "width='724'", "width='724' height='-1'")) {
            val child = response(target, page("<img src='/small.png' $extra srcset='$valid'>"))
            assertFalse(discovered(child).any { it.url == root + "large.png" })
        }
        for (body in listOf("<a href='/small.png'><img src='/small.png' width='724' height='1024' srcset='$valid'></a>",
            "<picture><img src='/small.png' width='724' height='1024' srcset='$valid'></picture>",
            "<img src='/small.png' width='724' height='1024' data-original='/original.png' srcset='$valid'>")) {
            val links = discovered(response(target, page(body)))
            assertEquals(1, links.size)
            assertFalse(links.any { it.url == root + "large.png" })
        }
        val decorativeBase = response(target, page("<img src='/logo.png' width='724' height='1024' " +
            "srcset='/logo.png 724w, /large.png 1448w'>"))
        assertTrue(discovered(decorativeBase).isEmpty())
        val decorativeVariant = response(target, page("<img src='/small.png' width='724' height='1024' " +
            "srcset='/small.png 724w, /logo-large.png 1448w'>"))
        assertEquals(listOf(root + "small.png"), discovered(decorativeVariant).map { it.url })
    }

    @Test fun realRepositoryUsesOneChosenWidthInTheThirdSlotAndNeverRetriesThePreview() = runBlocking {
        for (missing in listOf(false, true)) {
            val script = Script(); val large = root + "large.png"
            script.targetBody = page("<img src='${script.media}' width='724' height='1024' " +
                "srcset='${script.media} 724w, $large 1448w, /oversized.png 1810w'>")
            val transport = ScheduleTransport { url ->
                if (url == large) {
                    script.calls += url
                    if (missing) throw IOException("Published high resolution unavailable")
                    ScheduleResponse(url, byteArrayOf(1, 2, 3), "image/png")
                } else script.transport.fetch(url)
            }
            val repo = InternetScheduleRepository(transport, Cache(), script.extractor,
                siteResolver = ScheduleSiteResolver { null })
            val result = repo.refresh(parish(), now, force = true)
            assertEquals(listOf(root, script.services, target) + script.shops.take(2) + large, script.calls)
            assertFalse(script.calls.contains(script.media))
            if (missing) assertNull(result.publication)
            else {
                assertEquals(large, result.publication?.sourceUrl)
                assertEquals(script.finalTarget, result.publication?.linkedFrom)
            }
        }
    }

    @Test fun decorativeAnchorBeforeTheRealImageCannotConsumeTheThirdDocumentSlot() = runBlocking {
        val script = Script()
        val decoy = root + "decoy.png"
        script.targetBody = page("<article><h1></h1><a href='$decoy'><img class='brand-logo' src='/mark.png' " +
            "width='700' height='1000'></a>${image()}</article>")
        val result = script.repo().refresh(parish(), now, force = true)
        assertEquals(listOf(root, script.services, target) + script.shops.take(2) + script.media, script.calls)
        assertEquals(script.shops.take(2) + script.media, script.extracted)
        assertFalse(script.calls.contains(decoy)); assertFalse(script.calls.contains(root + "mark.png"))
        assertEquals(InternetScheduleStatus.READY, result.status)
        assertEquals(script.media, result.publication?.sourceUrl)
        assertEquals(script.finalTarget, result.publication?.linkedFrom)
    }

    @Test fun realRepositoryNeverAddsAFourthDocumentRequestOrCompensatingRescue() = runBlocking {
        val script = Script().apply { oldCount = 3 }
        val result = script.repo().refresh(parish(), now, force = true)
        assertEquals(listOf(root, script.services, target) + script.shops, script.calls)
        assertEquals(script.shops, script.extracted); assertFalse(script.calls.contains(script.media))
        assertNull(result.publication); assertFalse(script.calls.any { "/wp-json/" in it })
    }

    @Test fun lifetimeIsOneUseOneAttemptOneSelectedRootAndNeverRelaysOrSurvivesRefresh() = runBlocking {
        val attempt = Any(); val issuer = parent(); val intended = baseline(issuer).single()
        val child = response(target, page(image()))
        val hint = requireNotNull(issue(issuer, attempt)[target])
        assertEquals(1, hint.discover(parish(), root, attempt, intended, child, clean, baseline(child)).size)
        assertEquals(baseline(child), hint.discover(parish(), root, attempt, intended, child, clean, baseline(child)))
        for (variant in listOf("attempt", "root", "selected", "parent")) {
            val own = parent(); val token = Any(); val proof = requireNotNull(issue(own, token)[target])
            if (variant == "parent") own.bytes[own.bytes.lastIndex] = '!'.code.toByte()
            val got = proof.discover(if (variant == "selected") parish().copy(address = "Другая улица, 9") else parish(),
                if (variant == "root") root + "other/" else root, if (variant == "attempt") Any() else token,
                intended, child, clean, baseline(child))
            assertEquals(variant, baseline(child), got)
        }
        val grandchild = root + "next"
        val relayCalls = mutableListOf<String>()
        val relay = InternetScheduleRepository(ScheduleTransport { url ->
            relayCalls += url
            when (url) {
                root -> parent()
                target -> response(target, page("<a href='$grandchild'>Расписание</a>"))
                grandchild -> response(grandchild, page(image()))
                else -> error("Unexpected relayed GET")
            }
        }, Cache(), ScheduleDocumentExtractor { error("No relayed image extraction") }, siteResolver = ScheduleSiteResolver { null })
        assertNull(relay.refresh(parish(), now, force = true).publication)
        assertEquals(listOf(root, target, grandchild), relayCalls)
        val script = Script(); val cache = Cache(); val repo = script.repo(cache)
        repo.refresh(parish(), now, force = true)
        script.calls.clear(); script.extracted.clear(); script.issuerLabel = "Подробнее о расписании"
        script.repo(cache).refresh(parish(), now.plusSeconds(60), force = true)
        assertTrue(script.calls.contains(target)); assertFalse(script.calls.contains(script.media))
        assertFalse(cache.values.values.any { "ScheduleNavigationIntent" in it || "parentSha" in it })
    }

    @Test fun typedSafetyDeniesNewCapabilityAndDurableWithdrawalsPrecedeTheNextGet() = runBlocking {
        val unsafe = listOf(ScheduleParseResult(ScheduleParseDisposition.REJECTED, safetyConflict = true),
            ScheduleParseResult(ScheduleParseDisposition.REJECTED, unresolvedWithdrawal = true),
            ScheduleParseResult(ScheduleParseDisposition.REJECTED, withdrawsAll = true),
            ScheduleParseResult(ScheduleParseDisposition.REJECTED, withdrawnDates = setOf("2026-11-15")),
            ScheduleParseResult(ScheduleParseDisposition.REJECTED, withdrawnStarts = setOf("2026-11-15T09:00")))
        for (parsed in unsafe) {
            val issuer = parent(); assertTrue(issue(issuer, Any(), parsed = parsed).isEmpty())
            val child = response(target, page(image()))
            assertEquals(baseline(child), discovered(child, parsed = parsed))
        }
        val script = Script(); val cache = Cache(); val selected = parish()
        val oldEvents = listOf(InternetService("2026-11-15", "09:00", "Литургия", "liturgy"),
            InternetService("2026-11-16", "09:00", "Литургия", "liturgy"))
        val oldPublication = SchedulePublication(selected.id, selected.timezone, root, script.media,
            now.minusSeconds(3600).toString(), oldEvents, "image", script.finalTarget)
        val oldState = InternetScheduleState(selected.id, InternetScheduleStatus.READY, root, script.media,
            now.minusSeconds(3600).toString(), oldPublication, configuredWebsite = root)
        cache.write(selected.id, ScheduleCacheCodec.encode(oldState))
        assertNotNull(script.repo(cache).cached(selected).publication)
        script.targetBody = page("<p>15 ноября 2026 года все богослужения отменены</p>" +
            "<a href='/schedule-marker.png'>Расписание</a>" + image())
        var targetReturned = false; var observed = false
        script.getBefore = { url ->
            if (targetReturned) {
                val cold = script.repo(cache).cached(selected)
                assertTrue(cache.safety.isNotEmpty()); assertTrue(cold.sourceHasWithdrawals)
                assertFalse(cold.publication?.events.orEmpty().any { it.date == "2026-11-15" })
                observed = true
                throw kotlinx.coroutines.CancellationException("Controlled interruption after durable withdrawal")
            }
            if (url == target) targetReturned = true
        }
        try { script.repo(cache).refresh(selected, now, force = true) }
        catch (_: kotlinx.coroutines.CancellationException) { /* expected only after durable write */ }
        assertTrue(observed); assertFalse(script.calls.contains(script.media))
        val cold = script.repo(cache).cached(selected)
        assertTrue(cold.sourceHasWithdrawals)
        assertEquals(oldEvents.takeLast(1), cold.publication?.events)
        // A later unavailable attempt may not resurrect the withdrawn source row.
        val offline = InternetScheduleRepository(ScheduleTransport { throw IOException("Controlled offline") }, cache,
            siteResolver = ScheduleSiteResolver { null }).refresh(selected, now.plusSeconds(60), force = true)
        assertEquals(cold.publication, offline.publication); assertTrue(offline.sourceHasWithdrawals)
        // In the same import, a later clean binary is still subject to the earlier dated withdrawal.
        val later = Script().apply {
            oldCount = 0
            targetBody = page("<p>15 ноября 2026 года все богослужения отменены</p><a href='$media'>Расписание</a>")
        }
        val laterCache = Cache().apply { write(selected.id, ScheduleCacheCodec.encode(oldState)) }
        var checkedBeforeClean = false
        later.getBefore = { url -> if (url == later.media) {
            val durable = later.repo(laterCache).cached(selected)
            assertTrue(durable.sourceHasWithdrawals)
            assertFalse(durable.publication?.events.orEmpty().any { it.date == "2026-11-15" })
            checkedBeforeClean = true
        } }
        val afterClean = later.repo(laterCache).refresh(selected, now, force = true)
        assertTrue(checkedBeforeClean); assertEquals(listOf(later.media), later.extracted)
        assertFalse(afterClean.publication?.events.orEmpty().any { it.date == "2026-11-15" })
        assertTrue(afterClean.sourceHasWithdrawals)
    }

    @Test fun cancellationDuringTargetGetOrRecordingExtractionPublishesNothingLate() = runBlocking {
        data class Boundary(val calls: List<String>, val writes: Int, val values: Map<String, String>,
            val safety: List<String>, val extracted: List<String>, val publication: SchedulePublication?)
        for (stage in listOf("target", "extract")) {
            val script = Script(); val cache = Cache(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            if (stage == "target") script.getBefore = { url ->
                if (url == target) { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
            } else script.extractBefore = { result ->
                if (result.url == script.media) { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
            }
            val repo = script.repo(cache)
            val job = launch { repo.refresh(parish(), now, force = true) }
            val before = try {
                withTimeout(10_000) { entered.await() }
                Boundary(script.calls.toList(), cache.writes, cache.values.toMap(), cache.safety.toList(),
                    script.extracted.toList(), repo.cached(parish()).publication)
            } finally {
                job.cancel(); release.complete(Unit)
                withContext(NonCancellable) { job.join() }
            }
            if (stage == "target") assertEquals("No document has been processed before the target GET", 0, before.writes)
            else assertTrue("Both old documents precede extraction of the new image",
                before.extracted.containsAll(script.shops.take(2)))
            assertNull("No publication exists at the cancellation boundary", before.publication)
            assertTrue(stage, job.isCancelled); assertEquals(stage, before.calls, script.calls)
            assertNull(stage, repo.cached(parish()).publication)
            assertEquals("$stage must not write after cancellation; earlier writes=${before.writes}", before.writes, cache.writes)
            assertEquals(stage, before.values, cache.values); assertEquals(stage, before.safety, cache.safety)
            if (stage == "target") assertFalse(script.extracted.contains(script.media))
            println("NAV_CANCEL_BOUNDARY stage=$stage before=${before.writes} after=${cache.writes}")
        }
    }

    @Test fun discoveryDoesNotSupplyMissingYearVenueOrScheduleTextToTheRecordingExtractor() = runBlocking {
        val variants = listOf<Pair<String, (String) -> ScheduleDocument?>>(
            "missing year" to { url -> ScheduleDocument(url, "image", listOf(ScheduleDocumentPage(listOf(ScheduleDocumentLine("15 ноября 09:00 Литургия"))))) },
            "old year" to { url -> ScheduleDocument(url, "image", listOf(ScheduleDocumentPage(listOf(ScheduleDocumentLine("2024-11-15 09:00 Литургия"))))) },
            "other venue" to { url -> ScheduleDocument(url, "image", listOf(ScheduleDocumentPage(listOf(ScheduleDocumentLine("Храм Петра и Павла", headingLevel = 1), ScheduleDocumentLine("2026-11-15 09:00 Литургия"))))) },
            "shop" to { url -> ScheduleDocument(url, "image", listOf(ScheduleDocumentPage(listOf(ScheduleDocumentLine("Церковная лавка. Открыта с 8:00 до 18:00"))))) },
            "unsupported" to { _ -> null }, "independently dated control" to { url -> validDocument(url) })
        for ((name, make) in variants) {
            val script = Script().apply { document = { make(it.url) } }
            val expected = make(script.media)?.let { ScheduleDocumentParser.inspect(it, today, parish()).events }.orEmpty()
            val result = script.repo().refresh(parish(), now, force = true)
            assertTrue(name, script.extracted.contains(script.media))
            assertEquals(name, expected, result.publication?.events.orEmpty())
            if (name != "independently dated control") assertTrue(name, expected.isEmpty())
        }
    }

    @Test fun semanticMainKeepsGenericLayoutPrefixesWithoutGrantingSidebarAuthority() {
        fun markup(content: String, kind: String): String {
            val article = "<article>$content</article>"
            val region = when (kind) {
                "ancestor" -> "<section class='layout sidebar-width-4 sidebar_gap_8'><main>$article</main></section>"
                "main" -> "<main class='layout sidebar_columns'>$article</main>"
                "role" -> "<section class='sidebar-layout'><div role='main' class='sidebar_grid'>$article</div></section>"
                "body" -> "<main>$article</main>"
                else -> article
            }
            val bodyClass = if (kind == "body") " class='sidebar-right'" else ""
            return "<html><body$bodyClass>$region</body></html>"
        }
        for (kind in listOf("ancestor", "main", "role", "body", "plain-article")) {
            val issuer = response(root, markup("<a href='$target'><strong>Расписание</strong></a>", kind))
            assertTrue(kind, baseline(issuer).any { it.url == target && !it.document })
            val child = response(target, markup("<h1></h1>${image()}", kind))
            val result = discovered(child, issuer)
            prefix(baseline(child), result)
            assertTrue(kind, result.any { it.url == root + "opaque.png" && it.document && it.linkedFrom == target })
        }
    }

    @Test fun layoutExemptionNeverCrossesExplicitSidebarsWidgetsHiddenStateOrFallbackArticles() {
        for (wrapper in listOf("aside", "div class='sidebar'", "div id='sidebar'", "div role='complementary'",
            "div hidden", "div aria-hidden='true'", "div style='display:none'", "div style='visibility:hidden'")) {
            val tag = wrapper.substringBefore(' ')
            val child = response(target, "<html><body><$wrapper><main><article><h1></h1>${image()}</article></main></$tag></body></html>")
            assertEquals(wrapper, baseline(child), discovered(child))
        }
        for (name in listOf("sidebar-widget", "sidebar_widget", "sidebar-width-4")) {
            val issuer = response(root, page("<section class='$name'><a href='$target'>Расписание</a></section>"))
            assertTrue(name, baseline(issuer).any { it.url == target && !it.document })
            assertTrue(name, issue(issuer, Any()).isEmpty())
            val child = response(target, page("<h1></h1><section class='$name'>${image()}</section>"))
            assertEquals(name, baseline(child), discovered(child))
        }
        val fallback = response(target, page("<section class='sidebar-layout'><article><h1></h1>${image()}</article></section>", "section"))
        assertEquals(baseline(fallback), discovered(fallback))
        val fallbackIssuer = response(root, page("<section class='sidebar-layout'><article><a href='$target'>Расписание</a></article></section>", "section"))
        assertTrue(baseline(fallbackIssuer).any { it.url == target && !it.document })
        assertTrue(issue(fallbackIssuer, Any()).isEmpty())
        val outside = response(root, "<html><body><section class='sidebar-layout'><a href='$target'>Расписание</a></section><main></main></body></html>")
        assertTrue(baseline(outside).any { it.url == target && !it.document })
        assertTrue(issue(outside, Any()).isEmpty())
    }

    @Test fun unchangedPublicLinksAndForeignHtmlUnderPdfKeepThePreviousStrictRoute() = runBlocking {
        val baselinePage = response(root, page("<h1>Расписание богослужений</h1>" +
            "<a href='/schedule-cancel'>Расписание отменено</a><a href='/schedule.pdf'>Файл</a>"))
        val old = baseline(baselinePage)
        assertEquals(listOf(ScheduleDocumentLink(root + "schedule-cancel", root, false),
            ScheduleDocumentLink(root + "schedule.pdf", root, true)), old)
        assertEquals(ScheduleDocumentLink(target, root, false), ScheduleDocumentLink(target, root, false))
        val foreign = "https://cdn.example.net/schedule.pdf"; val calls = mutableListOf<String>(); val extracted = mutableListOf<String>()
        val repo = InternetScheduleRepository(ScheduleTransport { url ->
            calls += url
            if (url == root) response(root, page("<a href='$foreign'>Расписание</a>"))
            else response(foreign, "<article data-day='2026-11-15'><li><time>09:00</time><span>Литургия</span></li></article>")
        }, Cache(), ScheduleDocumentExtractor { extracted += it.url; validDocument(it.url) }, siteResolver = ScheduleSiteResolver { null })
        assertNull(repo.refresh(parish(), now, force = true).publication)
        assertEquals(listOf(root, foreign), calls); assertTrue(extracted.isEmpty())
        assertEquals(old, baseline(baselinePage))
        // Root060/linked navigation/WordPress/MyPrihod/declarative source suites remain unchanged and must run at root.
    }
}
