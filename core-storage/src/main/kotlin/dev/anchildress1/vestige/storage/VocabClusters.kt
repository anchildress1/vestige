package dev.anchildress1.vestige.storage

import org.json.JSONArray
import org.json.JSONObject

/**
 * A single vocabulary cluster surfaced under a `VOCAB_FREQUENCY` pattern. Members share the
 * pattern's root token (the canonical "tired" / "stuck" / etc.) but use distinct framings —
 * different surface vocabulary describing the same underlying state. The cluster ID is
 * content-addressable per ADR-003 §"Detection algorithm" — same members in same order yields
 * the same id, so a re-run of clustering on the same evidence is a no-op write.
 *
 * @property clusterId SHA-256-hex of the sorted member entry-id list.
 * @property label Deterministic short label derived from member tags + tokens (≤24 chars).
 * @property description One-line "X entries · framings: a, b, c" summary.
 * @property exampleEntryId Representative member used by the screen to render a snippet.
 * @property memberEntryIds Sorted ascending. Sort order is part of the [clusterId] hash.
 */
data class VocabCluster(
    val clusterId: String,
    val label: String,
    val description: String,
    val exampleEntryId: Long,
    val memberEntryIds: List<Long>,
)

/**
 * Round-trip for [PatternEntity.vocabClustersJson]. Uses hand-built JSON strings on write so
 * key order is stable (matching the convention in [PatternSignature]) — the column is part of
 * the pattern's audit trail and unstable JSON would defeat downstream diffing.
 *
 * Schema:
 * ```
 * {
 *   "version": 1,
 *   "clusters": [
 *     { "id": "<sha>", "label": "<≤24>", "description": "<one-line>",
 *       "example_entry_id": 42, "member_entry_ids": [1, 7, 42, …] },
 *     …
 *   ]
 * }
 * ```
 * Returns an empty list on blank input or any parse failure — the caller treats absent vocab
 * data as "clustering hasn't run" rather than as a corruption signal.
 */
object VocabClustersCodec {
    const val SCHEMA_VERSION: Int = 1

    fun encode(clusters: List<VocabCluster>): String {
        if (clusters.isEmpty()) return ""
        val clustersBody = clusters.joinToString(",") { cluster ->
            val members = cluster.memberEntryIds.joinToString(",")
            // Hand-built so key order is "id,label,description,example_entry_id,member_entry_ids"
            // every time — JSONObject's insertion order is HashMap-dependent on some JVMs.
            """{"id":${jsonString(cluster.clusterId)},""" +
                """"label":${jsonString(cluster.label)},""" +
                """"description":${jsonString(cluster.description)},""" +
                """"example_entry_id":${cluster.exampleEntryId},""" +
                """"member_entry_ids":[$members]}"""
        }
        return """{"version":$SCHEMA_VERSION,"clusters":[$clustersBody]}"""
    }

    fun decode(json: String): List<VocabCluster> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val root = JSONObject(json)
            val arr: JSONArray = root.optJSONArray("clusters") ?: return@runCatching emptyList()
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val memberArr = obj.getJSONArray("member_entry_ids")
                    val members = buildList(memberArr.length()) {
                        for (m in 0 until memberArr.length()) add(memberArr.getLong(m))
                    }
                    add(
                        VocabCluster(
                            clusterId = obj.getString("id"),
                            label = obj.getString("label"),
                            description = obj.getString("description"),
                            exampleEntryId = obj.getLong("example_entry_id"),
                            memberEntryIds = members,
                        ),
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun jsonString(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }
}
