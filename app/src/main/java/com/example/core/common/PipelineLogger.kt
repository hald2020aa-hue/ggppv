package com.example.core.common

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Structured 11-stage data pipeline logger for Android Logcat ("MangaPipeline").
 * Tracks every step from clicking a source to rendering the manga list in Compose UI.
 * Never logs cookies, tokens, session headers, or personal data.
 */
enum class PipelineStage(val stepNumber: Int, val stageName: String) {
    SOURCE_SELECTED(1, "SOURCE_SELECTED"),
    SOURCE_FOUND_IN_REGISTRY(2, "SOURCE_FOUND_IN_REGISTRY"),
    GET_POPULAR_CALLED(3, "GET_POPULAR_CALLED"),
    NETWORK_REQUEST_STARTED(4, "NETWORK_REQUEST_STARTED"),
    NETWORK_RESPONSE_RECEIVED(5, "NETWORK_RESPONSE_RECEIVED"),
    PARSER_STARTED(6, "PARSER_STARTED"),
    PARSER_COMPLETED(7, "PARSER_COMPLETED"),
    MANGA_RESULTS_RECEIVED(8, "MANGA_RESULTS_RECEIVED"),
    REPOSITORY_UPDATED(9, "REPOSITORY_UPDATED"),
    VIEWMODEL_STATE_UPDATED(10, "VIEWMODEL_STATE_UPDATED"),
    UI_RENDERED_RESULTS(11, "UI_RENDERED_RESULTS")
}

data class PipelineLogEntry(
    val timestampMs: Long = System.currentTimeMillis(),
    val stage: PipelineStage,
    val sourceId: String,
    val sourceName: String,
    val resultCount: Int = 0,
    val status: String,
    val failureReason: String? = null,
    val details: String? = null
) {
    fun formatLogLine(): String {
        val base = buildString {
            append("[${stage.stepNumber}/11 ${stage.stageName}] ")
            append("sourceId=$sourceId | ")
            append("sourceName=\"$sourceName\" | ")
            append("count=$resultCount | ")
            append("status=$status")
            if (!details.isNullOrBlank()) {
                append(" | details=$details")
            }
            if (!failureReason.isNullOrBlank()) {
                append(" | reason=\"$failureReason\"")
            }
        }
        return base
    }
}

object PipelineLogger {
    const val TAG = "MangaPipeline"
    private const val MAX_HISTORY = 250

    private val entries = CopyOnWriteArrayList<PipelineLogEntry>()
    private val _logsFlow = MutableStateFlow<List<PipelineLogEntry>>(emptyList())
    val logsFlow: StateFlow<List<PipelineLogEntry>> = _logsFlow.asStateFlow()

    fun log(
        stage: PipelineStage,
        sourceId: String,
        sourceName: String = sourceId,
        resultCount: Int = 0,
        status: String = "OK",
        failureReason: String? = null,
        details: String? = null,
        throwable: Throwable? = null
    ) {
        val safeReason = failureReason ?: throwable?.let { "${it.javaClass.simpleName}: ${it.message}" }
        val entry = PipelineLogEntry(
            stage = stage,
            sourceId = sourceId,
            sourceName = sourceName,
            resultCount = resultCount,
            status = status,
            failureReason = safeReason,
            details = details
        )

        entries.add(entry)
        while (entries.size > MAX_HISTORY) {
            entries.removeAt(0)
        }
        _logsFlow.value = entries.toList()

        val line = entry.formatLogLine()
        runCatching {
            if (status.equals("FAILED", ignoreCase = true) || status.equals("ERROR", ignoreCase = true) || safeReason != null) {
                if (throwable != null) {
                    Log.e(TAG, line, throwable)
                } else {
                    Log.e(TAG, line)
                }
            } else {
                Log.d(TAG, line)
            }
        }
        // Also print to standard output during local JVM unit tests where android.util.Log is stubbed
        println("$TAG: $line")
    }

    fun getLogsForSource(sourceId: String): List<PipelineLogEntry> {
        return entries.filter { it.sourceId == sourceId }
    }

    fun getLastFailureForSource(sourceId: String): PipelineLogEntry? {
        return entries.lastOrNull { it.sourceId == sourceId && !it.failureReason.isNullOrBlank() }
    }

    fun clear() {
        entries.clear()
        _logsFlow.value = emptyList()
    }
}
