package org.github.dayedge.timewhisper

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlin.test.*
import kotlin.time.Instant

/**
 * Whole sentences, tagged as the model would tag them, from the tagging to the candidates. "Now" is Wednesday
 * 2026-09-23 12:00 UTC.
 */
class PipelineTests {
    @Test fun theRunningExamples() = runTest {
        val a = parse("Standup [DAY tmrw] [CLOCK 10am] [W for] [LENGTH 30 minutes] [REPEAT every weekday] [PLACE @Office]")
        assertEquals(listOf("event"), a.kinds())
        assertEquals("Standup", a.event().title); assertEquals("Office", a.event().location)
        assertEquals(at("2026-09-24T10:00") to at("2026-09-24T10:30"), a.event().span())
        assertEquals(5, a.event().recurrence?.weekdays?.size)
        val b = parse("Pay rent [DAY next Friday] [LIST #home] [PRIORITY !!] [REMINDER remind me 1 day before]").task()
        assertEquals(TaskSpec("Pay rent", Due.OnDate(CivilDate(2026, 10, 2)), "home", Priority.MEDIUM, reminder = ReminderSpec.At(Moment(at("2026-10-01T09:00"), "UTC", 0))), b)
        val c = parse("Dentist [DAY next Friday] [W at] [CLOCK 2:30pm]")
        assertEquals(listOf("event", "task"), c.kinds())
        assertEquals(at("2026-10-02T14:30") to at("2026-10-02T15:30"), c.event().span())
    }
    @Test fun aCompoundOffsetGivesTheDayAndAStatedTimeTheHour() = runTest {
        // 23 Sep 12:00 + 2 months + 7 days + 3 hours is 30 Nov 15:00. A time stated beside it wins; the offset keeps the day.
        val withTime = parse("marathon race [DAY in two months, 7 days, 3 hours] [CLOCK 9am,] [W start at] [PLACE Main Square]")
        assertEquals("Marathon race", withTime.event().title)
        assertEquals(at("2026-11-30T09:00"), withTime.event().start())
        val alone = parse("marathon race [DAY in two months, 7 days, 3 hours] [W start at] [PLACE Main Square]")
        assertEquals(at("2026-11-30T15:00"), alone.event().start())
    }
    @Test fun aPlusGluedToANumberIsASignNotTheTaskMark() = runTest {
        val dentist = parse("[DAY +3 days] dentist")
        assertEquals(listOf("task", "event"), dentist.kinds(), "not forced: both are offered")
        assertEquals(Due.OnDate(CivilDate(2026, 9, 26)), dentist.task().due)
        assertEquals(listOf("task"), parse("+ buy milk [DAY tomorrow]").kinds(), "the mark still forces a task")
        assertEquals(listOf("task"), parse("+buy milk [DAY tomorrow]").kinds())
    }
    @Test fun wrappingWordsLeaveTheTitleWhereverTheyStand() = runTest {
        assertEquals("Call Bob", parse("[W can you remind me to] call Bob [DAY tomorrow]").title())
        assertEquals("Call the plumber", parse("[DAY Friday] [W at] [CLOCK 10,] [W I really need to] call the plumber").title())
        assertEquals("Send the slides", parse("send the slides [DAY tmrw] [W thanks!]").title())
    }
    @Test fun aPartNoReaderCanReadGoesBackIntoTheTitle() = runTest {
        val dinner = parse("dinner [W sometime] [DAY soon] [W at] [CLOCK 8]", intent = asTask)
        // The day was not read, so the time is not guessed onto today either.
        assertEquals("Dinner sometime soon at 8", dinner.title())
        assertNull(dinner.task().due)
        val reminder = parse("call mum [DAY tomorrow] [REMINDER with a reminder]")
        assertEquals("Call mum with a reminder", reminder.title())
    }
    @Test fun titleWordsAreNeverReadEvenWhenTheyLookLikeDates() = runTest {
        assertEquals("Call April about the lease", parse("call April about the lease [DAY tomorrow]").title())
        assertEquals("Read chapters 3-5", parse("read chapters 3-5 [DAY tonight]").title())
        assertEquals("Send the weekly report", parse("send the weekly report [DAY fri]").title())
    }
    @Test fun aLengthOrAnEndWithNoStartStaysInTheTitle() = runTest {
        val study = parse("study [W for] [LENGTH 2 hours] [DAY tomorrow]")
        assertEquals("Study for 2 hours", study.title())
        assertEquals(Due.OnDate(CivilDate(2026, 9, 24)), study.task().due)
        assertEquals("Work on slides until 5pm", parse("work on slides [W until] [END 5pm] [DAY tomorrow]").title())
    }
    @Test fun endsAndLengthsAttachToTheStart() = runTest {
        assertEquals(at("2026-09-24T15:00") to at("2026-09-24T17:00"), parse("[DAY Tomorrow] [CLOCK 3pm] workshop [W until] [END 5]").event().span())
        assertEquals(at("2026-09-24T10:00") to at("2026-09-24T10:45"), parse("[DAY Tomorrow] [CLOCK 10:00] planning [W for] [LENGTH 45 min]").event().span())
        assertEquals(at("2026-09-24T10:00") to at("2026-09-24T11:00"), parse("Workshop [DAY tomorrow] [CLOCK 10:00-11:00] [W for] [LENGTH 45 min]").event().span(), "a range keeps its own end")
        assertEquals(at("2026-09-24T22:00") to at("2026-09-25T01:00"), parse("Maintenance [DAY tomorrow] [CLOCK 22-01]").event().span(), "an end before the start is the next day")
        val late = parse("meeting [DAY today] [CLOCK 17:00-24]")
        assertEquals(at("2026-09-23T17:00") to at("2026-09-24T00:00"), late.event().span(), "24 is the midnight that ends the day")
        assertEquals("Meeting", late.title())
        val shutdown = parse("shutdown [W from] [DAY Friday] [W at] [CLOCK 22:00] [W until] [END Saturday at 06:00]").event()
        assertEquals(at("2026-09-25T22:00") to at("2026-09-26T06:00"), shutdown.span())
        val shift = parse("night shift [W from] [DAY Friday] [W at] [CLOCK 22:00] [W until] [END Saturday at 6:00]").event()
        assertEquals(at("2026-09-25T22:00") to at("2026-09-26T06:00"), shift.span(), "an hour on a later day is as written")
        val sameDay = parse("maintenance [W from] [DAY Friday] [W at] [CLOCK 14:00] [W until] [END Friday at 4:30]").event()
        assertEquals(at("2026-09-25T14:00") to at("2026-09-25T16:30"), sameDay.span(), "on the same day it comes after the start")
    }
    @Test fun wholeDaysWithNoTimeCoverThoseDays() = runTest {
        val leave = parse("[DAY today] [W for] [LENGTH 3 days] [W i'm] on sick leave")
        assertEquals(listOf("event"), leave.kinds(), "a span of days is an event")
        assertEquals("On sick leave", leave.title())
        assertEquals(EventTime.AllDay(CivilDate(2026, 9, 23), CivilDate(2026, 9, 26)), leave.event().time)
        val polish = parse("[DAY Dziś] [W na] [LENGTH 3 dni] [W jestem] na zwolnieniu lekarskim", language = "pl")
        assertEquals(EventTime.AllDay(CivilDate(2026, 9, 23), CivilDate(2026, 9, 26)), polish.event().time)
        assertEquals(at("2026-11-11T09:00") to at("2026-11-14T09:00"), parse("retreat [DAY nov 11] [CLOCK 9:00] [W for] [LENGTH 3 days]").event().span(),
            "after a time, the days give the end")
        assertEquals("Study for 2 hours", parse("study [W for] [LENGTH 2 hours] [DAY tomorrow]").title(), "hours with no time say nothing")
    }
    @Test fun aSecondDayOrTimeThatCannotJoinGoesBackToTheTitle() = runTest {
        val split = parse("meeting with s [W from] [DAY 11/11] [CLOCK 11] [DAY to 12/11] [CLOCK 13]")
        assertEquals("Meeting with s to 12/11 13", split.title(), "seen unread, not dropped")
        assertEquals(at("2026-11-11T11:00") to at("2026-11-11T12:00"), split.event().span())
        // Month first, 14-03-2027 is no date: the end is left whole, and lends the start no hour.
        val conference = parse("conference [W from] [DAY 12-03-2027] [CLOCK 8:00] [W to] [END 14-03-2027 16:00]")
        assertEquals("Conference to 14-03-2027 16:00", conference.title())
        assertEquals(at("2027-12-03T08:00") to at("2027-12-03T09:00"), conference.event().span())
        assertEquals(Due.OnDate(CivilDate(2026, 10, 2)), parse("lunch [DAY next week] [DAY friday]").task().due, "days that make one date stay")
    }
    @Test fun aRepeatEndsAtTheEndOfAYearByItsNumber() = runTest {
        val piano = parse("[REPEAT every second week] [W till] [ENDS end of 2027,] [W starting] [STARTS nov 1] [W i have] piano lessons [W from] [CLOCK 12 to 14:30]")
        val event = piano.event()
        assertEquals("Piano lessons", piano.title())
        assertEquals(at("2026-11-01T12:00") to at("2026-11-01T14:30"), event.span())
        assertEquals(CivilDate(2027, 12, 31), event.recurrence?.until)
    }
    @Test fun aRangeOfDaysIsAnAllDayEvent() = runTest {
        val holiday = parse("Holiday [DAY fri-sun]").event()
        assertEquals(EventTime.AllDay(CivilDate(2026, 9, 25), CivilDate(2026, 9, 28)), holiday.time)
        val conference = parse("conference [DAY monday] [W until] [END friday]").event()
        assertEquals(EventTime.AllDay(CivilDate(2026, 9, 28), CivilDate(2026, 10, 3)), conference.time)
    }
    @Test fun aTimeAloneIsTodayIfStillAheadElseTomorrow() = runTest {
        assertEquals(at("2026-09-23T15:00"), parse("call [W at] [CLOCK 3pm]").event().start())
        assertEquals(at("2026-09-24T09:00"), parse("call [W at] [CLOCK 9am]").event().start())
        // At 12:00 UTC it is already 02:00 on the 24th in UTC+14, so 01:00 there is next on the 25th.
        assertEquals(at("2026-09-24T11:00"), parse("call [W at] [CLOCK 01:00 UTC+14]").event().start(), "today where the time is")
    }
    @Test fun aDeadlineIsADayAndATime() = runTest {
        val due = parse("approve the payroll [W by] [DUE friday 5pm]")
        assertEquals(listOf("task", "event"), due.kinds(), "a deadline favours the task")
        assertEquals(Due.AtTime(Moment(at("2026-09-25T17:00"), "UTC", 0)), due.task().due)
    }
    @Test fun aRepeatStartsOnItsFirstDay() = runTest {
        val yoga = parse("yoga [REPEAT every tuesday] [W starting] [STARTS next week] [CLOCK 7am]").event()
        assertEquals(at("2026-09-29T07:00"), yoga.start())
        val review = parse("review [REPEAT every 2 weeks] [W at] [CLOCK 9am] [W starting] [STARTS next wednesday]").event()
        assertEquals(listOf(DayOfWeek.WEDNESDAY), review.recurrence?.weekdays?.map { it.day }, "the weekday the start names")
        assertEquals(at("2026-09-30T09:00"), review.start())
        assertEquals(at("2026-09-25T17:00"), parse("backup [REPEAT every 2 weeks] [W on] [DAY friday] [W at] [CLOCK 5pm]").event().start())
    }
    @Test fun aRepeatStopsOnADayOrAfterSoManyTimes() = runTest {
        assertEquals(CivilDate(2027, 5, 31), parse("standup [REPEAT every monday] [W until] [ENDS may] [CLOCK 9am]").event().recurrence?.until)
        // Six Mondays from Monday 28 September: the last is 2 November.
        assertEquals(CivilDate(2026, 11, 2), parse("swimming [REPEAT every monday] [ENDS 6 times] [CLOCK 9am]").event().recurrence?.until)
        // Six weeks from Monday 28 September: up to Sunday 8 November.
        assertEquals(CivilDate(2026, 11, 8), parse("swimming [REPEAT every monday] [W for] [ENDS 6 weeks] [CLOCK 9am]").event().recurrence?.until)
        assertEquals(CivilDate(2026, 10, 31), parse("[W for] [ENDS the next six] [REPEAT saturdays] swimming lessons [W at] [CLOCK 9]").event().recurrence?.until)
        assertEquals(CivilDate(2026, 11, 30), parse("book club [W starting] [STARTS next wed,] [W then] [REPEAT every second week] [W till] [ENDS end of nov] [W from] [CLOCK 11 to 12]").event().recurrence?.until)
    }
    @Test fun aRangeOfDaysBoundsARepeatAtBothEnds() = runTest {
        val race = parse("car race [REPEAT every two weeks] [W from] [STARTS 1 nov to 1 dec,] [CLOCK 11-12]").event()
        assertEquals(at("2026-11-01T11:00"), race.start())
        assertEquals(CivilDate(2026, 12, 1), race.recurrence?.until)
        assertEquals("Car race", race.title)
    }
    @Test fun withoutARepeatAStartIsTheDayAndALastDayTheEnd() = runTest {
        assertEquals(Due.OnDate(CivilDate(2026, 9, 28)), parse("water the plants [W from] [STARTS monday]").task().due)
        assertEquals("Lessons for 6 weeks", parse("lessons [DAY tomorrow] [W for] [ENDS 6 weeks]").title(), "a count with nothing to count stays")
    }
    @Test fun repeatPartsSaidApartAreOneRepeat() = runTest {
        val report = parse("send the report [REPEAT weekly] [W at] [CLOCK 9] [W on] [REPEAT fridays]").event().recurrence
        assertEquals(RecurrenceSpec(Frequency.WEEKLY, weekdays = listOf(WeekdayRule(DayOfWeek.FRIDAY))), report)
        // A part that means nothing alone still joins the others: "each" … "monday".
        assertEquals(listOf(DayOfWeek.MONDAY), parse("review [REPEAT each] [CLOCK 9am] [REPEAT monday]").event().recurrence?.weekdays?.map { it.day })
        assertEquals("Review", parse("review [REPEAT each] [CLOCK 9am] [REPEAT monday]").title())
        val mentoring = parse("mentoring [REPEAT every 3 weeks] [REPEAT every monday] [W at] [CLOCK 11am]").event()
        assertEquals(3 to listOf(DayOfWeek.MONDAY), mentoring.recurrence?.interval to mentoring.recurrence?.weekdays?.map { it.day })
    }
    @Test fun aPlaceOrACalendarOnlyConcernsTheEvent() = runTest {
        val pickup = parse("Pickup [DAY tomorrow] [W at] [PLACE Post Office]")
        assertEquals("Pickup", pickup.event().title); assertEquals("Post Office", pickup.event().location)
        assertEquals("Pickup at Post Office", pickup.task().title, "the place goes back with the word that attaches it")
        val context = fixtureContext.copy(destinations = fixtureContext.destinations + Destination("family", "Family", DestinationKind.CALENDAR))
        val party = parse("party [DAY sat] [CLOCK 8pm] [W on] [CALENDAR the family calendar]", context = context)
        assertEquals("family", party.event().calendarId); assertEquals("Party", party.event().title)
    }
    @Test fun priorityAndKind() = runTest {
        val urgent = parse("[PRIORITY urgent] call the bank [DAY tmrw]", urgency = mapOf(Priority.HIGH to 0.9, Priority.NONE to 0.1))
        assertEquals(Priority.HIGH, urgent.task().priority)
        assertEquals(listOf("task", "event"), parse("Review [DAY tomorrow] [CLOCK 13-14] [PRIORITY !!]").kinds())
        assertEquals(listOf("event"), parse("Dentist [DAY tomorrow] [KIND t:e]").kinds())
        assertEquals(listOf("task"), parse("[KIND todo] call the bank [DAY tmrw] [CLOCK 3pm]").kinds())
        assertEquals("Call the bank", parse("[KIND todo] call the bank").title())
    }
    @Test fun aLeadingPlusForcesATaskAndKeepsPositions() = runTest {
        val text = "  + 📅 Café tomorrow #work"
        val parser = TimeWhisper(Configuration(), { MarkupTagger(listOf("T", "T", "T", "DAY", "LIST").map { (if (it == "T") TokenLabel.TITLE else TokenLabel.valueOf(it)) to 0.95 }) },
            spellCheckerFactory = NoSpellCheckerFactory)
        try {
            val task = assertIs<Candidate.Task>(parser.parse(text, fixtureContext).single())
            val source = task.spans.map { text.substring(it.range.offset, it.range.end) }
            assertEquals(listOf("tomorrow", "#work"), source)
            assertEquals("📅 Café", task.spec.title)
        } finally { parser.close() }
    }
    @Test fun whatTheTaggerReadsTheTextAsDecidesWhenNothingElseDoes() = runTest {
        assertEquals(listOf("task"), parse("buy groceries", intent = asTask).kinds())
        assertEquals(emptyList(), parse("groceries").kinds())
        assertEquals(emptyList(), parse("[W already] paid the bill [DAY yesterday]", intent = asNothing))
        assertEquals(listOf("task", "event"), parse("[W remind me to] call Bob [DAY Friday] [W at] [CLOCK 5]", intent = asTask).kinds())
    }
    @Test fun aDateThatDoesNotExistCreatesNothing() = runTest {
        assertEquals(emptyList(), parse("dentist [DAY February 30 2026]"))
        assertEquals(emptyList(), parse("dentist [DAY 2026-02-30] [CLOCK 10am]"))
    }
    @Test fun aNumericDateFollowsTheDateOrder() = runTest {
        val dayFirst = Configuration(dateOrder = DateOrder.DAY_MONTH)
        assertEquals(at("2026-11-10T10:00"), parse("dentist [DAY 10/11/2026] [CLOCK 10am]", configuration = dayFirst).event().start())
        assertEquals(at("2026-10-11T10:00"), parse("dentist [DAY 10/11/2026] [CLOCK 10am]").event().start())
    }
    @Test fun onlyWordsInPartsAboutTimeAreSpellCorrected() = runTest {
        val asked = mutableListOf<String>()
        val checker = SpellCheckerFactory { object : SpellChecker {
            override suspend fun suggestions(word: String, language: String): List<String> {
                asked += word; return mapOf("tomorow" to listOf("tomorrow"), "marc" to listOf("march")).getValue(word)
            }
        } }
        val call = parse("call Marc [DAY tomorow]", checker = checker)
        assertEquals("Call Marc", call.title())
        assertEquals(Due.OnDate(CivilDate(2026, 9, 24)), call.task().due)
        assertEquals(listOf("tomorow"), asked)
    }
    @Test fun aDateFinderReadsADayTheRulesCannot() = runTest {
        val finder = DateFinderFactory { object : DateFinder {
            override suspend fun find(text: String, request: DateFinderRequest): List<DateMatch> =
                if (text == "Thanksgiving") listOf(DateMatch(SourceRange(0, 12), DateMatchComponents(CivilDate(2026, 11, 26), null))) else emptyList()
        } }
        val dinner = parse("dinner [W on] [DAY Thanksgiving]", finder = finder)
        assertEquals(Due.OnDate(CivilDate(2026, 11, 26)), dinner.task().due)
        assertEquals("Dinner", dinner.title())
        assertEquals(Source.FINDER, dinner.first().spans.single().source)
    }
    @Test fun polishSentences() = runTest {
        val call = parse("[W Przypomnij mi] [CLOCK o 17] zadzwonić do mamy [DAY jutro]", "pl", intent = asTask)
        assertEquals("Zadzwonić do mamy", call.title())
        assertEquals(at("2026-09-24T17:00"), call.event().start())
        val workshop = parse("warsztaty [DAY jutro] [CLOCK od 14 do 16]", "pl").event()
        assertEquals(at("2026-09-24T14:00") to at("2026-09-24T16:00"), workshop.span())
        val bill = parse("zapłacić za internet [DUE 25 października do 18:00]", "pl")
        assertEquals("Zapłacić za internet", bill.title())
        assertEquals(Due.AtTime(Moment(at("2026-10-25T18:00"), "UTC", 0)), bill.task().due)
        val daily = parse("[STARTS od przyszłego poniedziałku] [REPEAT codziennie w dni robocze] [CLOCK o 9:00] daily, [LENGTH 15 minut]", "pl").event()
        assertEquals("Daily", daily.title)
        assertEquals(at("2026-09-28T09:00") to at("2026-09-28T09:15"), daily.span())
        assertEquals(5, daily.recurrence?.weekdays?.size)
        val flight = parse("lot do Bostonu [DAY jutro] [CLOCK o 9] [REMINDER ustaw przypomnienie 10 min wcześniej]", "pl").event()
        assertEquals(listOf(ReminderSpec.Before(10)), flight.reminders)
        assertEquals("Lot do Bostonu", flight.title)
    }
    @Test fun aReminderAtASetTimeIsCompletedFromTheEntry() = runTest {
        val rent = parse("pay the rent [W by] [DUE friday] [REMINDER remind me thursday at 9am]").task()
        assertEquals(Due.OnDate(CivilDate(2026, 9, 25)), rent.due)
        assertEquals(ReminderSpec.At(Moment(at("2026-09-24T09:00"), "UTC", 0)), rent.reminder, "the reminder's time is not the due time")
        val dentist = parse("dentist [DAY thu] [W at] [CLOCK 3pm,] [REMINDER ping me at 1]").event()
        assertEquals(listOf(ReminderSpec.At(Moment(at("2026-09-24T13:00"), "UTC", 0))), dentist.reminders, "a time alone is on the entry's day")
        val fees = parse("[REMINDER remind me on the 24th] pay the school fees [W by] [DUE the 25th]").task()
        assertEquals(ReminderSpec.At(Moment(at("2026-09-24T09:00"), "UTC", 0)), fees.reminder, "a day alone is at the configured time")
        assertEquals("Pay the school fees", fees.title)
    }
    @Test fun aReminderBeforeIsRelativeToATimeAndADayOnADate() = runTest {
        val bins = parse("bins [DAY fri] [CLOCK 7am,] [REMINDER remind me day before]").task()
        assertEquals(ReminderSpec.Before(1440), bins.reminder, "before a time it moves with the entry")
        val trash = parse("trash collection [DAY next fri,] [REMINDER remind me day before]").task()
        assertEquals(ReminderSpec.At(Moment(at("2026-10-01T09:00"), "UTC", 0)), trash.reminder, "before a day it is at the reminder time, not midnight")
        val nine = parse("bins [DAY fri] [CLOCK 7am,] [REMINDER remind me at 9 the day before]").task()
        assertEquals(ReminderSpec.At(Moment(at("2026-09-24T09:00"), "UTC", 0)), nine.reminder)
        val insurance = parse("pay insurance [W by] [DUE dec 15,] [REMINDER remind me a month before]").task()
        assertEquals(ReminderSpec.At(Moment(at("2026-11-15T09:00"), "UTC", 0)), insurance.reminder)
        val domain = parse("renew domain, [REMINDER remind me a month before end of year]").task()
        assertEquals(ReminderSpec.At(Moment(at("2026-11-30T09:00"), "UTC", 0)), domain.reminder)
        val after = parse("call Bob [DAY fri] [CLOCK 3pm,] [REMINDER remind me 2 hours after]").task()
        assertNull(after.reminder, "after is not read"); assertEquals("Call Bob remind me 2 hours after", after.title)
    }
    @Test fun aTimeZoneNamedAfterAClockIsWhereTheTimeIs() = runTest {
        assertEquals(at("2026-09-24T19:00"), parse("call with Sam [DAY tomorrow] [W at] [CLOCK 3pm EST]").event().start())
        assertEquals(at("2027-01-15T20:00"), parse("review [DAY January 15] [W at] [CLOCK 3pm EST]").event().start(), "winter time there")
        assertEquals(at("2026-09-24T08:00"), parse("sync [DAY tomorrow] [CLOCK 10:00 UTC+2]").event().start())
        val warsaw = ParseContext(Instant.parse("2026-09-23T12:00:00Z"), "Europe/Warsaw")
        assertEquals(at("2026-09-24T19:00"), parse("rozmowa [DAY jutro] [CLOCK o 15:00 EST]", "pl", context = warsaw).event().start())
    }
}
