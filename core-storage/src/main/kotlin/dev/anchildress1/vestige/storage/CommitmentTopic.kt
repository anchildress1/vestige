package dev.anchildress1.vestige.storage

import android.util.Log
import org.json.JSONObject

internal fun readCommitmentTopic(json: String?): String? {
    val raw = json?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { JSONObject(raw).optString(KEY_TOPIC_OR_PERSON) }
        .onFailure {
            Log.w(
                TAG,
                "Malformed statedCommitmentJson (${it.javaClass.simpleName}); topic excluded",
            )
        }
        .getOrNull()
        ?.let(::normalizeCommitmentTopic)
}

internal fun normalizeCommitmentTopic(topic: String?): String? {
    val normalized = topic?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return normalized.takeUnless { it.lowercase() in SENTINEL_TOPICS }
}

private const val TAG = "VestigeCommitmentTopic"
private const val KEY_TOPIC_OR_PERSON = "topic_or_person"
private val SENTINEL_TOPICS = setOf("null", "none", "undefined", "n/a", "na")
