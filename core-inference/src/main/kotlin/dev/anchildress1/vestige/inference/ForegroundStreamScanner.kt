package dev.anchildress1.vestige.inference

/**
 * Incremental tag scanner for the streamed `<transcription>…</transcription><follow_up>…</follow_up>`
 * envelope. Each [accept] call appends a chunk to a cumulative buffer and scans the whole buffer,
 * so a tag split across two chunks (`</transcr` + `iption>`) resolves once both halves land.
 *
 * Scope: progressive UI surfacing only. The authoritative verdict — ordering, duplicate blocks,
 * empty bodies — stays with [ForegroundResponseParser] run on the final buffer. The scanner never
 * emits a partial close tag as body text: when `</follow_up>` is not yet seen it withholds the
 * trailing bytes that could be its prefix, and any withheld tail is corrected by the terminal
 * parse. Not thread-safe — one scanner per stream, driven from a single collector.
 */
internal class ForegroundStreamScanner {

    private val buffer = StringBuilder()
    private var transcriptionEmitted = false
    private var followUpBodyStart = -1
    private var followUpEmittedLen = 0

    /** The full text accumulated so far — handed to [ForegroundResponseParser] at stream end. */
    val accumulated: String get() = buffer.toString()

    fun accept(chunk: String): List<ForegroundStreamEvent> {
        buffer.append(chunk)
        val events = ArrayList<ForegroundStreamEvent>(EXPECTED_EVENTS_PER_CHUNK)
        emitTranscription(events)
        emitFollowUpDelta(events)
        return events
    }

    private fun emitTranscription(events: MutableList<ForegroundStreamEvent>) {
        if (transcriptionEmitted) return
        val close = buffer.indexOf(T_CLOSE)
        val open = buffer.indexOf(T_OPEN)
        if (close < 0 || open < 0 || open >= close) return
        transcriptionEmitted = true
        val body = buffer.substring(open + T_OPEN.length, close).trim()
        if (body.isNotEmpty()) events += ForegroundStreamEvent.Transcription(body)
    }

    private fun emitFollowUpDelta(events: MutableList<ForegroundStreamEvent>) {
        if (followUpBodyStart < 0) {
            val open = buffer.indexOf(F_OPEN)
            if (open < 0) return
            followUpBodyStart = open + F_OPEN.length
        }
        val close = buffer.indexOf(F_CLOSE, followUpBodyStart)
        val visibleEnd = if (close >= 0) {
            close
        } else {
            // Close tag not yet seen — hold back the bytes that could be its prefix so a
            // partial `</follow_up` is never surfaced as body text.
            (buffer.length - (F_CLOSE.length - 1)).coerceAtLeast(followUpBodyStart)
        }
        val from = followUpBodyStart + followUpEmittedLen
        if (visibleEnd <= from) return
        val delta = buffer.substring(from, visibleEnd)
        followUpEmittedLen = visibleEnd - followUpBodyStart
        if (delta.isNotEmpty()) events += ForegroundStreamEvent.FollowUpDelta(delta)
    }

    private companion object {
        const val T_OPEN = "<transcription>"
        const val T_CLOSE = "</transcription>"
        const val F_OPEN = "<follow_up>"
        const val F_CLOSE = "</follow_up>"
        const val EXPECTED_EVENTS_PER_CHUNK = 2
    }
}
