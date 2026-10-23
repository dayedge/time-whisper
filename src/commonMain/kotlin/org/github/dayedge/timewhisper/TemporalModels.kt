package org.github.dayedge.timewhisper

/** A stretch of time named by text. A point in time has equal start and end and [hasExplicitTime] set. */
public data class TemporalSpec(
    val start: Moment,
    val endExclusive: Moment,
    val granularity: Granularity,
    val hasExplicitTime: Boolean = false,
)

/** A date or time found inside longer text, and that text with the date words taken out. */
public data class FoundTemporal(val temporal: TemporalSpec, val remainingText: String)
