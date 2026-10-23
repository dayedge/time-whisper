package org.github.dayedge.timewhisper.taskorevent

import org.github.dayedge.timewhisper.CandidateKind

/**
 * What was found in the text, as far as the decision cares. No words, no spans and no lexicon: only whether something was there.
 * Step 5 fills it in; [Ranking.decide] is the only reader.
 */
internal class Evidence(
    /** A word that carries content is left for a title. */
    val title: Boolean = true,
    /** The text names a date that does not exist: "February 30". */
    val impossibleDate: Boolean = false,
    /** The kind was stated outright: "t:task", "type:event", "todo". */
    val statedKind: CandidateKind? = null,
    /** A leading "+". */
    val taskMark: Boolean = false,
    /** How much the tagger reads the text as a task, an event or nothing. */
    val intent: Intent = Intent(0.0, 0.0, 0.0),
    /** Some date or time was read. */
    val dateRead: Boolean = false,
    /** The result has a day. */
    val day: Boolean = false,
    /** The result has a start time. */
    val time: Boolean = false,
    /** The result takes up time: an end, a duration, a range of days, or "all day". */
    val span: Boolean = false,
    /** A deadline was stated: "by friday". */
    val due: Boolean = false,
    val priority: Boolean = false,
    val list: Boolean = false,
    val calendar: Boolean = false,
    val place: Boolean = false,
    val repeat: Boolean = false,
    val reminder: Boolean = false,
) {
    /** Nothing can be made of the text at all: no title, or a date that does not exist. */
    val unusable: Boolean get() = !title || impossibleDate
}

/** Step 6: whether to offer a task, an event, both or nothing, and in which order. [decide] is the whole rule. */
internal object Ranking {
    /**
     * Step 6. What to offer for a text, in order. An empty list means nothing is worth offering.
     * This is the whole rule; nothing else in the parser decides between a task and an event.
     *
     * 1. **Anything to offer?** Not without a title, and not with a date that does not exist. Not when the tagger is sure the
     *    text asks for nothing ("already paid the bill yesterday") and nothing was stated outright. Otherwise one reason is
     *    enough: a task was asked for (`+`, `t:task`, `todo`, or the tagger reads it as a task), a date or time was read, or
     *    there is a list, priority, repeat or reminder. `groceries` has a title and no reason; `buy groceries` has the
     *    tagger's reading as a task; `groceries tomorrow` has a date.
     * 2. **A kind was forced:** only that kind. A stated kind (`t:event`, `todo`) comes before `+`.
     * 3. **Hard signals.** Something that takes up time is an event; a priority is a task, and only a task keeps it. Both →
     *    task, then event; event first when there is also a place or a calendar. The tagger's reading does not change this: it
     *    is near certain for anything that sounds like a meeting, so it would decide every time. Only the first → event. Only a
     *    priority, or no day at all → task.
     * 4. **Soft signals** only set the order of the two. A place, a calendar, or a tagger that reads the text clearly more as
     *    an event favours the event; a list, a deadline, or a tagger that reads it clearly more as a task, favours the task.
     *    If neither side or both are favoured, a stated time puts the event first.
     *
     * | Text | What decides | Offered |
     * | --- | --- | --- |
     * | **A** `Standup tmrw 10am for 30 minutes every weekday @Office` | a duration, no priority | event |
     * | **B** `Pay rent next Friday #home !! remind me 1 day before` | a priority, nothing that takes up time | task |
     * | **C** `Dentist next Friday at 2:30pm` | nothing hard, nothing soft, a time | event, task |
     * | `Dentist next Friday` | the same without a time | task, event |
     * | `Pay rent Friday 3-4pm !!` | a time range and a priority | task, event |
     * | `urgent call with the team tomorrow 3-4pm` | a time range and a priority, however the tagger reads it | task, event |
     * | `Lunch Friday at 12 at Main Office` | a place | event, task |
     * | `remind me to call Bob Friday at 5` | the tagger reads a task | task, event |
     * | `+ call Bob Friday 3-4pm` | forced | task |
     * | `tomorrow at 5` | no title | nothing |
     */
    fun decide(e: Evidence): List<CandidateKind> {
        // 1. Anything to offer?
        if (e.unusable) return emptyList()
        val forced = e.statedKind ?: if (e.taskMark) CandidateKind.TASK else null
        val metadata = e.list || e.priority || e.repeat || e.reminder
        if (forced == null && !metadata && e.intent.none >= NOTHING) return emptyList()
        val taskAskedFor = e.taskMark || forced == CandidateKind.TASK || e.intent.task >= ASKED
        if (!taskAskedFor && !e.dateRead && !metadata) return emptyList()
        // 2. A forced kind.
        if (forced != null) return listOf(forced)
        // 3. Hard signals.
        if (e.span && e.priority) return order(eventFirst = e.place || e.calendar)
        if (e.span) return listOf(CandidateKind.EVENT)
        if (e.priority || !e.day) return listOf(CandidateKind.TASK)
        // 4. Soft signals set the order.
        val lean = e.intent.event - e.intent.task
        val favoursEvent = e.place || e.calendar || lean >= LEAN
        val favoursTask = e.list || e.due || lean <= -LEAN
        val eventFirst = if (favoursEvent == favoursTask) e.time else favoursEvent
        return order(eventFirst)
    }

    /** Both kinds, the event first or the task first. */
    private fun order(eventFirst: Boolean) =
        if (eventFirst) listOf(CandidateKind.EVENT, CandidateKind.TASK) else listOf(CandidateKind.TASK, CandidateKind.EVENT)

    /** How sure the tagger must be that a text asks for nothing before nothing is offered. */
    private const val NOTHING = 0.9
    /** How much the tagger must read a text as a task for that alone to be a reason to offer one. */
    private const val ASKED = 0.5
    /** How much more the tagger must read a text as one kind than the other to favour it. */
    private const val LEAN = 0.3
}

/** The tagger's view of the whole text, each 0 to 1. [Ranking.decide] reads it. */
internal data class Intent(val task: Double, val event: Double, val none: Double)
