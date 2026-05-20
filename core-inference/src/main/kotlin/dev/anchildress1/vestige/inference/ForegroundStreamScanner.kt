package dev.anchildress1.vestige.inference

/**
 * Incremental scanner for the streamed foreground envelope — progressive UI surfacing only;
 * [ForegroundResponseParser] on the final buffer owns the authoritative verdict. Not thread-safe
 * — one scanner per stream, one collector.
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
        val body = taggedTranscriptionBody() ?: labeledTranscriptionBody() ?: return
        transcriptionEmitted = true
        if (body.isNotEmpty()) events += ForegroundStreamEvent.Transcription(body)
    }

    private fun taggedTranscriptionBody(): String? {
        val close = buffer.indexOf(T_CLOSE)
        val open = buffer.indexOf(T_OPEN)
        if (close < 0 || open < 0 || open >= close) return null
        return buffer.substring(open + T_OPEN.length, close).trim()
    }

    private fun labeledTranscriptionBody(): String? {
        val labelOpen = TRANSCRIPTION_LABEL.find(buffer)
        val followUpLabel = labelOpen?.let { FOLLOW_UP_LABEL.find(buffer, it.range.last + 1) } ?: return null
        return buffer.substring(labelOpen.range.last + 1, followUpLabel.range.first).trim()
    }

    private fun emitFollowUpDelta(events: MutableList<ForegroundStreamEvent>) {
        if (followUpBodyStart < 0) {
            val open = buffer.indexOf(F_OPEN)
            followUpBodyStart = if (open >= 0) {
                open + F_OPEN.length
            } else {
                val label = FOLLOW_UP_LABEL.find(buffer) ?: return
                label.range.last + 1
            }
        }
        val close = buffer.indexOf(F_CLOSE, followUpBodyStart)
        val visibleEnd = if (close >= 0) {
            close
        } else if (buffer.indexOf(F_OPEN) < 0) {
            buffer.length
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
        val TRANSCRIPTION_LABEL = Regex("(?im)^\\s*transcription\\s*:\\s*")
        val FOLLOW_UP_LABEL = Regex("(?im)^\\s*_?follow_up\\s*:\\s*")
        const val EXPECTED_EVENTS_PER_CHUNK = 2
    }
}
