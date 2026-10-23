package org.github.dayedge.timewhisper.text

import org.github.dayedge.timewhisper.DateOrder
import org.github.dayedge.timewhisper.Frequency
import org.github.dayedge.timewhisper.Granularity

/**
 * English. The sections are in the same order in every lexicon; [Polish] is the same file for an inflected language.
 * Each row is one meaning with all the forms people type for it, abbreviations included.
 */
internal object English : Lexicon() {
    override val language = "en"
    override val spellingLanguage = "en_US"

    override val conventions = Conventions(
        dateOrder = DateOrder.MONTH_DAY,
        dateSeparators = "/",
        minuteSeparators = ":.",
        meridiemSuffixes = mapOf("a.m." to Meridiem.AM, "p.m." to Meridiem.PM, "am" to Meridiem.AM, "pm" to Meridiem.PM, "a" to Meridiem.AM, "p" to Meridiem.PM),
        // The 12-hour clock is the habit: "at 5" is the afternoon, and "7:30" does not say which half of the day.
        afternoonHours = 1..7,
        ordinalMarks = "st|nd|rd|th",
    )

    override fun LexiconBuilder.define() {
        // Calendar names. A weekday in the plural ("tuesdays") is the same day; a repeat reads it as every Tuesday.
        weekdays(
            "monday mondays mon", "tuesday tuesdays tue tues", "wednesday wednesdays wed", "thursday thursdays thu thur thurs",
            "friday fridays fri", "saturday saturdays sat", "sunday sundays sun",
        )
        months(
            "january jan", "february feb", "march mar", "april apr", "may", "june jun", "july jul", "august aug",
            "september sep sept", "october oct", "november nov", "december dec",
        )

        // Numbers. Counts, and the hours they name when said as a time ("at eight"); then ordinals, for days of the month.
        counted(1, "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve") { copy(number = it, hour = it) }
        counted(13, "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty") { copy(number = it) }
        set(30 to "thirty", 40 to "forty", 45 to "forty-five", 50 to "fifty", 60 to "sixty", 90 to "ninety") { copy(number = it) }
        set(1 to "a an") { copy(number = it, roles = roles + Role.ARTICLE) }
        counted(
            1, "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth", "eleventh", "twelfth",
            "thirteenth", "fourteenth", "fifteenth", "sixteenth", "seventeenth", "eighteenth", "nineteenth", "twentieth",
            "twenty-first", "twenty-second", "twenty-third", "twenty-fourth", "twenty-fifth", "twenty-sixth", "twenty-seventh",
            "twenty-eighth", "twenty-ninth", "thirtieth", "thirty-first",
        ) { copy(ordinal = it) }
        set(-1 to "last") { copy(ordinal = it) }

        // Units of the calendar, and lengths of time under a day in minutes. A day and a week are also lengths, of 1440
        // and 10080 minutes ("remind me a day before"); the readers take those from the unit.
        unit(Granularity.DAY, "day d", "days")
        unit(Granularity.WEEK, "week", "weeks")
        unit(Granularity.MONTH, "month", "months")
        unit(Granularity.YEAR, "year", "years")
        set(1 to "m min mins minute minutes", 60 to "h hr hrs hour hours") { copy(minutes = it) }

        // Where a date lies from today, or within a period.
        set(0 to "this", 1 to "next", -1 to "last previous past") { copy(direction = it) }
        set(0 to "today tonight", 1 to "tomorrow", -1 to "yesterday") { copy(relativeDay = it) }
        set(Edge.START to "start first", Edge.END to "end final") { copy(edge = it) }

        // The clock in words. "tonight" is a day and roughly an hour. Morning, afternoon and evening name no hour people
        // agree on: after a day they only qualify it ("tomorrow morning"), after an hour they say which half ("3 in the
        // afternoon"). "night" says neither: "2 at night" is early, "11 at night" late.
        set(20 to "tonight") { copy(approximateHour = it) }
        role(Role.PART_OF_DAY, "morning afternoon evening night")
        set(Meridiem.AM to "am morning", Meridiem.PM to "pm afternoon evening") { copy(meridiem = it) }
        set(12 to "noon midday", 0 to "midnight") { copy(namedClock = it) }
        set(30 to "half", 15 to "quarter") { copy(fraction = it) }

        // Repeats.
        set(Frequency.DAILY to "daily", Frequency.WEEKLY to "weekly", Frequency.MONTHLY to "monthly", Frequency.YEARLY to "yearly annually") {
            copy(recurrence = it)
        }
        role(Role.EVERY, "every each once")
        role(Role.OTHER, "other alternate")
        role(Role.WEEKDAYS, "weekday weekdays workday workdays working business")
        role(Role.WEEKEND, "weekend")

        // Small words that give a part its shape.
        role(Role.IN, "in within")
        role(Role.AGO, "ago")
        role(Role.FROM_NOW, "now today tonight")
        role(Role.PAST, "past")
        role(Role.TO, "to")
        role(Role.RANGE, "- – — -> ~ to until till til untill thru through")
        role(Role.BEFORE, "before earlier ahead prior")
        role(Role.AFTER, "after")
        role(Role.AND, "and &")
        role(Role.PLUS, "plus +")
        role(Role.MINUS, "minus less without -")
        role(Role.ALL, "all")

        // Words inside a date or a time that carry no value; readers read past them. A word that only attaches a part to the
        // sentence ("by friday", "at 3", "starting monday", "for 45 min") is not listed: the tagger labels it a wrapper.
        // Joiners hold one date or time together: "the 12th", "first of the month", "every week on monday", "friday at 5",
        // "3 days from now", "2 hours before".
        role(Role.JOINER, "the of on at from in before")
        // Hedges qualify a time without changing it: "about 4", "7 sharp", "3 o'clock", "later today". "coming friday" is
        // "friday", the next one to come.
        role(Role.HEDGE, "about around roughly sharp o'clock later earlier latest coming")

        // Other forms of a word: shorthand, and misspellings a spell checker does not mend. Each reads exactly like its word.
        alias("previous", "prev")
        alias("next", "nxt")
        alias("this", "cur current")
        alias("week", "wk w")
        alias("weeks", "wks")
        alias("month", "mo mth")
        alias("months", "mos mths")
        alias("year", "yr yer")
        alias("years", "yrs")
        alias("start", "beg beginning")
        alias("end", "ending")
        alias("weekday", "wkday")
        alias("weekdays", "wkdays")
        alias("weekly", "wkly")
        alias("monthly", "mthly")
        alias("tomorrow", "tmrw tmr tmw tmro 2moro 2morrow 2mrw")
        alias("today", "2day tdy")
        alias("tonight", "tonite 2nite")
        alias("yesterday", "yday")
        alias("monday", "munday")
        alias("wednesday", "weds")
        alias("weekend", "wknd wkend")
    }

    // Abbreviations and set phrases, each for words the readers know.
    override val wordings = listOf(
        Wording("bom", "start of month"), Wording("eom", "end of month"), Wording("boy", "start of year"), Wording("eoy", "end of year"),
        Wording("eow", "end of week"), Wording("eod", "end of day"), Wording("cob", "end of day"),
        Wording("day after tomorrow", "in 2 days"), Wording("last night", "yesterday"), Wording("all-day", "all day"),
        Wording("a fortnight", "2 weeks"), Wording("fortnight", "2 weeks"), Wording("fortnightly", "every 2 weeks"), Wording("quarterly", "every 3 months"),
    )
}
