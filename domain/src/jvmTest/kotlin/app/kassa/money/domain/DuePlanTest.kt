package app.kassa.money.domain

import app.kassa.money.domain.DueNotices.Moment
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The plan the iOS app schedules (`DueNotices.plan`). Expected moments are written with java.time here, never with the rules under test.
 */
class DuePlanTest {
    private val utc = TimeZone.UTC
    private fun day(text: String): Int = LocalDate.parse(text).toEpochDay().toInt()
    private fun millis(text: String, hour: Int = 9, minute: Int = 0): Long =
        LocalDateTime.of(java.time.LocalDate.parse(text), java.time.LocalTime.of(hour, minute)).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun at(text: String, hour: Int = 0, minute: Int = 0) = Moment(day(text), hour * 60 + minute)

    private val title = "Payment due"
    private val body = "Open Kassa to see what is waiting."
    private val rules = DueRules(DueRule(true, 0, 540), DueRule(true, 0, 540), DueRule(false, 0, 540))

    private fun plan(
        items: List<DueItem>, now: Moment, nowMillis: Long = millis(LocalDate.fromEpochDays(now.day).toString(), now.minute / 60, now.minute % 60),
        r: DueRules = rules, posted: Set<String> = emptySet(), cleared: Set<String> = emptySet(),
    ) = DueNotices.plan(items, r, now, nowMillis, utc, posted, cleared, title, body)

    private val card = DueItem(DueKind.CARD, 1, "Card A", day("2026-11-14"), 124_030, clearKey = "card:1:2026-10-16")
    private val loan = DueItem(DueKind.LOAN, 2, "Car loan", day("2026-11-20"), 58_000)
    private val bill = DueItem(DueKind.BILL, 3, "Internet", day("2026-11-25"), 6_999)

    @Test
    fun theWordsAreTheSameForEveryNoticeAndHoldNoAmountOrName() {
        val out = plan(listOf(card, loan, bill), at("2026-11-01"), r = rules.copy(bill = DueRule(true, 0, 540)))
        assertEquals(3, out.size)
        for (n in out) {
            assertEquals(title, n.title)
            assertEquals(body, n.body)
            for (secret in listOf("Card A", "Car loan", "Internet", "124", "1,240", "580", "69")) {
                assertFalse(secret in n.title || secret in n.body, secret)
            }
        }
    }

    @Test
    fun aNoticeRingsAtItsTimeOnItsDayEarliestFirst() {
        val out = plan(listOf(loan, card), at("2026-11-01"))
        assertEquals(listOf("card:1:${day("2026-11-14")}", "loan:2:${day("2026-11-20")}"), out.map { it.key })
        assertEquals(millis("2026-11-14"), out[0].epochMillis)
        assertEquals(millis("2026-11-20"), out[1].epochMillis)
        assertEquals(listOf("CARD", "LOAN"), out.map { it.kind })
        assertEquals(listOf(1L, 2L), out.map { it.id })
        assertFalse(out.any { it.late })
    }

    @Test
    fun theTimeAndDaysBeforeComeFromTheRule() {
        val custom = rules.copy(card = DueRule(true, 3, 7 * 60 + 30))
        val out = plan(listOf(card), at("2026-11-01"), r = custom)
        assertEquals(millis("2026-11-11", 7, 30), out.single().epochMillis)
    }

    @Test
    fun theLeadIsNeverMoreThanFourteenDays() {
        val far = rules.copy(card = DueRule(true, 40, 540))
        val out = plan(listOf(card), at("2026-10-01"), r = far)
        assertEquals(millis("2026-10-31"), out.single().epochMillis)
        // Zero days before is the due day itself.
        assertEquals(millis("2026-11-14"), plan(listOf(card), at("2026-10-01")).single().epochMillis)
    }

    @Test
    fun aKindThatIsOffIsNotScheduled() {
        assertTrue(plan(listOf(bill), at("2026-11-01")).isEmpty())
        assertTrue(plan(listOf(card, loan), at("2026-11-01"), r = DueRules(DueRule(false, 0, 540), DueRule(false, 0, 540), DueRule(false, 0, 540))).isEmpty())
    }

    @Test
    fun aKeyIsNeverScheduledTwiceOrAfterItWasPostedOrCleared() {
        val twice = plan(listOf(card, card.copy(name = "Same card again"), loan), at("2026-11-01"))
        assertEquals(2, twice.size)
        assertEquals(1, twice.count { it.key == card.key })
        assertTrue(plan(listOf(card, loan), at("2026-11-01"), posted = setOf(card.key)).none { it.key == card.key })
        // A card is cleared by its statement's key, not its notification key.
        assertTrue(plan(listOf(card, loan), at("2026-11-01"), cleared = setOf("card:1:2026-10-16")).none { it.kind == "CARD" })
        assertTrue(plan(listOf(card), at("2026-11-01"), cleared = setOf(card.key)).isNotEmpty())
    }

    @Test
    fun aRuleChangeDoesNotRingAgainForAKeyThatWasPosted() {
        // Posted for 3 days before; the user now picks 1 day before: that key was told already.
        val later = rules.copy(card = DueRule(true, 1, 540))
        assertTrue(plan(listOf(card), at("2026-11-12"), r = later, posted = setOf(card.key)).isEmpty())
    }

    @Test
    fun onlyTheNextSixtyDaysAreSchedulable() {
        val near = card.copy(dueDay = day("2026-12-30"))
        val far = card.copy(id = 9, dueDay = day("2027-01-02"))
        val out = plan(listOf(near, far), at("2026-11-01"))
        assertEquals(listOf(near.key), out.map { it.key })
        assertEquals(60, DueNotices.PLAN_DAYS)
    }

    @Test
    fun atMostSixtyAreKeptTheEarliestOnes() {
        val many = (1L..70L).map { DueItem(DueKind.CARD, it, "C$it", day("2026-11-02") + it.toInt() % 50, 100) }
        val out = plan(many, at("2026-11-01"))
        assertEquals(60, out.size)
        assertEquals(out.map { it.epochMillis }.sorted(), out.map { it.epochMillis })
        val dropped = many.map { it.key }.toSet() - out.map { it.key }.toSet()
        assertEquals(10, dropped.size)
        assertTrue(many.filter { it.key in dropped }.all { millis(LocalDate.fromEpochDays(it.dueDay).toString()) >= out.last().epochMillis })
    }

    @Test
    fun aNoticeThatJustMissedItsTimeRingsInAMomentUntilTheGraceEnds() {
        val onTime = at("2026-11-14", 11, 59)
        val now = millis("2026-11-14", 11, 59)
        val out = plan(listOf(card), onTime, nowMillis = now)
        assertTrue(out.single().late)
        assertEquals(now + DueNotices.LATE_DELAY_MILLIS, out.single().epochMillis)
        assertEquals(1, plan(listOf(card), at("2026-11-14", 12, 0)).size)
        assertTrue(plan(listOf(card), at("2026-11-14", 12, 1)).isEmpty())
        assertTrue(plan(listOf(card), at("2026-11-15")).isEmpty())
    }

    @Test
    fun aDueDayOf31FallsOnTheLastDayOfAShortMonthAndNotifiesFromThere() {
        val rent = Recurring(id = 1, name = "Rent", categoryId = 1, accountId = 1, dayOfMonth = 31, fixedAmount = 50_000)
        val on = DueRules(DueRule(true, 0, 540), DueRule(true, 0, 540), DueRule(true, 3, 540))
        fun items(today: String) = DueItems.build(
            emptyList(), emptyMap(), emptyList(), listOf(rent), emptyMap(), emptyList(), emptyList(), LocalDate.parse(today), utc, forPhone = true,
        )
        val feb = items("2027-02-10")
        assertEquals(day("2027-02-28"), feb.single().dueDay)
        assertEquals(millis("2027-02-25"), plan(feb, at("2027-02-10"), r = on).single().epochMillis)
        val leap = items("2028-02-10")
        assertEquals(day("2028-02-29"), leap.single().dueDay)
        assertEquals(millis("2028-02-26"), plan(leap, at("2028-02-10"), r = on).single().epochMillis)
        val april = items("2026-04-02")
        assertEquals(day("2026-04-30"), april.single().dueDay)
    }

    @Test
    fun theClockOfTheZoneDecidesTheMoment() {
        val tokyo = TimeZone.of("Asia/Tokyo")
        val out = DueNotices.plan(listOf(card), rules, at("2026-11-01"), 0L, tokyo, emptySet(), emptySet(), title, body)
        assertEquals(LocalDateTime.of(2026, 11, 14, 9, 0).toInstant(ZoneOffset.ofHours(9)).toEpochMilli(), out.single().epochMillis)
    }
}
