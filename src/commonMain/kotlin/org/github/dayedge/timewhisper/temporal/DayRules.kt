package org.github.dayedge.timewhisper.temporal

import org.github.dayedge.timewhisper.temporal.Example.Companion.en
import org.github.dayedge.timewhisper.temporal.Example.Companion.pl
import kotlinx.datetime.*
import org.github.dayedge.timewhisper.*
import org.github.dayedge.timewhisper.text.Edge
import org.github.dayedge.timewhisper.text.Role
import org.github.dayedge.timewhisper.text.SlashDate
import org.github.dayedge.timewhisper.text.Word

/**
 * The ways of writing a day or a period, in the order they are tried on the words of a part the tagger labelled a day.
 * The words come without the small ones that only attach them ("on", "the", "of"); the first rule that reads all of them wins.
 *
 * In the running examples, **B** and **C** `next friday` are read by [WeekdayPhrase]: one weekday word, the other word a
 * direction (+1), so the start of this week (Mon 21 Sep) plus one week, then forward to Friday = 2026-10-02.
 * `friday` alone would be the next Friday counting today = 2026-09-25. `end of month` (what `eom` stands for) is read by
 * [EdgeOfPeriod]: the last day of this month = 2026-09-30.
 */
internal object DayRules : RuleSet(listOf(
    SingleDayWord, DayOfMonth, WeekdayAndDay, WeekNumber, CountedOffset, CalendarDate, NthWeekdayOfMonth, WeekOfMonth, EdgeOfPeriod,
    WeekdayPhrase, DayInMonth, ComingDays, WholePeriod, MonthAlone, YearAlone, DayToDay, GluedDays, MonthDayRange,
))

/**
 * One word that is a day: relative to today, or a date written in digits.
 *
 * - English: `today`, `tomorrow`, `yesterday`, `tonight` (today, at about 20:00); `2026-10-01`; `10/11` (October 11, or
 *   10 November when day-first is configured). A whole date with its year may use any of "/", "." and "-", in every language:
 *   `11-11-2026`, `25.12.2026`, `2026/11/11`.
 * - Polish: `dziś`, `jutro`, `pojutrze`, `wczoraj`, `przedwczoraj`. Polish has single words for the day after tomorrow and the day
 *   before yesterday, so the lexicon gives a distance in days, not a fixed set of three.
 * - Other languages: the same table ("heute", "übermorgen", "aujourd'hui", "après-demain", "mañana"). ISO dates are the same everywhere.
 *
 * "tonight" carries a rough hour, which a time read elsewhere replaces. Without a year, only the language's own
 * separators make a date ("10/11" in English, "5.10" in Polish): a dash would be a range ("3-4") and a dot may be minutes ("9.30").
 */
internal object SingleDayWord : PhraseRule {
    override val name = "single-day-word"
    override val examples = en("tomorrow", "tonight", "2026-10-01", "10/11", "11-11-2026", "25.12.2026", "2026/11/11") + pl("jutro", "pojutrze", "do pojutrza", "11-11-2026")
    override fun read(words: List<Word>, scope: Scope): Fact? {
        val only = words.singleOrNull() ?: return null
        val today = scope.context.today
        only.entry?.relativeDay?.let { shift ->
            val hour = only.entry.approximateHour ?: return Fact(today.shift(shift))
            return Fact(today.shift(shift), start = LocalTime(hour, 0))
        }
        only.isoDate?.let { return Fact(it) }
        val slash = only.slashDate ?: return null
        return slashed(slash, today, scope.dateOrder)?.let { Fact(it, yearImplied = slash.year == null) }
    }
}

/**
 * A day of the month on its own: the next time that day comes, this month or the next.
 *
 * - English: `the 15th`, `1st`, `fifteenth`. Polish: `piętnastego`, `15-go`.
 * - Other languages: German "am 15.", French "le 15": the day's own mark is the language's number reader's business.
 *
 * A bare number ("15") is not read here: in a part about a day it is more often a time or a count than a date.
 */
internal object DayOfMonth : PhraseRule {
    override val name = "day-of-month"
    override val examples = en("the 15th", "1st", "fifteenth") + pl("piętnastego", "15-go")
    override fun read(words: List<Word>, scope: Scope): Fact? {
        val day = words.singleOrNull()?.dayOfMonth ?: return null
        return nextDayOfMonth(day, scope.context.today)?.let { Fact(it) }
    }
}

/**
 * A weekday and a day of the month: the next date that is both, within a year.
 *
 * - English: `friday the 13th`, `Tuesday the 17th`. Polish: `piątek trzynastego`.
 * - Other languages: the same two words, in whatever order the language puts them.
 */
internal object WeekdayAndDay : PhraseRule {
    override val name = "weekday-and-day"
    override val examples = en("friday the 13th", "Tuesday the 17th") + pl("piątek trzynastego")
    override fun read(words: List<Word>, scope: Scope): Fact? {
        if (words.size != 2) return null
        val weekday = words.firstNotNullOfOrNull { it.weekday } ?: return null
        val day = words.firstNotNullOfOrNull { it.dayOfMonth } ?: return null
        val today = scope.context.today
        return daysOfMonthAhead(day, today).firstOrNull { it >= today && it.dayOfWeek == weekday }?.let { Fact(it) }
    }
}

/**
 * An ISO week number, with or without its year: the Monday of that week.
 *
 * - English: `week 40`, `week 40 2026`.
 * - Polish: `tydzień 40`, the same shape.
 * - Other languages: the word for "week" is lexicon data ("Woche 40", "semaine 40"). The German abbreviation "KW 40" would be an
 *   ordinary lexicon form of that word.
 *
 * A number no year has ("week 99") names no week.
 */
internal object WeekNumber : PhraseRule {
    override val name = "week-number"
    override val examples = en("week 40", "week 40 2026") + pl("tydzień 40")
    override fun read(words: List<Word>, scope: Scope): Fact? {
        if (words.size !in 2..3 || !words[0].isSingular(Granularity.WEEK)) return null
        val week = words[1].shortNumber?.takeIf { it in 1..53 } ?: return null
        val stated = words.getOrNull(2)?.let { it.yearNumber ?: return null }
        val year = stated ?: scope.context.today.isoYear
        // ISO week 1 is the week that contains 4 January.
        val date = LocalDate(year, 1, 4).weekStart(DayOfWeek.MONDAY).shift((week - 1) * 7)
        return if (date.isoYear == year) Fact(date) else null
    }
}

/**
 * A counted distance in days, weeks, months or years, from today or from the day a first word names ("tomorrow + 2 days"): one amount
 * ([Amounts]), with one marker that says which way. An amount with hours in it gives a time as well; a time stated beside
 * it wins, and the hours then only count where they cross midnight. Every part's count is 1 to 999.
 *
 * - English: `in 3 days`, `2 weeks ago`, `+3 days`, `3 days from now`, `within 30 days`, `in 2 months, 7 days and 3 hours`,
 *   `in 3 weeks minus a day`, `a week and a half from now`.
 * - Polish: `za 3 dni`, `za tydzień`, `3 dni temu`, `za 2 miesiące i 7 dni`, `za 3 tygodnie bez jednego dnia`.
 * - Other languages: German puts "ago" in front ("vor 3 Tagen") and French uses three words in front ("il y a 3 jours").
 *   The forward marker is read before the amount and the backward one after it, so those two need the mirrored order added.
 */
internal object CountedOffset : PhraseRule {
    override val name = "counted-offset"
    override val examples = en("in 3 days", "2 weeks ago", "+3 days", "3 days from now", "3 days from today", "within 30 days", "in a week",
        "in 2 months, 7 days and 3 hours", "in 3 weeks minus a day", "a week and a half from now", "2 weeks and 3 days ago", "in 3w2d",
        "+3d", "- 3 days", "in 3 weeks - 2 days", "tomorrow + 2 days", "yesterday - 3 days", "- 2 weeks + 1 day", "friday + 2 weeks",
        "next tue - 3 days") +
        pl("za 3 dni", "za tydzień", "3 dni temu", "za 2 miesiące i 7 dni", "za 3 tygodnie bez jednego dnia", "za półtora tygodnia",
            "jutro + 3 dni", "piątek + 1 tydzień")
    override fun read(words: List<Word>, scope: Scope): Fact? {
        fromNamedDay(words, scope)?.let { return it }
        // Hours or minutes alone are a time, read by the clock rules.
        val amount = Amounts.anchored(words)?.takeIf { !it.onlyClock } ?: return null
        val context = scope.context
        if (amount.minutes == 0) {
            val date = amount.after(context.today)
            // Counted in weeks, the date also names a week: "saturday in 2 weeks" is the Saturday of that week.
            return Fact(date, weekStart = if (amount.onlyWeeks) date.weekStart(scope.configuration.weekStartsOn) else null)
        }
        val at = amount.after(context.referenceInstant, context.zone).toLocalDateTime(context.zone)
        return Fact(at.date, start = LocalTime(at.hour, at.minute))
    }

    /** "friday + 2 weeks", "next tue - 3 days": a day other words name, moved by the amount after the sign. Days and longer only. */
    private fun fromNamedDay(words: List<Word>, scope: Scope): Fact? {
        val at = words.indexOfFirst { Role.PLUS in it || Role.MINUS in it || it.sign != null }.takeIf { it > 0 } ?: return null
        // "tomorrow + 2 days" and "now + 3 hours" count from their word in [Amounts.anchored].
        if (at == 1 && Amounts.anchorDays(words[0]) != null) return null
        // The amount first: it is the cheaper of the two to read.
        val amount = Amounts.read(words.subList(at, words.size))?.takeIf { it.minutes == 0 } ?: return null
        val day = DayRules.fact(words.subList(0, at), scope)?.takeIf { it.start == null && it.endDay == null }?.day ?: return null
        return Fact(amount.after(day))
    }
}

/**
 * A month named with a day, a year, or both. Without a year the date is the next one to come.
 *
 * - English: `October 5`, `5 October`, `the 5th of October`, `Sat Oct 5 2026`, `March 2027`.
 * - Polish: `10 października`, `dziesiątego października`, `w październiku dziesiątego`, `dwudziesty piąty października`.
 *   The month changes its ending with its place in the sentence, and the day may be an ordinal word; both are lexicon forms.
 * - Other languages: German "5. Oktober" and French "1er mai" write the day with a mark that the language's own number reader
 *   strips, as English "5th" is. Spanish "5 de octubre" puts a small word between, which is dropped as English "of" is.
 *
 * A day the month does not have ("February 30") is marked impossible, and nothing is created from the sentence.
 */
internal object CalendarDate : PhraseRule {
    override val name = "calendar-date"
    override val examples = en("October 5", "the 5th of October", "Sat Oct 5 2026", "March 2027") +
        pl("10 października", "dziesiątego października", "październiku dziesiątego", "dwudziesty piąty października")
    override fun read(phrase: List<Word>, scope: Scope): Fact? {
        val today = scope.context.today
        // A weekday in front says nothing the date does not: "Saturday October 10".
        val words = if (phrase.size > 2 && phrase[0].weekday != null) phrase.subList(1, phrase.size) else phrase
        // "March 2027": the month as a whole.
        if (words.size == 2) {
            val month = words[0].month
            val year = words[1].yearNumber
            if (month != null && year != null) return Fact(LocalDate(year, month, 1), Granularity.MONTH)
        }
        // An optional year at the end: "October 5 2026".
        val year = if (words.size >= 3) words.last().yearNumber else null
        val core = if (year != null) words.subList(0, words.size - 1) else words
        if (core.size != 2) return null
        // The month and the word holding the day, in either order: "October 5", "5 October".
        val (month, written) = when {
            core[0].month != null -> core[0].month!! to core[1]
            core[1].month != null -> core[1].month!! to core[0]
            else -> return null
        }
        // The day as an ordinal word ("fifth") or as digits ("5", "5th").
        val day = (written.entry?.ordinal ?: written.ordinal.takeIf { written.looksLikeDayOfMonth })?.takeIf { it in 1..31 } ?: return null
        val date = if (year != null) dateOrNull(year, month, day) else upcoming(month, day, today)
        // "February 30" has the shape of a date and names none.
        return date?.let { Fact(it, yearImplied = year == null) } ?: Fact(impossible = true)
    }
}

/**
 * One weekday of a month, counted from its start or its end: an ordinal or "last", the weekday, and the month. Without a
 * month name it is this month's, or next month's once this month's has passed.
 *
 * - English: `last friday of the month`, `the second tuesday of november`, `first monday of next month`, `the first wednesday in december`.
 * - Polish: `ostatni piątek miesiąca`, `pierwszy poniedziałek listopada`, `drugi wtorek przyszłego miesiąca`. No word for "of":
 *   the month is in the genitive, which is a lexicon form.
 * - Other languages: German "letzter Freitag im Monat", French "le dernier vendredi du mois", Spanish "el último viernes del mes".
 */
internal object NthWeekdayOfMonth : PhraseRule {
    override val name = "nth-weekday-of-month"
    override val examples = en("last friday of the month", "the second tuesday of november", "first monday of next month", "the first wednesday in december") +
        pl("ostatni piątek miesiąca", "pierwszy poniedziałek listopada")

    override fun read(words: List<Word>, scope: Scope): Fact? {
        if (words.size < 3) return null
        val nth = words[0].nth ?: return null
        val weekday = words[1].weekday ?: return null
        val (first, roll) = monthOf(words.subList(2, words.size), scope) ?: return null
        val day = nthWeekday(first, weekday, nth) ?: return null
        // Passed already: the next such month ("of november" a year on, "of the month" a month on), unless a direction fixed it.
        val date = if (day < scope.context.today && roll > 0) nthWeekday(first.plus(roll, DateTimeUnit.MONTH), weekday, nth) else day
        return date?.let { Fact(it) }
    }
}

/**
 * One week of a month, counted from its start or its end. The first week starts on the 1st; the last ends on the last day.
 *
 * - English: `the first week of november`, `last week of the month`.
 * - Polish: `pierwszy tydzień listopada`, `ostatni tydzień miesiąca`.
 * - Other languages: German "die erste Novemberwoche" glues the month to the week, which would need a wording.
 */
internal object WeekOfMonth : PhraseRule {
    override val name = "week-of-month"
    override val examples = en("the first week of november", "last week of the month") + pl("pierwszy tydzień listopada")

    override fun read(words: List<Word>, scope: Scope): Fact? {
        if (words.size < 3 || !words[1].isSingular(Granularity.WEEK)) return null
        val nth = words[0].entry?.ordinal?.takeIf { it in 1..5 || it == -1 } ?: return null
        val (first, _) = monthOf(words.subList(2, words.size), scope) ?: return null
        val start = if (nth == -1) first.lastOfMonth().shift(-6) else first.shift((nth - 1) * 7)
        return if (start.month == first.month) Fact(start, Granularity.WEEK) else null
    }
}

/**
 * A year by its number, with the word for year or without it: the whole year.
 *
 * - English: `2027`, `year 2027`; `in 2027`, where "in" is a wrapper outside the part. With an edge, `end of 2027` and
 *   `beginning of 2027` are read by [EdgeOfPeriod].
 * - Polish: `2027`, `2027 roku`, `rok 2027`.
 * - Other languages: only the word for year, which is a unit in the lexicon.
 *
 * Only a part the tagger labelled a date reaches this rule, so a number in a title ("Room 2027", "budget 2027") is never read.
 */
internal object YearAlone : PhraseRule {
    override val name = "year-alone"
    override val examples = en("2027", "year 2027") + pl("2027 roku", "rok 2027")
    override fun read(words: List<Word>, scope: Scope): Fact? {
        val year = words.firstNotNullOfOrNull { it.yearNumber } ?: return null
        // The number, and at most the word for year beside it.
        val unitWords = words.count { it.isSingular(Granularity.YEAR) }
        if (words.size != 1 + unitWords || unitWords > 1) return null
        return Fact(LocalDate(year, 1, 1), Granularity.YEAR)
    }
}

/**
 * The first or last day of what follows the edge: any date the day rules read, or a period said by its unit alone.
 *
 * - English: `end of may 2028`, `start of next november`, `end of nov`, `end of next month`, and a period by its unit: `end of
 *   month`, `end of the year`; `first day of next month`; `end of day` (today); `eom`, which a wording spells out.
 * - Polish: `koniec miesiąca`, `koniec listopada`. Polish needs no word for "of"; the period is simply in the genitive, which is
 *   a lexicon form.
 * - Other languages: German has both a phrase ("Ende des Monats") and a compound ("Monatsende"); the compound would be a wording.
 *
 * A date said without its year keeps saying so, so a year named beside it moves it: "end of nov next year" is read as "end of
 * nov" and "next year", which [merge] puts together. "start of the weekend" is deliberately not read.
 */
internal object EdgeOfPeriod : PhraseRule {
    override val name = "edge-of-period"
    override val examples = en("end of month", "start of next month", "end of the year", "first day of next month", "end of day", "end of nov", "start of march",
        "end of may 2028", "end of 11/12/2029", "end of 2027", "beginning of 2027") +
        pl("koniec miesiąca", "koniec listopada", "koniec 2027 roku")
    override fun read(words: List<Word>, scope: Scope): Fact? {
        if (words.size < 2) return null
        // "last" is an edge only before "day": "the last day of the month". "last week" is the week before this one.
        val edge = words[0].entry?.edge ?: Edge.END.takeIf { words[0].entry?.ordinal == -1 && words[1].isSingular(Granularity.DAY) } ?: return null
        // "first day of next month" is the start of it; "end of day" is today.
        val rest = words.drop(1).let { if (it[0].isSingular(Granularity.DAY)) it.drop(1) else it }
        if (rest.isEmpty()) return Fact(scope.context.today)
        // Any date: its first or its last day ("end of may 2028" is 31 May 2028).
        DayRules.fact(rest, scope)?.let { date ->
            val first = date.day ?: return null
            val day = if (edge == Edge.START) first else date.lastDay() ?: return null
            return Fact(day, yearImplied = date.yearImplied)
        }
        // A period by its unit alone, which is no date by itself: "end of month", "end of the year".
        val period = period(rest, scope, implicit = true)?.takeUnless { it.weekend } ?: return null
        return Fact(if (edge == Edge.START) period.start else period.endExclusive.shift(-1))
    }
}

/**
 * Exactly one weekday, alone or with a direction or the word "week" around it, in any order.
 *
 * - English: `friday`, `this friday`, `next friday`, `last friday`, `friday next week`.
 * - Polish: `piątek`, `w przyszły piątek`, `w przyszłą środę`, `najbliższy czwartek`. The direction agrees with the day in gender
 *   and case, so the lexicon lists its forms. "najbliższy" (nearest) means what the bare day means, and a wording reduces it to that.
 * - Other languages: because order is free here, a direction after the noun already fits ("vendredi prochain", "el viernes próximo").
 *
 * What "next" means is a convention this rule fixes for every language: the bare day is the coming one, counting today, and
 * "next" is that day in the next calendar week. German "nächsten Freitag" is often meant as the coming Friday; if a language
 * needs the other convention, it belongs here as a setting, not in the lexicon.
 */
internal object WeekdayPhrase : PhraseRule {
    override val name = "weekday-phrase"
    override val examples = en("friday", "next friday", "friday next week", "last friday") + pl("piątek", "przyszły piątek", "przyszłą środę", "najbliższy czwartek")
    override fun read(words: List<Word>, scope: Scope): Fact? {
        val weekday = words.mapNotNull { it.weekday }.singleOrNull() ?: return null
        val other = words.filter { it.weekday == null }
        if (other.size > 2 || other.any { it.direction == null && !it.isSingular(Granularity.WEEK) }) return null
        val today = scope.context.today
        val leading = other.firstOrNull()?.direction
        val weeks = leading ?: 0
        val date = when {
            // A named week, or "this"/"next", counts in configured calendar weeks.
            other.any { it.isSingular(Granularity.WEEK) } || leading == 0 || leading == 1 ->
                today.weekStart(scope.configuration.weekStartsOn).shift(weeks * 7).onOrAfter(weekday)

            // "last friday": the most recent one before today.
            weeks < 0 -> today.shift(-7).onOrAfter(weekday)
            // A bare weekday: the next one, counting today.
            else -> today.onOrAfter(weekday)
        }
        // A bare weekday is remembered as such, so that a separate "next week" elsewhere in the sentence can move it.
        return Fact(date, weekday = if (other.isEmpty()) weekday else null, namedWeekday = weekday)
    }
}

/**
 * A day of the month, with the month named by a direction and not by its name.
 *
 * - English: `15th of next month`, `next month 15th`.
 * - Polish: `15 przyszłego miesiąca`. No word for "of" is needed; the month is in the genitive.
 * - Other languages: German "am 15. nächsten Monats", French "le 15 du mois prochain". The French one has the direction after
 *   the unit, which this rule does not accept: it expects the direction first.
 */
internal object DayInMonth : PhraseRule {
    override val name = "day-in-month"
    override val examples = en("15th of next month", "next month 15th") + pl("15 przyszłego miesiąca")
    override fun read(words: List<Word>, scope: Scope): Fact? {
        val (day, direction, month) = parts(words) ?: return null
        val n = day.ordinal ?: return null
        val first = period(listOf(direction, month), scope)!!.start
        return dateOrNull(first.year, first.month.number, n)?.let { Fact(it) }
    }

    /** "15th of next month", "next month 15th": the day word, then the two words that name the month. */
    private fun parts(words: List<Word>): Triple<Word, Word, Word>? {
        val lead = words[0].takeIf { it.looksLikeDayOfMonth }
        val at = if (lead != null) 1 else 0
        val direction = words.getOrNull(at)?.takeIf { it.direction != null } ?: return null
        val month = words.getOrNull(at + 1)?.takeIf { it.isSingular(Granularity.MONTH) } ?: return null
        // Or last: "next month 15th". Nothing else may follow.
        val trail = words.getOrNull(at + 2)
        if (trail != null && (!trail.looksLikeDayOfMonth || words.size > at + 3)) return null
        if (trail == null && words.size > at + 2) return null
        return Triple(lead ?: trail ?: return null, direction, month)
    }
}

/**
 * A run of days starting today: a word for "next", a count, and days in the plural. "next 3 days" is today and the two days after.
 *
 * - English: `next 3 days`, `next three days`.
 * - Polish: the count changes the case of the words around it ("następne 3 dni", "następnych 5 dni"); only the forms the
 *   lexicon lists are read.
 * - Other languages: German "die nächsten 3 Tage" fits once the article is dropped; French "les 3 prochains jours" puts the
 *   count before the direction, which this rule does not accept.
 */
internal object ComingDays : PhraseRule {
    override val name = "coming-days"
    override val examples = en("next 3 days", "next three days")
    override fun read(words: List<Word>, scope: Scope): Fact? {
        if (words.size != 3 || words[0].direction != 1 || words[2].unit != Granularity.DAY) return null
        val count = words[1].takeIf { it.isPlain }?.number?.takeIf { it in 1..999 } ?: return null
        val today = scope.context.today
        return Fact(today, endDay = today.shift(count - 1))
    }
}

/**
 * A whole week, month, year or weekend: the phrase means the period, from its first day.
 *
 * - English: `next week`, `this month`, `last year`, `the weekend`.
 * - Polish: `w przyszłym tygodniu`, `w tym miesiącu`, `weekend`.
 * - Other languages: German "nächste Woche" fits. French and Spanish put the direction last ("la semaine prochaine",
 *   "la semana próxima"), and this rule expects the unit last, so those are not read yet.
 *
 * On its own a unit ("week") is not a period, except the weekend, which always means the coming one. Which days make a
 * weekend is configuration, not vocabulary.
 */
internal object WholePeriod : PhraseRule {
    override val name = "whole-period"
    override val examples = en("next week", "this month", "last year", "the weekend") + pl("przyszłym tygodniu", "weekend")
    override fun read(words: List<Word>, scope: Scope): Fact? {
        val period = period(words, scope) ?: return null
        return Fact(period.start, period.granularity, endDay = if (period.weekend) period.endExclusive.shift(-1) else null)
    }
}

/**
 * A month by its name alone, or with a word that moves it: the whole month. Alone it is the next one to come (this one, if
 * it is now); "next" and "last" move it a year on or back from that.
 *
 * - English: `in november`, `march`, `next march`, `last june`.
 * - Polish: `w listopadzie`, `listopad`, `do marca`.
 *
 * Only a part the tagger labelled a date reaches this rule, so "call May" is never read: there "May" is the title.
 */
internal object MonthAlone : PhraseRule {
    override val name = "month-alone"
    override val examples = en("in november", "march", "next march") + pl("w listopadzie", "marca")
    override fun read(words: List<Word>, scope: Scope): Fact? {
        val month = words.last().month ?: return null
        val today = scope.context.today
        val year = when (words.size) {
            // The next one to come, or this one.
            1 -> if (month >= today.month.number) today.year else today.year + 1
            // "last june" is the latest one gone by, "next march" the next one after this month, "this may" this year's.
            2 -> when (words[0].direction ?: return null) {
                0 -> today.year
                -1 -> if (month < today.month.number) today.year else today.year - 1
                else -> if (month > today.month.number) today.year else today.year + 1
            }
            else -> return null
        }
        // A month named alone says no year: "nov next year" is that month in that year.
        return Fact(LocalDate(year, month, 1), Granularity.MONTH, yearImplied = words.size == 1)
    }
}

/**
 * A day, a range word, another day: the first and the last day of a range.
 *
 * - English: `friday to sunday`, `from oct 3 to oct 5`, `from the 12th through the 14th`, `mon thru wed`, `from 1 nov to 1 dec`,
 *   `from next monday to end of january`.
 * - Polish: `od piątku do niedzieli`, `od 3 października do 5 października`. Both days are in the genitive after these prepositions.
 * - Other languages: German "von Freitag bis Sonntag". French fuses the preposition with the article ("du vendredi au dimanche"),
 *   which gives a small word and a range word.
 *
 * Each side is any day the other rules read. The last day comes after the first: one written without its year is the next
 * one after the start, so "from nov 6 till end of may" ends next May, and a weekday is the next one after the start, so
 * "from next friday to next tuesday" ends the Tuesday after. "between … and …" is not read for days: "and" also joins
 * two separate days ("mon and wed").
 */
internal object DayToDay : PhraseRule {
    override val name = "day-to-day"
    override val examples = en("friday to sunday", "from oct 3 to oct 5", "from the 12th through the 14th", "from next monday to end of january",
        "from next friday to next tuesday") +
        pl("od piątku do niedzieli")
    override fun read(words: List<Word>, scope: Scope): Fact? {
        val at = words.indexOfFirst { Role.RANGE in it }.takeIf { it > 0 } ?: return null
        val before = words.subList(0, at)
        val after = words.subList(at + 1, words.size)
        val first = day(before, scope.context.today) ?: DayRules.fact(before, scope)?.day ?: return null
        val last = day(after, first) ?: DayRules.fact(after, scope)?.let { end ->
            val last = end.lastDayFrom(first) ?: return@let null
            if (last <= first && end.yearImplied) last.plus(1, DateTimeUnit.YEAR) else last
        }
        return Fact(first, endDay = last?.takeIf { it > first } ?: return null)
    }
}

/**
 * Two weekdays, two days of the month, or two dates, in one token with a dash between them.
 *
 * - English: `fri-sun`, `mon-wed`, `12th-14th`, `3/4-3/7`.
 * - Polish: `pt-nd`, with the Polish abbreviations.
 * - Other languages: nothing here depends on the language beyond the weekday forms and the day's mark ("Fr-So", "ven-dim").
 */
internal object GluedDays : PhraseRule {
    override val name = "glued-days"
    override val examples = en("fri-sun", "mon-wed", "12th-14th", "3/4-3/7") + pl("pt-nd", "11/11-14/11")
    override fun read(words: List<Word>, scope: Scope): Fact? {
        val (a, b) = words.singleOrNull()?.dashParts ?: return null
        val today = scope.context.today
        if (a.weekday != null && b.weekday != null) today.onOrAfter(a.weekday!!).let { return Fact(it, endDay = it.onOrAfter(b.weekday!!)) }
        a.dayOfMonth?.let { day ->
            val first = nextDayOfMonth(day, today) ?: return null
            val last = b.dayOfMonth?.takeIf { it > first.day }?.let { dateOrNull(first.year, first.month.number, it) } ?: return null
            return Fact(first, endDay = last)
        }
        // Otherwise two whole dates, "3/4-3/7", the last after the first.
        val from = DayRules.fact(listOf(a), scope)?.day ?: return null
        val to = DayRules.fact(listOf(b), scope)?.day ?: return null
        return to.takeIf { it > from }?.let { Fact(from, endDay = it) }
    }
}

/**
 * Days of one month: a month and two day numbers glued with a dash, in either order.
 *
 * - English: `3-5 march`, `march 3-5`, `oct 20-22`.
 * - Polish: `3-5 marca`.
 * - Other languages: the same ("3-5 marzo", "3.-5. März" once dotted days are read).
 *
 * The second number must be the larger; "5-3 march" is not a range.
 */
internal object MonthDayRange : PhraseRule {
    override val name = "month-day-range"
    override val examples = en("3-5 march", "march 3-5", "oct 20-22") + pl("3-5 marca")
    override fun read(words: List<Word>, scope: Scope): Fact? {
        if (words.size != 2) return null
        val (month, days) = if (words[0].dashParts != null) words[1].month to words[0].dashParts else words[0].month to words[1].dashParts
        if (month == null || days == null || days.first.weekday != null) return null
        val first = days.first.ordinal ?: return null
        // The second day must come after the first; "5-3 march" is not a range.
        val last = days.second.ordinal?.takeIf { it > first } ?: return null
        val from = upcoming(month, first, scope.context.today) ?: return null
        val to = dateOrNull(from.year, month, last) ?: return null
        return Fact(from, endDay = to)
    }
}

// What the rules above are built from, where more than one rule uses it.

/** A calendar period named by words such as "next month" or "this weekend". */
private class Period(val start: LocalDate, val endExclusive: LocalDate, val granularity: Granularity, val weekend: Boolean)


/** An ordinal as written on its own: a word ("fifteenth", "last") or digits with the language's mark ("15th"), not bare digits. */
private val Word.writtenOrdinal: Int? get() = entry?.ordinal ?: ordinal?.takeIf { looksLikeDayOfMonth && !allDigits }

/** A day of the month as written on its own: "fifteenth", "15th". */
private val Word.dayOfMonth: Int? get() = writtenOrdinal?.takeIf { it in 1..31 }

/** Which one of a month's weekdays: 1 to 5 from the start, -1 the last. "last", "2nd", "ostatni". */
private val Word.nth: Int? get() = writtenOrdinal?.takeIf { it in 1..5 || it == -1 }

/** [day] of this month and of each of the next twelve that have it, in order. */
private fun daysOfMonthAhead(day: Int, today: LocalDate): Sequence<LocalDate> =
    (0..12).asSequence().map { today.plus(it, DateTimeUnit.MONTH) }.mapNotNull { dateOrNull(it.year, it.month.number, day) }

/** [day] of this month if it is still to come, else of the next month that has it. */
private fun nextDayOfMonth(day: Int, today: LocalDate): LocalDate? = daysOfMonthAhead(day, today).firstOrNull { it >= today }

/**
 * One end of a range of days, on or after [from]: a weekday, a month and a day in either order, or a day of the month alone.
 */
private fun day(words: List<Word>, from: LocalDate): LocalDate? = when (words.size) {
    1 -> words[0].weekday?.let { from.onOrAfter(it) } ?: words[0].dayOfMonth?.let { nextDayOfMonth(it, from) }
    2 -> {
        val month = words[0].month ?: words[1].month
        val day = (if (words[0].month != null) words[1] else words[0]).let { it.dayOfMonth ?: it.ordinal }
        if (month != null && day != null) upcoming(month, day, from) else null
    }
    else -> null
}

/**
 * The first day of the month that ends a phrase such as "last friday of …", and how many months on the next such month is:
 * "november" (the next November to come; 12), "month" (this one; 1), "next month" (that one; 0, fixed),
 * Polish "listopada", "miesiąca".
 */
private fun monthOf(words: List<Word>, scope: Scope): Pair<LocalDate, Int>? {
    val today = scope.context.today
    words.singleOrNull()?.month?.let { month ->
        return LocalDate(if (month >= today.month.number) today.year else today.year + 1, month, 1) to 12
    }
    val period = period(words, scope, implicit = true)?.takeIf { it.granularity == Granularity.MONTH } ?: return null
    return period.start to (if (words.size > 1) 0 else 1)
}

/** The [nth] [weekday] of the month starting on [first], -1 being the last; null when the month has no such day. */
private fun nthWeekday(first: LocalDate, weekday: DayOfWeek, nth: Int): LocalDate? {
    if (nth == -1) return first.lastOfMonth().shift(-6).onOrAfter(weekday)
    return first.onOrAfter(weekday).shift((nth - 1) * 7).takeIf { it.month == first.month }
}

/** "week", "next month", "the weekend". A bare unit names the current period only where [implicit], and always for a weekend. */
private fun period(words: List<Word>, scope: Scope, implicit: Boolean = false): Period? {
    val configuration = scope.configuration
    val unit = words.lastOrNull() ?: return null
    val weekend = Role.WEEKEND in unit
    // The last word names the period: "week", "month" or "year" in the singular, or "weekend".
    val calendar = unit.unit?.takeIf { it != Granularity.DAY && !unit.plural }
    if (!weekend && calendar == null || words.size > 2) return null
    // At most one word before it, and it must be a direction: "next month".
    // On its own the unit means the current period only where the caller says so ("end of month"), and always for a weekend.
    val n = if (words.size == 2) words[0].direction ?: return null else if (implicit || weekend) 0 else return null
    val today = scope.context.today
    if (weekend) {
        // Which days are the weekend is configurable, so weekends are found by scanning:
        // one starts on a weekend day that follows a day that is not.
        val candidates = (-14..21).map(today::shift)
        val starts = candidates.filter { it.dayOfWeek in configuration.weekendDays && it.shift(-1).dayOfWeek !in configuration.weekendDays }
        // "This weekend" is the one under way, or else the next to start.
        val current = starts.lastOrNull { it <= today && weekendEnd(it, configuration) > today }
        val upcoming = current ?: starts.first { it > today }
        val start = when (n) {
            -1 -> starts.last { it < (current ?: today) }; 1 -> starts.first { it > upcoming }; else -> upcoming
        }
        return Period(start, weekendEnd(start, configuration), Granularity.DAY, weekend = true)
    }
    val start = when (calendar) {
        Granularity.WEEK -> today.weekStart(configuration.weekStartsOn).shift(n * 7)
        Granularity.MONTH -> LocalDate(today.year, today.month, 1).plus(n, DateTimeUnit.MONTH)
        else -> LocalDate(today.year + n, 1, 1)
    }
    return Period(start, start.shift(1, calendar!!), calendar, weekend = false)
}

/** The day after the weekend that starts on [start]: the first day that is not a weekend day. */
private fun weekendEnd(start: LocalDate, configuration: Configuration): LocalDate =
    (1..7).map(start::shift).first { it.dayOfWeek !in configuration.weekendDays }

private fun slashed(date: SlashDate, today: LocalDate, order: DateOrder): LocalDate? {
    val dayFirst = order == DateOrder.DAY_MONTH || date.first > 12
    val month = if (dayFirst) date.second else date.first
    val day = if (dayFirst) date.first else date.second
    return if (date.year == null) upcoming(month, day, today) else dateOrNull(date.year, month, day)
}

