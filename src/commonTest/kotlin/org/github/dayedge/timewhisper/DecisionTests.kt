package org.github.dayedge.timewhisper

import kotlin.test.*
import org.github.dayedge.timewhisper.CandidateKind.EVENT
import org.github.dayedge.timewhisper.CandidateKind.TASK
import org.github.dayedge.timewhisper.taskorevent.Evidence
import org.github.dayedge.timewhisper.taskorevent.Intent
import org.github.dayedge.timewhisper.taskorevent.Ranking

/** The decision as a table: what was found, and what is offered. No text is parsed here. */
class DecisionTests {
    private val nothing = emptyList<CandidateKind>()
    /** What a tagger might say: clearly a task, clearly an event, clearly nothing, or undecided. */
    private val reads = mapOf(
        "task" to Intent(task = 0.8, event = 0.15, none = 0.05), "event" to Intent(task = 0.1, event = 0.85, none = 0.05),
        "nothing" to Intent(task = 0.02, event = 0.03, none = 0.95), "undecided" to Intent(task = 0.45, event = 0.5, none = 0.05),
    )

    @Test fun nothingWithoutATitleOrWithADateThatDoesNotExist() {
        assertEquals(nothing, Ranking.decide(Evidence(title = false, dateRead = true, day = true, time = true)), "tomorrow at 5")
        assertEquals(nothing, Ranking.decide(Evidence(impossibleDate = true, dateRead = true, intent = reads.getValue("task"))), "pay rent February 30")
        assertEquals(nothing, Ranking.decide(Evidence(title = false, taskMark = true)), "a lone +")
    }
    @Test fun aPriorityCountsForATaskUnlessThereIsAPlaceOrACalendar() {
        val urgentMeeting = Evidence(dateRead = true, day = true, time = true, span = true, priority = true)
        assertEquals(listOf(TASK, EVENT), Ranking.decide(urgentMeeting), "pay rent Friday 3-4pm !!")
        assertEquals(listOf(TASK, EVENT), Ranking.decide(Evidence(dateRead = true, day = true, time = true, span = true, priority = true, intent = reads.getValue("event"))),
            "urgent call with the team tomorrow 3-4pm: the tagger's reading does not outweigh the priority")
        assertEquals(listOf(EVENT, TASK), Ranking.decide(Evidence(dateRead = true, day = true, time = true, span = true, priority = true, place = true)), "urgent review Friday 10-11 @HQ")
        assertEquals(listOf(TASK), Ranking.decide(Evidence(dateRead = true, day = true, time = true, priority = true, place = true)), "urgent review Friday at 10 @HQ: a place alone does not outweigh it")
        assertEquals(listOf(TASK), Ranking.decide(Evidence(dateRead = true, day = true, time = true, priority = true, intent = Intent(task = 0.05, event = 0.9, none = 0.05))),
            "urgent incident review tuesday at 10: without anything that takes up time, the priority stands")
        assertEquals(listOf(TASK), Ranking.decide(Evidence(dateRead = true, day = true, priority = true, intent = reads.getValue("task"))), "pay rent Friday !!")
        assertEquals(listOf(TASK), Ranking.decide(Evidence(priority = true, intent = reads.getValue("event"))), "an event without a day is still no event")
    }
    @Test fun oneReasonIsEnoughToOfferSomething() {
        assertEquals(nothing, Ranking.decide(Evidence()), "groceries")
        assertEquals(listOf(TASK), Ranking.decide(Evidence(intent = reads.getValue("task"))), "buy groceries, read as a task by a tagger")
        assertEquals(nothing, Ranking.decide(Evidence(intent = reads.getValue("undecided"))), "an undecided tagger is no reason")
        assertEquals(listOf(TASK), Ranking.decide(Evidence(list = true)), "groceries #home")
        assertEquals(listOf(TASK), Ranking.decide(Evidence(repeat = true)), "groceries weekly, with no day yet")
        assertEquals(listOf(TASK), Ranking.decide(Evidence(reminder = true)))
        assertEquals(listOf(TASK, EVENT), Ranking.decide(Evidence(dateRead = true, day = true)), "groceries tomorrow")
    }
    @Test fun placeOrCalendarAloneIsNotAReason() {
        assertEquals(nothing, Ranking.decide(Evidence(place = true)))
        assertEquals(nothing, Ranking.decide(Evidence(calendar = true)))
    }
    @Test fun forcedKindIsTheOnlyOneOffered() {
        val full = Evidence(dateRead = true, day = true, time = true, span = true, priority = true)
        assertEquals(listOf(TASK, EVENT), Ranking.decide(full))
        assertEquals(listOf(TASK), Ranking.decide(Evidence(taskMark = true, dateRead = true, day = true, time = true, span = true)), "+ call Bob Friday 3-4pm")
        assertEquals(listOf(TASK), Ranking.decide(Evidence(statedKind = TASK)), "todo sort receipts")
        assertEquals(listOf(EVENT), Ranking.decide(Evidence(statedKind = EVENT, dateRead = true, day = true)), "t:event dentist friday")
    }
    @Test fun statedKindComesBeforeTheMark() {
        assertEquals(listOf(EVENT), Ranking.decide(Evidence(statedKind = EVENT, taskMark = true)), "the mark still makes it worth offering")
        assertEquals(nothing, Ranking.decide(Evidence(statedKind = EVENT)), "a stated event with nothing else is not worth offering")
        assertEquals(listOf(TASK), Ranking.decide(Evidence(statedKind = TASK)), "a stated task is")
    }
    @Test fun aTaggerSureThereIsNothingToCreateWinsUnlessSomethingWasStated() {
        val nothingAsked = reads.getValue("nothing")
        assertEquals(nothing, Ranking.decide(Evidence(intent = nothingAsked, dateRead = true, day = true)), "already paid the bill yesterday")
        assertEquals(listOf(TASK), Ranking.decide(Evidence(intent = nothingAsked, list = true)), "a list was stated")
        assertEquals(listOf(TASK), Ranking.decide(Evidence(intent = nothingAsked, taskMark = true)), "a task was forced")
    }
    @Test fun hardSignals() {
        val dated = Evidence(dateRead = true, day = true, time = true)
        assertEquals(listOf(EVENT), Ranking.decide(Evidence(dateRead = true, day = true, time = true, span = true)), "a duration")
        assertEquals(listOf(TASK), Ranking.decide(Evidence(dateRead = true, day = true, time = true, priority = true)), "a priority")
        assertEquals(listOf(TASK, EVENT), Ranking.decide(Evidence(dateRead = true, day = true, time = true, span = true, priority = true)), "both")
        assertEquals(listOf(TASK), Ranking.decide(Evidence(dateRead = true, day = false)), "a date span that gave no day")
        assertEquals(listOf(EVENT, TASK), Ranking.decide(dated), "neither")
    }
    @Test fun softSignalsOnlySetTheOrder() {
        fun dated(time: Boolean, place: Boolean = false, calendar: Boolean = false, list: Boolean = false, due: Boolean = false, reads: Intent = Intent(0.0, 0.0, 0.0)) =
            Ranking.decide(Evidence(dateRead = true, day = true, time = time, place = place, calendar = calendar, list = list, due = due, intent = reads))
        assertEquals(listOf(TASK, EVENT), dated(time = false), "no time, nothing favoured")
        assertEquals(listOf(EVENT, TASK), dated(time = true), "a time, nothing favoured")
        assertEquals(listOf(EVENT, TASK), dated(time = false, place = true), "a place favours the event even without a time")
        assertEquals(listOf(EVENT, TASK), dated(time = false, calendar = true))
        assertEquals(listOf(TASK, EVENT), dated(time = true, list = true), "a list favours the task even with a time")
        assertEquals(listOf(TASK, EVENT), dated(time = true, due = true), "so does a deadline")
        assertEquals(listOf(TASK, EVENT), dated(time = true, reads = reads.getValue("task")), "a tagger reading a task")
        assertEquals(listOf(EVENT, TASK), dated(time = false, reads = reads.getValue("event")), "a tagger reading an event")
        assertEquals(listOf(EVENT, TASK), dated(time = true, reads = reads.getValue("undecided")), "an undecided tagger leaves it to the time")
        assertEquals(listOf(EVENT, TASK), dated(time = true, list = true, reads = reads.getValue("event")), "a list and an event reading: the time decides")
        assertEquals(listOf(EVENT, TASK), dated(time = true, place = true, list = true), "both sides favoured: the time decides")
        assertEquals(listOf(TASK, EVENT), dated(time = false, place = true, list = true))
    }
}
