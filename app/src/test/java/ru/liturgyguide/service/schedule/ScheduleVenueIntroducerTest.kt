package ru.liturgyguide.service.schedule

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.security.MessageDigest
import java.time.LocalDate

/** Exact saved OCR stays primary evidence; grammatical variants below are explicitly synthetic. */
class ScheduleVenueIntroducerTest {
    private val today = LocalDate.of(2026, 11, 13)
    private val child = parish("Церковь иконы Божией Матери «Всецарица»")
    private val header = "Храм во имя иконы Божией Матери \"Всецарица\""

    private fun parish(name: String, shortName: String = name, city: String = "", address: String = "") = Parish(
        "venue-selected", name, shortName, city, address, "", "", "Europe/Moscow", "", "", "", "", emptyList(), "", "")
    private fun matches(label: String, selected: Parish) = ScheduleVenue.matches(ScheduleVenue.inlineLabel(label), selected)
    private fun starts(document: ScheduleDocument, selected: Parish) = ScheduleDocumentParser.inspect(document, today, selected)
    private fun simple(heading: String, service: String = "17:00 Вечерня") = ScheduleDocument("https://source.example/schedule", "image",
        listOf(ScheduleDocumentPage(listOf(ScheduleDocumentLine(heading, headingLevel = 1),
            ScheduleDocumentLine("Ноябрь 2026"), ScheduleDocumentLine("13 пятница $service")))))

    @Test fun actualThirdImageAndItsHocrPublishOnlyTheSelectedChildWithEmptyOsmMetadata() {
        assertEquals("", child.city); assertEquals("", child.address)
        assertEquals("", child.phone); assertEquals("", child.website)
        for (geometry in listOf(false, true)) {
            val result = starts(ImageScheduleFixture.document(3, geometry), child)
            assertEquals("geometry=$geometry", ScheduleParseDisposition.PARSED, result.disposition)
            val expected = listOf("2026-11-13T17:00", "2026-11-14T08:00", "2026-11-14T17:00", "2026-11-15T08:00")
            val actual = result.events.filter { it.date in setOf("2026-11-13", "2026-11-14", "2026-11-15") }
                .map { "${it.date}T${it.time}" }
            assertEquals("geometry=$geometry", expected, actual.sorted())
            assertFalse(result.events.any { it.time in setOf("07:00", "09:30") })
            assertFalse(result.yearInferred)
            assertFalse(result.safetyConflict)
            assertTrue(result.withdrawnStarts.isEmpty())
        }
    }

    @Test fun realParentHalvesAndTheirExistingContinuationNeverAcquireTheChildImage() {
        val parent = ImageScheduleFixture.parish
        val joined = requireNotNull(ImageScheduleFixture.joined())
        val result = starts(joined, parent)
        assertTrue(result.events.any { it.date == "2026-11-15" && it.time == "07:00" })
        assertTrue(result.events.any { it.date == "2026-11-15" && it.time == "09:30" })
        assertTrue(result.events.any { it.date == "2026-11-30" && it.time == "08:00" })
        assertTrue(starts(ImageScheduleFixture.document(3), parent).events.isEmpty())
        assertNull(ScheduleImageContinuation.join(joined, ImageScheduleFixture.document(3), ImageScheduleFixture.parent, parent))
        assertNull(ScheduleImageContinuation.join(ImageScheduleFixture.document(1), ImageScheduleFixture.document(2),
            ImageScheduleFixture.parent, child))
        assertTrue(starts(ImageScheduleFixture.document(1), child).events.isEmpty())
        assertTrue(starts(ImageScheduleFixture.document(2), child).events.isEmpty())
    }

    @Test fun wholeIntroducersAndBalancedBoundaryQuotesRetainTheFullDedication() {
        for (intro in listOf("во имя", "в честь", "ВО ИМЯ")) for ((open, close) in listOf("\"" to "\"", "«" to "»", "“" to "”")) {
            val label = "Храм $intro иконы Божией Матери $open" + "Всецарица$close"
            assertTrue(label, matches(label, child))
            assertTrue("Direct heading: $label", ScheduleVenue.matches(label, child))
        }
        assertTrue(matches("Храм в честь \"иконы Божией Матери Всецарица\"", child))
        assertTrue(starts(simple(header.replace("во имя", "в честь")), child).events.isNotEmpty())
        for (invalid in listOf("Храм во имя", "Храм в честь иконы Божией Матери", "Храм во имя Божией Матери", "Храм во имя Святителя",
            "Храм во Николая", "Храм в честь", "Храм имя Николая", "Храм честь Николая",
            "Храм во имя иконы Божией Матери \"Всецарица", "Храм во имя иконы Божией Матери «Всецарица\"",
            "Храм во имя иконы Божией Матери / Всецарица", "Храм во имя иконы Божией Матери | Всецарица",
            "Храм во имя иконы Божией Матери. Всецарица", "Храм во имя иконы Божией Матери 17:00 Всецарица")) {
            assertNotNull(invalid, ScheduleVenue.inlineLabel(invalid))
            assertFalse(invalid, matches(invalid, child))
            assertTrue(invalid, starts(simple(invalid), child).events.isEmpty())
        }
    }

    @Test fun otherIconsCannotBorrowTheDistinguishingWordFromCityAddressOrNameLocation() {
        for (name in listOf("Церковь Казанской иконы Божией Матери", "Церковь Владимирской иконы Божией Матери")) {
            for (geometry in listOf(false, true)) {
                for (selected in listOf(parish(name), parish(name, city = "Всецарица"),
                    parish(name, address = "улица Всецарица, 1"))) {
                    assertFalse(name, ScheduleVenue.matches(header, selected))
                    assertTrue(name, starts(ImageScheduleFixture.document(3, geometry), selected).events.isEmpty())
                }
            }
        }
        for (name in listOf("Храм на улице Всецарица", "Храм в поселке Всецарица", "Храм при Всецарице", "Храм г. Всецарица"))
            assertFalse(name, matches(header, parish(name, shortName = "", address = "Всецарица")))
        assertTrue(matches(header, child.copy(shortName = "Храм Всецарица")))
        assertTrue(matches(header, child.copy(name = "Храм", shortName = child.name)))
        assertFalse(matches(header, parish("Храм Всеволода", city = "Всецарица", address = "Всецарица")))
    }

    @Test fun genericIconWithoutAnIntroducerStillNeedsItsOwnDistinguishingName() {
        for (selected in listOf(child, parish("Церковь Казанской иконы Божией Матери"),
            parish("Церковь Владимирской иконы Божией Матери"))) {
            for (label in listOf("Храм иконы Божией Матери", "Храм иконы Божией Матери 'Всецарица'")) {
                assertFalse(label, ScheduleVenue.matches(label, selected))
                assertFalse(label, matches(label, selected))
                assertTrue(label, starts(simple(label), selected).events.isEmpty())
            }
        }
        assertTrue(matches("Нижний храм", parish("Нижний Никольский храм")))
        assertTrue(matches("Храм иконы Божией Матери «Всецарица»", child))
    }

    @Test fun trailingNameCommaKeepsTheActualAlexiusIdentityWithoutBorrowingAnAddress() {
        val selected = parish("Церковь Алексия, митрополита Московского")
        for (label in listOf("Храм во имя Алексия Московского", selected.name)) {
            assertTrue(label, ScheduleVenue.matches(label, selected))
            assertTrue(label, matches(label, selected))
            assertTrue(label, starts(simple(label), selected).events.isNotEmpty())
            assertFalse(label, matches(label, parish("Церковь Николая", address = "Алексия Московского")))
        }
        assertFalse(matches("Храм во имя Николая Московского", selected))
        for (label in listOf("Храм Алексия / храм", "Храм Алексия | храм", "Храм Алексия. храм Николая",
            "Храм Николая. Алексия"))
            assertFalse(label, matches(label, selected))
    }

    @Test fun confessorsInACompleteDedicationAreNeitherConfessionNorAnotherBuilding() {
        val label = "Храм Воскресения Христова и Новомучеников и Исповедников Церкви Русской"
        val selected = parish("Сретенский монастырь. $label")
        assertTrue(ScheduleVenue.isLabel(label))
        assertTrue(ScheduleVenue.matches(label, selected))
        assertTrue(matches(label, selected))
        assertTrue(starts(simple(label), selected).events.isNotEmpty())
        assertFalse(matches(label, parish("Храм Сретения Владимирской иконы Божией Матери")))
        assertFalse(matches("$label | Никольский храм", selected))
        assertFalse(matches("$label в церкви Николая", selected))
        assertFalse(ScheduleVenue.isLabel("Храм Николая Исповедь"))
        val cancelled = starts(simple(label, "17:00 Вечерня отменена"), selected)
        assertTrue(cancelled.events.isEmpty())
        assertTrue("2026-11-13T17:00" in cancelled.withdrawnStarts)
    }

    @Test fun qualifiersBelongToTheBuildingNameAndIntroducersDoNotEraseRealLocationClauses() {
        val nicholas = parish("Никольский храм")
        val lower = parish("Нижний Никольский храм")
        assertFalse(matches("Нижний храм во имя Николая", nicholas))
        assertFalse(matches("Нижний храм во имя Николая", nicholas.copy(city = "Нижний Новгород", address = "Нижняя улица")))
        assertFalse(matches("Нижний храм во имя Николая", nicholas.copy(name = "Никольский храм в Нижнем Новгороде")))
        assertTrue(matches("Нижний храм во имя Николая", lower))
        assertTrue(ScheduleVenue.matches("Нижний храм во имя Николая", lower))
        assertTrue(matches("Литургия в нижнем Никольском храме", lower))
        assertFalse(matches("Нижний храм во имя Николая", lower.copy(name = "Нижний Казанский храм", shortName = "Нижний храм")))
        assertFalse(matches("Нижний храм во имя Николая", lower.copy(name = "Нижний Казанский храм", shortName = "Никольский храм")))
        for (label in listOf("Храм во имя Николая в Казанском храме", "Храм во имя Николая при часовне",
            "Храм во имя Николая в нижнем приделе", "Храм во имя Николая на Казанской улице",
            "Храм во имя Николая для другого здания")) assertFalse(label, matches(label, nicholas))
        assertFalse(matches("Часовня во имя Николая", nicholas))
        assertFalse(matches("Храм во имя Николая", parish("Никольская часовня")))
        assertTrue(matches("Часовня во имя Николая", parish("Никольская часовня")))
    }

    @Test fun everyBuildingSurvivesSeparatorsWhileTheExistingPairedDedicationStaysWhole() {
        val nicholas = parish("Никольский храм")
        for (separator in listOf(" / ", " | ", " и ")) {
            for (label in listOf("Никольский храм${separator}Казанский храм",
                "Храм во имя Николая${separator}Храм в честь Казанской иконы Божией Матери",
                "Нижний храм во имя Николая${separator}Казанский храм")) {
                assertFalse(label, matches(label, nicholas))
                assertFalse("Direct: $label", ScheduleVenue.matches(label, nicholas))
            }
            assertTrue(matches("Никольский храм${separator}Никольский храм", nicholas))
        }
        val peterPaul = parish("Петропавловский собор")
        for (label in listOf("Собор во имя святых апостолов Петра и Павла", "Собор свв. апостолов Петра и Павла")) {
            assertTrue(label, matches(label, peterPaul))
            assertTrue(label, ScheduleVenue.matches(label, peterPaul))
        }
        assertTrue(matches("Петропавловский собор", parish("Собор святых апостолов Петра и Павла")))
        assertFalse(matches("Собор во имя апостола Петра", peterPaul))
        for (selected in listOf(parish("Никольский храм", address = "Петропавловская улица"),
            parish("Храм на Петропавловской улице", shortName = "")))
            assertFalse(matches("Собор во имя святых апостолов Петра и Павла", selected))
    }

    @Test fun anUnidentifiedBuildingCannotHideBesideAMatchingBuildingInEitherOrder() {
        val selected = parish("Никольский храм")
        val named = "Храм во имя Николая"
        for (separator in listOf(" / ", " | ", " и ")) {
            for (label in listOf("$named${separator}храм", "храм$separator$named")) {
                assertNotNull(label, ScheduleVenue.inlineLabel(label))
                assertFalse(label, matches(label, selected))
                assertFalse("Direct: $label", ScheduleVenue.matches(label, selected))
                assertTrue(label, starts(simple(label), selected).events.isEmpty())
            }
        }
        assertTrue(matches("Нижний храм", parish("Нижний Никольский храм")))
        assertFalse(matches("Нижний храм | храм", parish("Нижний Никольский храм")))
    }

    @Test fun typeDedicationAndQualifierComeFromOnePrimaryOwnIdentity() {
        val church = parish("Никольский храм", shortName = "Казанская часовня")
        val chapel = "Часовня во имя Николая"
        assertFalse(matches(chapel, church))
        assertFalse(ScheduleVenue.matches(chapel, church))
        assertFalse(matches(chapel, church.copy(shortName = "Никольская")))
        assertFalse(matches("Казанская часовня", church.copy(shortName = "Никольский храм при Казанской часовне")))
        assertFalse(matches("Нижняя часовня во имя Николая",
            church.copy(name = "Нижний Никольский храм", shortName = "Никольская часовня")))
        for (name in listOf("Никольский храм при Казанской часовне", "Никольский храм в Казанской часовне",
            "Никольский храм на территории Казанской часовни", "Никольский храм г. Казанской часовни",
            "Никольский при Казанской часовне")) {
            val selected = parish(name, shortName = "")
            assertFalse(name, matches("Казанская часовня", selected))
            assertFalse(name, ScheduleVenue.matches("Казанская часовня", selected))
            assertTrue(name, starts(simple("Казанская часовня"), selected).events.isEmpty())
        }
        val associated = parish("Никольский храм при часовне", shortName = "")
        assertFalse(matches(chapel, associated))
        assertFalse(ScheduleVenue.matches(chapel, associated))
        assertTrue(matches("Храм во имя Николая", associated))
        assertTrue(matches("Нижняя часовня во имя Николая", parish("Нижняя Никольская часовня")))
        assertTrue(matches(header, parish(header)))
        assertTrue(matches("Нижняя часовня в честь Николая", parish("Нижняя часовня в честь Николая")))
    }

    @Test fun directFullHeadingsKeepLegalLocalityAndSelectedCancellations() {
        val trinity = parish("Церковь Троицы Живоначальной в Листах", shortName = "Троицы в Листах", city = "Москва")
        val legal = "Местная религиозная организация Православный приход Храма Живоначальной Троицы в Листах Московской епархии Русской Православной Церкви (Московский Патриархат)"
        assertTrue(ScheduleVenue.matches(legal, trinity))
        assertFalse(ScheduleVenue.matches(legal, parish("Храм Троицы Живоначальной", city = "Москва")))
        val cancelled = starts(simple(header, "17:00 Вечерня отменена"), child)
        assertTrue(cancelled.events.isEmpty())
        assertTrue("2026-11-13T17:00" in cancelled.withdrawnStarts)
        assertTrue(starts(simple("$header - все службы отменены"), child).events.isEmpty())
        val stock = "Богослужения в храме совершаются согласно расписанию"
        assertNull(ScheduleVenue.inlineLabel(stock))
        assertTrue(starts(simple(stock), child).events.isNotEmpty())
        assertTrue(starts(simple("$stock. Все богослужения отменены"), child).events.isEmpty())
        assertNull(ScheduleVenue.inlineLabel("Собор Архистратига Михаила и прочих Небесных Сил бесплотных"))
    }

    @Test fun realPrimaryFixturesAreUnchangedAndBoundedAmbiguousInputFailsClosed() {
        fun sha(name: String) = MessageDigest.getInstance("SHA-256").digest(ImageScheduleFixture.text(name).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals("5ef40e9f59e2bbba05ccd11ab94485634fc2218e6ab2c89747f17e053ad556f1", sha("opalikha-3.json"))
        assertEquals("0a5c34ef5286725be20f5a2fba0c7a08e686984f6d19d85eab2729a270241e39", sha("opalikha-3.hocr"))
        assertFalse(ScheduleVenue.matches(("Храм во имя Николая ").repeat(150), parish("Никольский храм")))
        assertNotNull(ScheduleVenue.inlineLabel(("Никольский храм | ").repeat(13)))
        assertFalse(matches(("Никольский храм | ").repeat(13), parish("Никольский храм")))
    }
}
