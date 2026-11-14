package ru.liturgyguide.ui

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.schedule.*

class ScheduleCandidatePresentationTest {
    private val church = Parish("osm:way:42", "Храм святителя Николая", "Николая", "Москва",
        "Осенняя улица, 32 с1", "", "", "Europe/Moscow", "", "", "", "", emptyList(), "", "",
        55.76964415, 37.4061332)
    private fun row(provider: String = "public_index", url: String = "https://parish.example/schedule/") =
        ScheduleSourceCandidate(selectedIdentity = ScheduleEntityIdentity.fingerprint(church),
            provider = provider, providerLabel = provider, providerObjectId = "42", name = church.name,
            address = church.address, sourceUrl = url, evidenceUrl = "https://index.example/church/42",
            evidenceSha256 = "a".repeat(64), checkedAt = "2026-11-13T06:00:00Z",
            latitude = church.latitude!!, longitude = church.longitude!!,
            outcome = ScheduleIdentityOutcome.INSUFFICIENT, reason = "Проверьте название и адрес")
    private fun state(vararg rows: ScheduleSourceCandidate) = InternetScheduleState(church.id,
        InternetScheduleStatus.NO_WEBSITE, checkedAt = "2026-11-13T06:01:00Z",
        candidateSearchIdentity = ScheduleEntityIdentity.fingerprint(church), sourceCandidates = rows.toList())
    private fun binding(row: ScheduleSourceCandidate) = ScheduleSiteBinding(
        ScheduleEntityIdentity.POLICY_VERSION, row.selectedIdentity, row.name, ScheduleEntityScope.CHURCH,
        ScheduleBindingMethod.USER_CONFIRMED, row.sourceUrl, row.evidenceUrl,
        candidateConfirmation = ScheduleCandidateConfirmation(1, ScheduleCandidateConfirmation.TYPE,
            row.selectedIdentity, row, "2026-11-13T06:01:00Z"))

    @Test fun duplicateExactUrlIsOneViewButKeepsEveryRawRowAndProofUnchanged() {
        val first = row(); val second = row("second_index"); val input = state(first, second)
        val before = Gson().toJson(input)
        val group = scheduleCandidateGroups(church, input).single()
        assertEquals(first.sourceUrl, group.sourceUrl)
        assertEquals(2, group.providerCount)
        assertEquals(listOf(first.id, second.id), group.rows.map { it.id })
        assertSame(first, group.rows[0]); assertSame(second, group.rows[1])
        assertSame(first, group.confirmationCandidate)
        assertEquals(before, Gson().toJson(input))
        assertTrue(group.rows.all { it.canConfirm(church) })
    }

    @Test fun checkedCurrentContactRowRemainsVisibleAndOwnsTheSamePrimaryRawId() {
        val first = row().copy(address = "")
        val contact = row("second_index").copy(address = "Осенняя улица, 33 к3", currentContactAddress = church.address,
            currentContactUrl = "https://parish.example/contacts/", currentContactSha256 = "b".repeat(64),
            linkedEmail = "parish@example.org")
        assertTrue(first.canConfirm(church)); assertTrue(contact.canConfirm(church))
        val group = scheduleCandidateGroups(church, state(first, contact)).single()
        assertSame(contact, group.representative); assertSame(contact, group.confirmationCandidate)
        assertEquals(church.address, group.representative.currentContactAddress)
        assertSame(first, group.rows[0]); assertSame(contact, group.rows[1])
    }

    @Test fun schemeHostPathAndQueryAreNeverCoalescedAsAliases() {
        val urls = listOf("http://parish.example/schedule/", "https://parish.example/schedule/",
            "https://www.parish.example/schedule/", "https://parish.example/other/",
            "https://parish.example/schedule/?part=one", "https://parish.example/schedule/?part=two")
        val input = state(*urls.mapIndexed { index, url -> row("index_${index}", url) }.toTypedArray())
        assertEquals(urls, scheduleCandidateGroups(church, input).map { it.sourceUrl })
    }

    @Test fun conflictInAnyRawRowCannotBeHiddenByPositiveOrDismissedRows() {
        val first = row(); val conflict = row("second_index").copy(outcome = ScheduleIdentityOutcome.CONFLICT,
            reason = "Адрес противоречит выбранному храму")
        for (dismissed in listOf(emptyList(), listOf(conflict.id))) {
            val group = scheduleCandidateGroups(church, state(first, conflict).copy(dismissedCandidateIds = dismissed)).single()
            assertNull(group.confirmationCandidate)
            assertSame(conflict, group.conflicts.single())
            assertSame(conflict, group.rows[1])
        }
    }

    @Test fun ancestorPathConflictStillBlocksItsCoveredChildButNotAnotherHost() {
        val first = row(); val conflict = row("second_index", "https://parish.example/")
            .copy(outcome = ScheduleIdentityOutcome.CONFLICT)
        val unrelated = row("third_index", "https://another.example/")
        val groups = scheduleCandidateGroups(church, state(first, conflict, unrelated))
        assertNull(groups.single { it.sourceUrl == first.sourceUrl }.confirmationCandidate)
        assertSame(unrelated, groups.single { it.sourceUrl == unrelated.sourceUrl }.confirmationCandidate)
    }

    @Test fun dismissingOneIdKeepsTheOtherRowAndNeverApprovesIt() {
        val first = row(); val second = row("second_index")
        val input = state(first, second).copy(dismissedCandidateIds = listOf(first.id))
        val group = scheduleCandidateGroups(church, input).single()
        assertSame(second, group.confirmationCandidate)
        assertEquals(listOf(first.id, second.id), group.rows.map { it.id })
        assertNull(input.siteBinding)
        assertTrue(scheduleCandidateGroups(church, input.copy(dismissedCandidateIds = listOf(first.id, second.id))).isEmpty())
    }

    @Test fun approvedSourceDuplicateHasNoNewConfirmationObligation() {
        val first = row(); val second = row("second_index")
        val input = state(first, second).copy(siteBinding = binding(first))
        assertTrue(requireNotNull(input.siteBinding).validFor(church, first.sourceUrl, first.evidenceUrl))
        val group = scheduleCandidateGroups(church, input).single()
        assertTrue(group.alreadyChosen); assertNull(group.confirmationCandidate)
        assertFalse(hasReviewableScheduleSources(church, input))
        assertFalse(hasUnreviewedScheduleSources(input))
        assertTrue(scheduleCandidateGroups(church, state(first).copy(siteBinding = binding(first))).isEmpty())
    }

    @Test fun approvedIdCannotConcealAFreshConflictAtItsOwnSource() {
        val approved = row(); val duplicate = row("second_index")
        val conflict = approved.copy(outcome = ScheduleIdentityOutcome.CONFLICT, reason = "Новое противоречие")
        val input = state(conflict, duplicate).copy(siteBinding = binding(approved))
        val group = scheduleCandidateGroups(church, input).single()
        assertTrue(group.alreadyChosen); assertNull(group.confirmationCandidate)
        assertSame(conflict, group.conflicts.single())
        assertTrue(hasReviewableScheduleSources(church, input))
    }

    @Test fun historyRemainsABlockAndNeverChangesRawProofsOrApproval() {
        val first = row()
        val input = state(first).copy(sourceHasWithdrawals = true, publicationRejected = true)
        val group = scheduleCandidateGroups(church, input).single()
        assertTrue(group.blockedByHistory); assertSame(first, group.confirmationCandidate)
        assertNull(input.siteBinding); assertTrue(input.publicationRejected); assertTrue(input.sourceHasWithdrawals)
    }

    @Test fun otherParishChangedIdentityInvalidRowsAndDuplicateIdsCannotConfirm() {
        val first = row()
        assertTrue(scheduleCandidateGroups(church, state(first).copy(parishId = "other")).isEmpty())
        val changed = church.copy(address = "Осенняя улица, 99")
        assertTrue(scheduleCandidateGroups(changed, state(first)).isEmpty())
        assertFalse(hasUnreviewedScheduleSources(state(first), changed))
        assertFalse(scheduleEmptyTitle(state(first), false, changed).contains("Найдены"))
        assertFalse(requireNotNull(scheduleCheckReceipt(state(first), church.timezone, changed)).startsWith("Найдены"))
        assertTrue(scheduleCandidateGroups(church, state(first.copy(sourceUrl = "javascript:alert(1)"))).isEmpty())
        val ambiguous = scheduleCandidateGroups(church, state(first, first.copy(reason = "Другая версия той же карточки"))).single()
        assertNull(ambiguous.confirmationCandidate)
    }

    @Test fun pendingCopyAndReceiptAgreeAndDoNotClaimPublication() {
        val input = state(row(), row("second_index"))
        assertEquals("Найдены сайты для проверки", scheduleEmptyTitle(input, false))
        assertEquals("Проверьте название и адрес храма на найденном сайте", scheduleEmptyDescription(input, false))
        val receipt = requireNotNull(scheduleCheckReceipt(input, church.timezone))
        assertTrue(receipt.startsWith("Найдены сайты, ожидается проверка"))
        assertFalse(receipt.contains("не найден")); assertFalse(receipt.contains("Расписание получено"))
        assertTrue(hasReviewableScheduleSources(church, input))
        assertNull(scheduleCheckReceipt(input.copy(loading = true), church.timezone))
    }

    @Test fun rejectedWithdrawnAndUnverifiedAuthorityPrecedePendingSuggestions() {
        val input = state(row())
        val rejected = input.copy(publicationRejected = true, sourceNeedsVerification = true)
        assertTrue(requireNotNull(scheduleCheckReceipt(rejected, church.timezone)).startsWith("Расписание не подтверждено источником"))
        assertEquals("Расписание требует уточнения", scheduleEmptyTitle(rejected, false))
        val withdrawn = input.copy(sourceHasWithdrawals = true)
        assertTrue(requireNotNull(scheduleCheckReceipt(withdrawn, church.timezone)).startsWith("В источнике есть отмены или изменения"))
        assertTrue(scheduleEmptyDescription(withdrawn).contains("Обновите его перед сменой сайта"))
        val pendingIdentity = input.copy(sourceNeedsVerification = true)
        assertTrue(requireNotNull(scheduleCheckReceipt(pendingIdentity, church.timezone)).startsWith("Источник требует проверки"))
    }

    @Test fun actualReadyPublicationAndNoCandidateFailureKeepTheirOwnReceiptMeaning() {
        val input = state(row()).copy(status = InternetScheduleStatus.READY,
            publication = SchedulePublication(church.id, church.timezone, "https://own.example/", "https://own.example/",
                "2026-11-13T06:00:00Z", emptyList()))
        assertTrue(requireNotNull(scheduleCheckReceipt(input, church.timezone)).startsWith("Расписание получено"))
        val empty = state().copy(candidateSearchUnavailable = true)
        assertFalse(hasReviewableScheduleSources(church, empty))
        assertTrue(requireNotNull(scheduleCheckReceipt(empty, church.timezone)).startsWith("Источник расписания не найден"))
    }
}

