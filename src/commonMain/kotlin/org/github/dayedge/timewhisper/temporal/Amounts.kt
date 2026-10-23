package org.github.dayedge.timewhisper.temporal

import kotlinx.datetime.*
import kotlin.time.Duration.Companion.minutes
import kotlin.math.abs
import kotlin.math.floor
import kotlin.time.Instant
import org.github.dayedge.timewhisper.Granularity
import org.github.dayedge.timewhisper.text.Role
import org.github.dayedge.timewhisper.text.Word
import org.github.dayedge.timewhisper.text.contains

/**
 * An amount of time: calendar months, weeks and days, and minutes on the clock. A year is 12 months; an hour is 60 minutes.
 * Months, weeks and days move along the calendar ("a month after 31 January" is the last day of February); minutes pass
 * on the clock, so two hours across a change of offset are still two hours.
 */
internal data class Amount(val months: Int = 0, val weeks: Int = 0, val days: Int = 0, val minutes: Int = 0) {
    operator fun plus(other: Amount) = Amount(months + other.months, weeks + other.weeks, days + other.days, minutes + other.minutes)
    operator fun unaryMinus() = Amount(-months, -weeks, -days, -minutes)

    /** Only hours and minutes: "2 hours 15 min". */
    val onlyClock: Boolean get() = months == 0 && weeks == 0 && days == 0

    /** Only whole weeks: "in 2 weeks" also names the week it lands in. */
    val onlyWeeks: Boolean get() = weeks != 0 && months == 0 && days == 0 && minutes == 0

    /** The same amount in minutes where it is a fixed length: days and weeks are; a month is not. */
    val fixedMinutes: Int? get() = if (months != 0) null else (weeks * 7 + days) * 1440 + minutes

    /** The day this amount after [day], on the calendar: months first, then weeks and days. Minutes are not counted. */
    fun after(day: LocalDate): LocalDate = day.shift(months, Granularity.MONTH).shift(weeks * 7 + days)

    /** The day this amount before [day]. */
    fun before(day: LocalDate): LocalDate = (-this).after(day)

    /** The moment this amount after [moment] where the user is: the calendar part on the calendar, then the minutes on the clock. */
    fun after(moment: Instant, zone: TimeZone): Instant {
        val local = moment.toLocalDateTime(zone)
        return LocalDateTime(after(local.date), local.time).toInstant(zone) + minutes.minutes
    }
}

/**
 * Reads an amount of time from words, in any language whose lexicon gives the units and the small words: a sum of parts
 * ("2 months, 7 days and 3 hours", "3h15min", "an hour and a half"), or a difference ("3 weeks minus a day", Polish
 * "3 tygodnie bez jednego dnia").
 *
 * A part is a count and its unit ("3 days", "a week"), a unit alone ("tydzień"), a number glued to its unit ("30min",
 * "1.5h"), or "half" before a unit ("half an hour"). Parts follow each other, or are joined by "and", "plus" (added) or
 * "minus" (taken away). Without "plus" or "minus" the units must go down, each at most once, as an amount is written:
 * "3 weeks, 7 weeks, 8 weeks" and "7 days and 2 months" are lists, not amounts, and are not read. A fraction of a month
 * or a year that is not a whole number of months is not read either: half a month is no fixed number of days.
 */
internal object Amounts {
    /**
     * The amount all of [words] say, or null. No part may count more than [limit]. A sign or an operator sets the direction
     * of the parts after it, until the next one: "-3 weeks 2 days" is 23 days back, "- 2 weeks + 1 day" 13 days back.
     */
    fun read(words: List<Word>, limit: Double = 999.0): Amount? = Sum(words, limit).amount()

    /**
     * An amount counted from a day other than today: "tomorrow + 2 days", "yesterday - 3 days", "now + 3 hours", "today in
     * 3 days". The day the first word names ([anchorDays]) is added to the amount that follows; without such a word the
     * amount counts from now. Null when what follows is not a marked amount.
     */
    fun anchored(words: List<Word>): Amount? {
        val days = anchorDays(words.firstOrNull()?.takeIf { words.size > 1 }) ?: return marked(words)
        return marked(words.drop(1))?.let { it + Amount(days = days) }
    }

    /** The days from today a word that starts an amount counts from: "today" and "now" 0, "tomorrow" 1, "yesterday" -1. */
    fun anchorDays(word: Word?): Int? = word?.entry?.relativeDay ?: if (Role.FROM_NOW in word) 0 else null

    /**
     * The amount [words] say as a point in time, signed: one marker before it ("in", "within", "za", a sign "+3", "- 2", or
     * "plus") or after it ("from now", "from today", "ago", "temu"), never both.
     */
    private fun marked(words: List<Word>): Amount? {
        val first = words.firstOrNull() ?: return null
        val last = words.last()
        // A sign or an operator before the amount, alone or glued to its count: the amount reads it.
        if (first.sign != null || Role.PLUS in first || Role.MINUS in first) return read(words)
        val before = Role.IN in first
        val after = Role.AGO in last || Role.FROM_NOW in last
        if (before == after) return null
        if (before) return read(words.drop(1))
        val amount = read(words.dropLast(1)) ?: return null
        return if (Role.AGO in last) -amount else amount
    }

    /** One part at [at]: its pieces (a glued "3h15min" is several), and where the next part starts. */
    private fun part(words: List<Word>, at: Int, limit: Double): Pair<List<Piece>, Int>? {
        val word = words.getOrNull(at) ?: return null
        // "half an hour", "half a week", "pół godziny": the fraction, an optional article, then the unit.
        fractionAt(words, at)?.let { (share, next) ->
            words.getOrNull(next)?.let(::unitMinutes)?.let { unit -> return listOf(Piece(share, unit)) to next + 1 }
        }
        // A unit alone is one of it: "tydzień", "godzinę", "kwadrans". A plural is never alone ("weeks").
        unitMinutes(word)?.let { unit -> return if (word.plural) null else listOf(Piece(1.0, unit)) to at + 1 }
        glued(word)?.let { pieces -> return pieces.takeIf { it.all { p -> p.quantity > 0 && p.quantity <= limit } }?.let { it to at + 1 } }
        // A count, then its unit: "3 days", "a week", "two and a half hours".
        val count = word.measure?.takeIf { it.unit == null }?.amount ?: return null
        if (count <= 0 || count > limit) return null
        val half = if (Role.AND in words.getOrNull(at + 1)) fractionAt(words, at + 2) else null
        val unitAt = half?.second ?: (at + 1)
        val unit = words.getOrNull(unitAt)?.let(::unitMinutes) ?: return null
        return listOf(Piece(count + (half?.first ?: 0.0), unit)) to unitAt + 1
    }

    /**
     * A fraction of the unit that follows at [at] ("half", "a half", "quarter of an"), as a share, and where the unit is. The
     * lexicon gives a fraction in minutes of an hour (30, 15), which is also its share of any other unit.
     */
    private fun fractionAt(words: List<Word>, at: Int): Pair<Double, Int>? {
        var i = at
        if (Role.ARTICLE in words.getOrNull(i) && words.getOrNull(i + 1)?.entry?.fraction != null) i++
        val fraction = words.getOrNull(i)?.entry?.fraction ?: return null
        i++
        if (Role.ARTICLE in words.getOrNull(i)) i++
        return fraction / 60.0 to i
    }

    /**
     * The pieces of a number glued to its unit: "30min", "1.5h", "3h15min", "2d3h". Each number is read as the language
     * writes numbers ([Word.measure]), so "1,5h" is an amount in Polish and not in English. A bare number after hours is
     * minutes: "1h30".
     */
    private fun glued(word: Word): List<Piece>? {
        val pieces = mutableListOf<Piece>()
        var rest = word
        while (true) {
            if (rest.text.firstOrNull()?.isDigit() != true) return null
            val measure = rest.measure ?: return null
            val after = measure.unit ?: return if (pieces.lastOrNull()?.unitMinutes == HOUR) pieces + Piece(measure.amount, 1) else null
            val name = after.text.takeWhile { !it.isDigit() }
            pieces += Piece(measure.amount, unitMinutes(word.read(name)) ?: return null)
            if (name.length == after.text.length) return pieces
            rest = word.read(after.text.drop(name.length))
        }
    }

    /** How long one of a unit is, in minutes, for ordering and for the arithmetic: a year, a month, a week, a day, an hour. */
    private fun unitMinutes(word: Word): Int? = when (word.unit) {
        Granularity.YEAR -> YEAR
        Granularity.MONTH -> MONTH
        Granularity.WEEK -> WEEK
        Granularity.DAY -> DAY
        null -> word.clockUnitMinutes
    }

    /** [quantity] of the unit of [unitMinutes] as an amount, or null when it is not a whole number of months or minutes. */
    private fun amountOf(quantity: Double, unitMinutes: Int): Amount? = when (unitMinutes) {
        YEAR -> months(quantity * 12)
        MONTH -> months(quantity)
        WEEK -> if (quantity == floor(quantity)) Amount(weeks = quantity.toInt()) else days(quantity * 7)
        DAY -> days(quantity)
        else -> whole(quantity * unitMinutes)?.let { Amount(minutes = it) }
    }

    private fun months(quantity: Double): Amount? = whole(quantity)?.let { Amount(months = it) }

    /** Days, with a fraction of a day as minutes: 1.5 days is a day and 720 minutes. */
    private fun days(quantity: Double): Amount? {
        val whole = floor(quantity).toInt()
        return whole((quantity - whole) * DAY)?.let { Amount(days = whole, minutes = it) }
    }

    private fun whole(value: Double): Int? = value.toInt().takeIf { abs(value - it) < 1e-6 }

    /** One count of one unit, the unit as its length in minutes. */
    private data class Piece(val quantity: Double, val unitMinutes: Int)

    /** The steps of [read], one function each. */
    private class Sum(private val words: List<Word>, private val limit: Double) {
        private var total = Amount()
        /** The length of the last unit, in minutes: without an operator the next one must be shorter. Null before the first part. */
        private var previous: Int? = null
        /** The sign of the parts that follow, set by the last sign or operator. */
        private var direction = 1
        /** Which way the whole amount counts: the direction of its first part. */
        private var leading = 1

        fun amount(): Amount? {
            var at = 0
            while (at < words.size) at = step(at) ?: return null
            // What is taken away may not reach past what is there: "2 months minus 3 days" is fine, "a day minus 2 days" is not.
            val length = total.months.toLong() * MONTH + (total.weeks * 7 + total.days).toLong() * DAY + total.minutes
            return total.takeIf { leading * it.months >= 0 && leading * length > 0 }
        }

        /** An operator or a sign at [start], then "and a half" closing the amount or the next part. Where the next step starts. */
        private fun step(start: Int): Int? {
            var at = start
            var explicit = false
            val join = words[at]
            when {
                Role.MINUS in join -> { direction = -1; explicit = true; at++ }
                Role.PLUS in join -> { direction = 1; explicit = true; at++ }
                previous != null && Role.AND in join -> at++
            }
            // A sign glued to the count: "+3 days", "-1w2d".
            var said = words
            val signed = words.getOrNull(at)
            val sign = signed?.sign
            if (signed != null && sign != null && signed.text.length > 1) {
                direction = sign; explicit = true
                said = words.toMutableList().also { it[at] = signed.unsigned ?: return null }
            }
            // "an hour and a half": half of the unit before, closing the amount.
            val last = previous
            val half = if (last != null) fractionAt(words, at) else null
            if (last != null && half != null && half.second == words.size) {
                add(amountOf(half.first, last) ?: return null)
                return words.size
            }
            val (pieces, next) = part(said, at, limit) ?: return null
            if (previous == null) leading = direction
            for ((quantity, unit) in pieces) {
                val before = previous
                if (!explicit && before != null && unit >= before) return null
                add(amountOf(quantity, unit) ?: return null)
                previous = unit
                explicit = false
            }
            return next
        }

        private fun add(more: Amount) {
            total += if (direction < 0) -more else more
        }
    }

    private const val HOUR = 60
    private const val DAY = 24 * HOUR
    private const val WEEK = 7 * DAY
    /** Thirty days: only for ordering units and for the sign check, as a month is no fixed length. */
    private const val MONTH = 30 * DAY
    private const val YEAR = 365 * DAY
}
