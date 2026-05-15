package dev.anchildress1.vestige.ui.history

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.anchildress1.vestige.storage.EntryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

data class HistoryStats(
    val totalEntries: Int,
    val daysTracked: Int,
    val thisWeek: Int,
    val avgAudioLabel: String,
    val densityBuckets: List<Int>,
)

data class HistoryGroup(val dateKey: String, val headerLabel: String, val summaries: List<HistorySummary>)

data class HistoryUiState(
    val entries: List<HistorySummary> = emptyList(),
    val groups: List<HistoryGroup> = emptyList(),
    val loading: Boolean = true,
    val stats: HistoryStats? = null,
)

class HistoryViewModel(
    private val entryStore: EntryStore,
    private val zoneId: ZoneId = ZoneOffset.UTC,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    dataRevision: StateFlow<Long> = MutableStateFlow(0L),
) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            dataRevision.collectLatest { load() }
        }
    }

    private suspend fun load() {
        val nowMs = System.currentTimeMillis()
        val rows = runCatching {
            withContext(ioDispatcher) { entryStore.listCompleted(limit = LIST_LIMIT) }
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to load history", e)
            emptyList()
        }
        val summaries = rows.map { entity -> HistorySummary.from(entity, zoneId) }
        _state.value = HistoryUiState(
            entries = summaries,
            groups = buildGroups(summaries, nowMs),
            loading = false,
            stats = if (summaries.isNotEmpty()) buildStats(summaries, nowMs) else null,
        )
    }

    private fun buildGroups(summaries: List<HistorySummary>, nowMs: Long): List<HistoryGroup> {
        val nowDate = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()
        return summaries
            .groupBy { Instant.ofEpochMilli(it.timestampEpochMs).atZone(zoneId).toLocalDate() }
            .entries
            .sortedByDescending { it.key }
            .map { (date, daySummaries) ->
                HistoryGroup(
                    dateKey = date.toString(),
                    headerLabel = HistoryDateFormatter.formatSectionHeader(date, nowDate),
                    summaries = daySummaries.sortedByDescending { it.timestampEpochMs },
                )
            }
    }

    private fun buildStats(summaries: List<HistorySummary>, nowMs: Long): HistoryStats {
        val nowDate = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()
        val firstDate = Instant.ofEpochMilli(summaries.last().timestampEpochMs).atZone(zoneId).toLocalDate()
        val daysTracked = ChronoUnit.DAYS.between(firstDate, nowDate).toInt() + 1
        val weekAgoMs = nowMs - WEEK_MS
        val thisWeek = summaries.count { it.timestampEpochMs >= weekAgoMs }

        val totalDurationMs = summaries.sumOf { it.durationMs }
        val avgAudioLabel = when {
            totalDurationMs <= 0L -> "—"

            daysTracked <= 0 -> "—"

            else -> {
                val avgMs = totalDurationMs / daysTracked
                if (avgMs >= 60_000L) "~${avgMs / 60_000L}m" else "~${avgMs / 1_000L}s"
            }
        }

        val bucketCounts = IntArray(DENSITY_DAYS)
        summaries.forEach { s ->
            val entryDate = Instant.ofEpochMilli(s.timestampEpochMs).atZone(zoneId).toLocalDate()
            val daysAgo = ChronoUnit.DAYS.between(entryDate, nowDate).toInt()
            if (daysAgo in 0 until DENSITY_DAYS) {
                bucketCounts[DENSITY_DAYS - 1 - daysAgo]++
            }
        }

        return HistoryStats(
            totalEntries = summaries.size,
            daysTracked = daysTracked,
            thisWeek = thisWeek,
            avgAudioLabel = avgAudioLabel,
            densityBuckets = bucketCounts.toList(),
        )
    }

    private companion object {
        private const val LIST_LIMIT = 100
        private const val DENSITY_DAYS = 30
        private const val WEEK_MS = 7L * 24 * 60 * 60 * 1_000
        private const val TAG = "HistoryViewModel"
    }
}
