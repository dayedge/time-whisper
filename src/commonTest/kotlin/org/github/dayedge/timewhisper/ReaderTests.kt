package org.github.dayedge.timewhisper

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Instant
import kotlin.test.*
import org.github.dayedge.timewhisper.taskorevent.Readers
import org.github.dayedge.timewhisper.temporal.Fact
import org.github.dayedge.timewhisper.temporal.Amount
import org.github.dayedge.timewhisper.temporal.Read
import org.github.dayedge.timewhisper.temporal.Scope
import org.github.dayedge.timewhisper.text.Lexer
import org.github.dayedge.timewhisper.text.Lexicon
import org.github.dayedge.timewhisper.text.tokenize

/**
 * Each reader on the words of one part, as the tagger would hand them over. "Now" is Wednesday 2026-09-23 12:00 UTC.
 * A part a reader cannot read gives null: those words go back into the title.
 */
class ReaderTests {
    private val lexers = Configuration.supportedLanguages.associateWith { Lexer(Lexicon.forLanguage(it)!!) { NoSpellChecker() } }
    private val withCalendars = fixtureContext.copy(destinations = fixtureContext.destinations + Destination("team", "Team", DestinationKind.CALENDAR))

    private suspend fun read(label: TokenLabel, text: String, language: String = "en", urgency: Map<Priority, Double> = emptyMap()): Read? {
        val lexemes = lexers.getValue(language).lex(tokenize(text)) { false }
        val order = if (language == "pl") DateOrder.DAY_MONTH else DateOrder.MONTH_DAY
        return Readers[label]!!.read(lexemes, Scope(withCalendars, Configuration(language = language), order, urgency))
    }
    private suspend fun fact(label: TokenLabel, text: String, language: String = "en"): Fact? = (read(label, text, language) as? Read.When)?.fact
    private suspend fun day(text: String, language: String = "en"): String? = fact(TokenLabel.DAY, text, language)?.let { f ->
        listOfNotNull(f.day, f.endDay).joinToString("..") + (if (f.allDay) " all day" else "") + (if (f.granularity != Granularity.DAY) " ${f.granularity}" else "")
    }
    private suspend fun clock(text: String, language: String = "en"): String? = fact(TokenLabel.CLOCK, text, language)?.let { listOfNotNull(it.start, it.end).joinToString("-") }
    private suspend fun repeat(text: String, language: String = "en"): RecurrenceSpec? = (read(TokenLabel.REPEAT, text, language) as? Read.Repeat)?.rule

    @Test fun days() = runTest {
        mapOf(
            "tomorrow" to "2026-09-24", "on friday" to "2026-09-25", "next friday" to "2026-10-02", "friday next week" to "2026-10-02",
            "the 15th" to "2026-10-15", "October 5" to "2026-10-05", "the 5th of October" to "2026-10-05", "tomorrow morning" to "2026-09-24",
            "this morning" to "2026-09-23", "mon-wed" to "2026-09-28..2026-09-30", "friday to sunday" to "2026-09-25..2026-09-27",
            "from the 12th through the 14th" to "2026-10-12..2026-10-14", "3-5 march" to "2027-03-03..2027-03-05",
            "all day saturday" to "2026-09-26 all day", "in 3 days" to "2026-09-26", "end of month" to "2026-09-30",
            "next week" to "2026-09-28 WEEK", "in november" to "2026-11-01 MONTH", "next march" to "2027-03-01 MONTH",
            "may 4 this year" to "2026-05-04", "friday the 13th" to "2026-11-13", "11.11.2026" to "2026-11-11", "2026-11-11" to "2026-11-11",
            "the last friday of the month" to "2026-09-25", "week 40" to "2026-09-28", "tonight" to "2026-09-23",
            "12th-14th" to "2026-10-12..2026-10-14", "the day after tomorrow" to "2026-09-25", "later today" to "2026-09-23",
            "tomorrow in the morning" to "2026-09-24", "last night" to "2026-09-22", "past week" to "2026-09-14 WEEK",
            "coming friday" to "2026-09-25", "end of nov" to "2026-11-30", "start of march" to "2027-03-01",
            "end of nov next year" to "2027-11-30", "end of may 2028" to "2028-05-31", "start of next november" to "2026-11-01",
            "end of 11/12/2029" to "2029-11-12", "nov next year" to "2027-11-01 MONTH", "from 1 nov to 1 dec" to "2026-11-01..2026-12-01",
            "from next monday to end of january" to "2026-09-28..2027-01-31", "from nov 6 till end of may" to "2026-11-06..2027-05-31",
            "from next friday to next tuesday" to "2026-10-02..2026-10-06", "3/4-3/7" to "2027-03-04..2027-03-07",
            "2027" to "2027-01-01 YEAR", "year 2027" to "2027-01-01 YEAR", "end of 2027" to "2027-12-31", "beginning of 2027" to "2027-01-01",
        ).forEach { (text, expected) -> assertEquals(expected, day(text), text) }
        mapOf(
            "w piątek" to "2026-09-25", "jutro rano" to "2026-09-24", "w przyszłym tygodniu" to "2026-09-28 WEEK", "15 października" to "2026-10-15",
            "od piątku do niedzieli" to "2026-09-25..2026-09-27", "ostatni piątek miesiąca" to "2026-09-25", "za tydzień" to "2026-09-30",
            "w najbliższy czwartek" to "2026-09-24", "dwudziestego piątego października" to "2026-10-25", "najbliższą sobotę" to "2026-09-26",
            "w najbliższy weekend" to "2026-09-26..2026-09-27", "trzydziestego pierwszego października" to "2026-10-31",
            "koniec listopada" to "2026-11-30", "do pojutrza" to "2026-09-25",
            "2027 roku" to "2027-01-01 YEAR", "do końca 2027 roku" to "2027-12-31", "końca 2027" to "2027-12-31",
        ).forEach { (text, expected) -> assertEquals(expected, day(text, "pl"), text) }
        // What is not a day stays unread: a bare number, a word the lexicon does not know, two days that are not a range.
        for (text in listOf("15", "sometime soon", "after the trip", "mon and wed", "2027 2028", "202")) assertNull(day(text), text)
    }
    @Test fun tonightCarriesARoughHour() = runTest {
        val tonight = assertNotNull(fact(TokenLabel.DAY, "tonight"))
        assertEquals("20:00", tonight.start.toString())
    }
    @Test fun clocks() = runTest {
        mapOf(
            "at 3pm" to "15:00", "11" to "11:00", "at 5" to "17:00", "noon" to "12:00", "from 10 to 11" to "10:00-11:00",
            "3-5pm" to "15:00-17:00", "2 and 4" to "14:00-16:00", "half past 3" to "15:30", "quarter to 5" to "16:45",
            "eight thirty" to "08:30", "7:30-8 am" to "07:30-08:00", "@5pm" to "17:00", "3pm to 5" to "15:00-17:00", "around 7" to "19:00",
            "half seven" to "19:30", "at a quarter to five" to "16:45", "around 3 in the afternoon" to "15:00", "at eight" to "08:00", "at 7 sharp" to "19:00",
        ).forEach { (text, expected) -> assertEquals(expected, clock(text), text) }
        mapOf(
            "o 17" to "17:00", "od 14 do 16" to "14:00-16:00", "kwadrans po piątej po południu" to "17:15", "o 9.10" to "09:10",
            "między 14 a 16" to "14:00-16:00", "ósma trzydzieści" to "08:30", "w południe" to "12:00", "od 18 aż do 20:30" to "18:00-20:30",
            "o drugiej w nocy" to "02:00", "za godzinę" to "13:00", "za pół godziny" to "12:30",
            "o dwudziestej drugiej" to "22:00", "pół ósmej" to "19:30", "o 15:00 pt" to null,
        ).forEach { (text, expected) -> assertEquals(expected, clock(text, "pl"), text) }
        assertNull(clock("lunchtime"))
    }
    @Test fun aClockCarriesItsZoneAndElapsedTimeItsDay() = runTest {
        assertEquals("America/New_York", fact(TokenLabel.CLOCK, "at 3pm EST")?.zone?.id)
        val soon = assertNotNull(fact(TokenLabel.CLOCK, "in 2 hours"))
        assertEquals("2026-09-23 14:00", "${soon.day} ${soon.start}")
    }
    @Test fun lengths() = runTest {
        mapOf("45 min" to 45, "1h30m" to 90, "a 30-minute" to 30, "an hour" to 60, "half an hour" to 30, "two hours" to 120, "a one-hour" to 60,
            "a half-hour" to 30)
            .forEach { (text, minutes) -> assertEquals(minutes, fact(TokenLabel.LENGTH, text)?.duration, "en: $text") }
        assertEquals(30, fact(TokenLabel.CLOCK, "in about half an hour")?.let { (it.start!!.hour - 12) * 60 + it.start!!.minute })
        mapOf("przez godzinę" to 60, "na pół godziny" to 30, "przez 1,5h" to 90, "potrwa godzinę" to 60)
            .forEach { (text, minutes) -> assertEquals(minutes, fact(TokenLabel.LENGTH, text, "pl")?.duration, "pl: $text") }
        // An event may last days: "for 2 days", "for 1 day and 6 hours". A month is no fixed length.
        assertEquals(2880, fact(TokenLabel.LENGTH, "2 days")?.duration)
        assertNull(fact(TokenLabel.LENGTH, "a month"))
    }
    @Test fun compoundAmounts() = runTest {
        // Now is Wednesday 23 September 2026, 12:00 UTC. Months first, then weeks and days, then the clock.
        suspend fun day(text: String, label: TokenLabel = TokenLabel.DAY, language: String = "en") = fact(label, text, language)?.let { "${it.day} ${it.start}" }
        assertEquals("2026-11-30 15:00", day("in 2 months, 7 days and 3 hours"))
        assertEquals("2026-10-16 null", day("in 3 weeks 2 days"))
        assertEquals("2026-10-16 null", day("in 3w2d"))
        assertEquals("2026-10-13 null", day("within 3 weeks minus a day", TokenLabel.DUE))
        assertEquals("2026-10-13 null", day("in 3 weeks without a day"))
        assertEquals("2026-10-04 00:00", day("a week and a half from now"), "10 days and 12 hours")
        assertEquals("2026-10-04 00:00", day("in 1.5 weeks"))
        assertEquals("2026-09-06 null", day("2 weeks and 3 days ago"))
        assertEquals("2026-11-30 null", day("za 2 miesiące i 7 dni", language = "pl"))
        assertEquals("2026-10-13 null", day("za 3 tygodnie bez jednego dnia", language = "pl"))
        assertEquals("2026-10-04 00:00", day("za półtora tygodnia", language = "pl"))
        assertEquals("2026-09-23 14:15", day("in 2 hours and 15 minutes", TokenLabel.CLOCK))
        // A list, units going up, half a month, and "m" for minutes before days are not one amount: nothing is guessed.
        for (text in listOf("in 3 weeks, 7 weeks, 8 weeks", "in 7 days and 2 months", "in 1.5 months", "in 2m7d")) assertNull(fact(TokenLabel.DAY, text), text)
        // Lengths, how long a repeat runs, and a reminder's lead are the same amounts.
        mapOf("2h 30m" to 150, "3h15min" to 195, "1 day and 6 hours" to 1800, "an hour and a half" to 90)
            .forEach { (text, minutes) -> assertEquals(minutes, fact(TokenLabel.LENGTH, text)?.duration, text) }
        assertEquals(135, fact(TokenLabel.LENGTH, "przez 2 godziny i 15 minut", "pl")?.duration)
        assertEquals(Read.SeriesEnd(span = Amount(months = 3, weeks = 2)), read(TokenLabel.ENDS, "3 months and 2 weeks"))
        assertNull(read(TokenLabel.ENDS, "3 hours"), "hours are neither how long a repeat runs nor a count of times")
        // A decimal is written as the language writes it: "1,5h" is an hour and a half in Polish, not in English.
        assertEquals(90, fact(TokenLabel.LENGTH, "1,5h", "pl")?.duration)
        assertNull(fact(TokenLabel.LENGTH, "1,5h"))
        assertEquals(Amount(days = 1, minutes = 120), (read(TokenLabel.REMINDER, "remind me 1 day 2 hours before") as? Read.Remind)?.lead)
        // On the calendar a month after 31 January is the last day of February; hours carry past midnight.
        assertEquals(LocalDate(2027, 2, 28), Amount(months = 1).after(LocalDate(2027, 1, 31)))
        assertEquals(Instant.parse("2026-09-25T01:00:00Z"), Amount(days = 1, minutes = 180).after(Instant.parse("2026-09-23T22:00:00Z"), TimeZone.UTC))
    }
    @Test fun ends() = runTest {
        assertEquals(5, fact(TokenLabel.END, "5")?.detachedEnd?.hour)
        assertEquals(16, fact(TokenLabel.END, "do 16", "pl")?.detachedEnd?.hour)
        assertEquals("2026-09-25", fact(TokenLabel.END, "friday")?.day.toString())
        val both = assertNotNull(fact(TokenLabel.END, "saturday at 6"))
        assertEquals("2026-09-26 18:00", "${both.day} ${both.detachedEnd?.time()}")
    }
    @Test fun deadlines() = runTest {
        mapOf(
            "friday" to "2026-09-25 null", "5pm" to "null 17:00", "friday 5pm" to "2026-09-25 17:00", "eow" to "2026-09-27 null",
            "within 30 days" to "2026-10-23 null", "the 14th" to "2026-10-14 null", "eod" to "2026-09-23 null",
            "eow at the latest" to "2026-09-27 null",
        ).forEach { (text, expected) -> assertEquals(expected, fact(TokenLabel.DUE, text)?.let { "${it.day} ${it.start}" }, text) }
        assertEquals("2026-09-25", fact(TokenLabel.DUE, "do piątku", "pl")?.day.toString())
    }
    @Test fun repeats() = runTest {
        fun weekly(vararg days: DayOfWeek, every: Int = 1) = RecurrenceSpec(Frequency.WEEKLY, every, days.map(::WeekdayRule))
        mapOf(
            "daily" to RecurrenceSpec(Frequency.DAILY), "every day" to RecurrenceSpec(Frequency.DAILY), "every morning" to RecurrenceSpec(Frequency.DAILY),
            "every 2 weeks" to RecurrenceSpec(Frequency.WEEKLY, 2), "every other month" to RecurrenceSpec(Frequency.MONTHLY, 2),
            "fortnightly" to RecurrenceSpec(Frequency.WEEKLY, 2), "every 3 days" to RecurrenceSpec(Frequency.DAILY, 3),
            "every monday" to weekly(DayOfWeek.MONDAY), "mon wed fri" to weekly(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            "tuesdays" to weekly(DayOfWeek.TUESDAY), "tues & thurs" to weekly(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY),
            "every other friday" to weekly(DayOfWeek.FRIDAY, every = 2), "every second friday" to weekly(DayOfWeek.FRIDAY, every = 2),
            "every 2 weeks on sunday" to weekly(DayOfWeek.SUNDAY, every = 2),
            "every weekday" to weekly(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            "every month on the 15th" to RecurrenceSpec(Frequency.MONTHLY, daysOfMonth = listOf(15)),
            "the last day of every month" to RecurrenceSpec(Frequency.MONTHLY, daysOfMonth = listOf(-1)),
            "every 10th day of the month" to RecurrenceSpec(Frequency.MONTHLY, daysOfMonth = listOf(10)),
            "the last friday of every month" to RecurrenceSpec(Frequency.MONTHLY, weekdays = listOf(WeekdayRule(DayOfWeek.FRIDAY, -1))),
            "first thu of the month" to RecurrenceSpec(Frequency.MONTHLY, weekdays = listOf(WeekdayRule(DayOfWeek.THURSDAY, 1))),
            "every first and third wednesday of the month" to RecurrenceSpec(Frequency.MONTHLY, weekdays = listOf(WeekdayRule(DayOfWeek.WEDNESDAY, 1), WeekdayRule(DayOfWeek.WEDNESDAY, 3))),
            "monthly on the first monday" to RecurrenceSpec(Frequency.MONTHLY, weekdays = listOf(WeekdayRule(DayOfWeek.MONDAY, 1))),
            "every first sunday" to RecurrenceSpec(Frequency.MONTHLY, weekdays = listOf(WeekdayRule(DayOfWeek.SUNDAY, 1))),
            "once a month" to RecurrenceSpec(Frequency.MONTHLY), "quarterly" to RecurrenceSpec(Frequency.MONTHLY, 3),
            "mon-fri" to weekly(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            "tue-thu" to weekly(DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY),
            "every 3rd week" to RecurrenceSpec(Frequency.WEEKLY, 3), "every third week" to RecurrenceSpec(Frequency.WEEKLY, 3),
            "every 2nd month" to RecurrenceSpec(Frequency.MONTHLY, 2),
            "every working day" to weekly(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
        ).forEach { (text, expected) -> assertEquals(expected, repeat(text), text) }
        mapOf(
            "codziennie" to RecurrenceSpec(Frequency.DAILY), "co poniedziałek" to weekly(DayOfWeek.MONDAY), "we wtorki" to weekly(DayOfWeek.TUESDAY),
            "co 2 tygodnie" to RecurrenceSpec(Frequency.WEEKLY, 2), "co trzeci dzień" to RecurrenceSpec(Frequency.DAILY, 3),
            "co drugi piątek" to weekly(DayOfWeek.FRIDAY, every = 2),
            "w dni robocze" to weekly(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            "w każdy ostatni piątek miesiąca" to RecurrenceSpec(Frequency.MONTHLY, weekdays = listOf(WeekdayRule(DayOfWeek.FRIDAY, -1))),
            "każdego ostatniego dnia miesiąca" to RecurrenceSpec(Frequency.MONTHLY, daysOfMonth = listOf(-1)),
            "co miesiąc w drugi wtorek" to RecurrenceSpec(Frequency.MONTHLY, weekdays = listOf(WeekdayRule(DayOfWeek.TUESDAY, 2))),
            "w poniedziałki" to weekly(DayOfWeek.MONDAY), "w soboty" to weekly(DayOfWeek.SATURDAY), "co trzeci tydzień" to RecurrenceSpec(Frequency.WEEKLY, 3),
            "codziennie w dni robocze" to weekly(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
        ).forEach { (text, expected) -> assertEquals(expected, repeat(text, "pl"), text) }
        // Not guessed: that Sunday of each month, or every three weeks? And words a repeat cannot hold.
        for (text in listOf("every third sunday", "every weekday except friday", "twice a week", "a recurring", "every other working day")) assertNull(repeat(text), text)
    }
    @Test fun seriesBounds() = runTest {
        assertEquals("2026-09-28", (read(TokenLabel.STARTS, "next week") as? Read.SeriesStart)?.day.toString())
        assertEquals("2026-10-05", (read(TokenLabel.STARTS, "oct 5") as? Read.SeriesStart)?.day.toString())
        assertEquals("2026-11-01", (read(TokenLabel.STARTS, "od listopada", "pl") as? Read.SeriesStart)?.day.toString())
        // A range of days gives where the repeat stops as well.
        val range = read(TokenLabel.STARTS, "1 nov to 1 dec") as? Read.SeriesStart
        assertEquals("2026-11-01 until 2026-12-01", "${range?.day} until ${range?.fact?.endDay}")
        assertEquals(Read.SeriesEnd(until = kotlinx.datetime.LocalDate(2026, 11, 30)), read(TokenLabel.ENDS, "end of nov"))
        assertEquals(Read.SeriesEnd(until = kotlinx.datetime.LocalDate(2027, 5, 31)), read(TokenLabel.ENDS, "may"))
        assertEquals(Read.SeriesEnd(until = kotlinx.datetime.LocalDate(2028, 5, 31)), read(TokenLabel.ENDS, "end of may 2028"))
        assertEquals(Read.SeriesEnd(until = kotlinx.datetime.LocalDate(2029, 11, 12)), read(TokenLabel.ENDS, "11/12/2029"))
        assertEquals(Read.SeriesEnd(until = kotlinx.datetime.LocalDate(2026, 11, 30)), read(TokenLabel.ENDS, "before dec 1"), "the day named is not included")
        assertEquals(Read.SeriesEnd(until = kotlinx.datetime.LocalDate(2026, 11, 30)), read(TokenLabel.ENDS, "before december"), "nor the period")
        assertEquals(Read.SeriesEnd(until = kotlinx.datetime.LocalDate(2026, 11, 30)), read(TokenLabel.ENDS, "przed 1 grudnia", "pl"))
        assertEquals(Read.SeriesEnd(until = kotlinx.datetime.LocalDate(2027, 3, 31)), read(TokenLabel.ENDS, "do marca", "pl"))
        assertEquals(Read.SeriesEnd(span = Amount(weeks = 6)), read(TokenLabel.ENDS, "6 weeks"))
        assertEquals(Read.SeriesEnd(count = 6), read(TokenLabel.ENDS, "6 times"))
        assertEquals(Read.SeriesEnd(count = 6), read(TokenLabel.ENDS, "the next six"))
    }
    @Test fun places() = runTest {
        mapOf("@Office" to "Office", "Café Nero" to "Café Nero", "\"the corner table\"" to "the corner table", "Zoom" to "Zoom", "the Hilton" to "the Hilton")
            .forEach { (text, name) -> assertEquals(Read.Place(name), read(TokenLabel.PLACE, text), text) }
        assertEquals(Read.Place("Main Office"), read(TokenLabel.PLACE, "Main Office", "pl"))
    }
    @Test fun listsAndCalendarsAreTheCallers() = runTest {
        assertEquals(Read.Destination("work", null), read(TokenLabel.LIST, "#work"))
        assertEquals(Read.Destination("home", null), read(TokenLabel.LIST, "to my home list"))
        assertEquals(Read.Destination(null, "team"), read(TokenLabel.CALENDAR, "on the team calendar"))
        // A tag names the caller's list or calendar whatever the tagger called it.
        assertEquals(Read.Destination(null, "team"), read(TokenLabel.LIST, "#team"))
        assertNull(read(TokenLabel.LIST, "#unknown"))
        assertNull(read(TokenLabel.CALENDAR, "on the shared calendar"))
    }
    @Test fun priority() = runTest {
        assertEquals(Read.Importance(Priority.MEDIUM), read(TokenLabel.PRIORITY, "!!"))
        assertEquals(Read.Importance(Priority.HIGH), read(TokenLabel.PRIORITY, "urgent", urgency = mapOf(Priority.NONE to 0.1, Priority.HIGH to 0.85, Priority.LOW to 0.05)))
        assertEquals(Read.Importance(Priority.LOW), read(TokenLabel.PRIORITY, "no rush", urgency = mapOf(Priority.LOW to 0.7, Priority.MEDIUM to 0.1)))
        // The tagger must say clearly which level.
        assertNull(read(TokenLabel.PRIORITY, "important", urgency = mapOf(Priority.HIGH to 0.4, Priority.MEDIUM to 0.35)))
        assertNull(read(TokenLabel.PRIORITY, "important"))
    }
    @Test fun reminders() = runTest {
        suspend fun lead(text: String, language: String = "en") = assertIs<Read.Remind>(read(TokenLabel.REMINDER, text, language), text).lead
        mapOf("remind me 10 min before" to 10, "alert me an hour before" to 60, "remind me 1h before" to 60)
            .forEach { (text, minutes) -> assertEquals(Amount(minutes = minutes), lead(text), text) }
        assertEquals(Amount(days = 1), lead("remind me the day before"))
        assertEquals(Amount(weeks = 1), lead("a week before"))
        assertEquals(Amount(months = 1), lead("remind me a month before"))
        assertEquals(Amount(minutes = 10), lead("przypomnij 10 minut wcześniej", "pl"))
        assertEquals(Amount(minutes = 60), lead("powiadom godzinę wcześniej", "pl"))
        assertNull(read(TokenLabel.REMINDER, "with a reminder"))
        val thursday = assertIs<Read.Remind>(read(TokenLabel.REMINDER, "remind me thursday at 9am")).at!!
        assertEquals("2026-09-24 09:00", "${thursday.day} ${thursday.start}")
        assertEquals("null 13:00", assertIs<Read.Remind>(read(TokenLabel.REMINDER, "ping me at 1")).at!!.let { "${it.day} ${it.start}" })
        val dayBefore = assertIs<Read.Remind>(read(TokenLabel.REMINDER, "remind me at 9 the day before"))
        assertEquals("09:00 1", "${dayBefore.at?.start} ${dayBefore.lead?.days}")
        val yearEnd = assertIs<Read.Remind>(read(TokenLabel.REMINDER, "remind me a month before end of year"))
        assertEquals("2026-12-31 1", "${yearEnd.at?.day} ${yearEnd.lead?.months}")
        assertNull(read(TokenLabel.REMINDER, "remind me 2 hours after"), "a reminder after is not read")
        assertEquals(Amount(minutes = 10), lead("with a 10 min reminder"))
        assertEquals(Amount(minutes = 60), lead("remind me an hour ahead"))
        assertEquals(Amount(minutes = 20), lead("reminder 20 mins prior"))
    }
    @Test fun kinds() = runTest {
        mapOf("todo" to CandidateKind.TASK, "t:task" to CandidateKind.TASK, "task:" to CandidateKind.TASK, "type:event" to CandidateKind.EVENT)
            .forEach { (text, kind) -> assertEquals(Read.Kind(kind), read(TokenLabel.KIND, text), text) }
        assertEquals(Read.Kind(CandidateKind.TASK), read(TokenLabel.KIND, "zadanie", "pl"))
        assertEquals(Read.Kind(CandidateKind.TASK), read(TokenLabel.KIND, "do zrobienia", "pl"))
        assertEquals(Read.Kind(CandidateKind.EVENT), read(TokenLabel.KIND, "typ:wydarzenie", "pl"))
    }
}
