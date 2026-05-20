package dev.anchildress1.vestige.storage

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Read the root token out of a `VOCAB_FREQUENCY` pattern's `signatureJson`. Returns `null` when
 * the JSON is malformed OR the `token` field is missing/blank — both indicate a data invariant
 * break for a `VOCAB_FREQUENCY` row and the caller should log + skip rather than silently
 * fall through to an empty rootToken (which would produce degenerate labels downstream).
 */
fun vocabRootTokenOrNull(signatureJson: String): String? = runCatching {
    JSONObject(signatureJson).optString("token", "").takeIf { it.isNotBlank() }
}.getOrNull()

/**
 * Canonical (signatureJson, patternId) for a `VOCAB_FREQUENCY` pattern with root [token].
 * Mirrors the detector's own signature serialization (including the token stemmer) — debug
 * fixtures and tests that pre-insert vocab patterns must use both fields so the next detection
 * pass updates the seeded row instead of inserting a duplicate.
 */
fun vocabPatternIdentity(token: String): VocabPatternIdentity {
    val sig = PatternSignature.forVocabToken(token)
    return VocabPatternIdentity(patternId = sig.patternId, signatureJson = sig.json)
}

/** Result of [vocabPatternIdentity]. */
data class VocabPatternIdentity(val patternId: String, val signatureJson: String)

/**
 * A single vocabulary cluster surfaced under a `VOCAB_FREQUENCY` pattern. Members share the
 * pattern's root token but use distinct framings — different surface vocabulary describing the
 * same underlying state. Construct via [of]; the constructor is private so [clusterId] always
 * matches the canonical hash of the sorted member list.
 */
@Suppress("DataClassPrivateConstructor")
data class VocabCluster private constructor(
    val clusterId: String,
    val label: String,
    val description: String,
    val exampleEntryId: Long,
    val memberEntryIds: List<Long>,
) {
    companion object {
        const val MAX_LABEL_CHARS: Int = 24

        /**
         * Build a [VocabCluster]. Sorts members ascending, derives [clusterId] as SHA-256 hex of
         * the sorted id list, and fails fast on the structural invariants — same evidence ⇒
         * same id is what guarantees the orchestrator's no-op re-stamp.
         */
        fun of(members: List<Long>, label: String, description: String, exampleEntryId: Long): VocabCluster {
            require(members.isNotEmpty()) { "VocabCluster.members must be non-empty" }
            require(label.length <= MAX_LABEL_CHARS) {
                "VocabCluster.label must be ≤$MAX_LABEL_CHARS chars (got ${label.length})"
            }
            val sortedMembers = members.sorted()
            require(exampleEntryId in sortedMembers) {
                "VocabCluster.exampleEntryId ($exampleEntryId) must be one of memberEntryIds"
            }
            return VocabCluster(
                clusterId = sha256Hex(sortedMembers),
                label = label,
                description = description,
                exampleEntryId = exampleEntryId,
                memberEntryIds = sortedMembers,
            )
        }

        /**
         * Internal seam used by [VocabClustersCodec.decode]: trusts the inputs verbatim (the
         * caller has already parsed and validated the JSON shape) so we don't re-hash on every
         * screen load.
         */
        internal fun fromTrustedJson(
            clusterId: String,
            label: String,
            description: String,
            exampleEntryId: Long,
            memberEntryIds: List<Long>,
        ): VocabCluster = VocabCluster(clusterId, label, description, exampleEntryId, memberEntryIds)

        internal fun sha256Hex(sortedMemberIds: List<Long>): String {
            val canonical = sortedMemberIds.joinToString(",")
            val bytes = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            // `HexFormat.formatHex` produces exactly 64 lowercase hex chars per 32-byte digest.
            // The `"%02x".format(byte)` shorthand sign-extends negative bytes to 8 hex chars,
            // breaking the 64-hex invariant non-deterministically.
            return HexFormat.of().formatHex(bytes)
        }
    }
}

/**
 * Round-trip for [PatternEntity.vocabClustersJson] plus the cheap `evidence_hash` the
 * orchestrator's second pass uses to skip re-clustering when supporting evidence is unchanged.
 * Hand-built JSON on write — key order is stable, which keeps the audit trail byte-identical
 * for unchanged evidence and lets the dirty gate compare strings without re-parsing.
 *
 * Decode catches [JSONException] only and emits a Log.w with patternless context (no payload).
 * Any non-parse exception (NPE / OOM) propagates, so a real bug doesn't get buried as "no
 * vocab data."
 */
object VocabClustersCodec {
    const val SCHEMA_VERSION: Int = 1

    /**
     * Compute the evidence hash for a set of supporting-entry ids. Stable, deterministic, fast.
     * Re-running clustering when this hash matches the persisted envelope's `evidence_hash` is
     * pointless — the inputs haven't changed.
     */
    fun evidenceHashOf(memberIds: List<Long>): String = VocabCluster.sha256Hex(memberIds.sorted())

    /** Pull the `evidence_hash` from a previously-encoded envelope, or `null` when absent. */
    fun evidenceHashIn(json: String): String? {
        if (json.isBlank()) return null
        return runCatching { JSONObject(json).optString("evidence_hash", "").takeIf { it.isNotBlank() } }
            .getOrNull()
    }

    fun encode(clusters: List<VocabCluster>, evidenceHash: String): String {
        if (clusters.isEmpty()) return ""
        val clustersBody = clusters.joinToString(",") { cluster ->
            val members = cluster.memberEntryIds.joinToString(",")
            // Stable key order: id, label, description, example_entry_id, member_entry_ids.
            """{"id":${jsonString(cluster.clusterId)},""" +
                """"label":${jsonString(cluster.label)},""" +
                """"description":${jsonString(cluster.description)},""" +
                """"example_entry_id":${cluster.exampleEntryId},""" +
                """"member_entry_ids":[$members]}"""
        }
        return """{"version":$SCHEMA_VERSION,""" +
            """"evidence_hash":${jsonString(evidenceHash)},""" +
            """"clusters":[$clustersBody]}"""
    }

    @Suppress("ReturnCount") // Blank input + missing-key + JSON failure each yield empty distinctly.
    fun decode(json: String): List<VocabCluster> {
        if (json.isBlank()) return emptyList()
        return try {
            val root = JSONObject(json)
            val arr: JSONArray = root.optJSONArray("clusters") ?: return emptyList()
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val memberArr = obj.getJSONArray("member_entry_ids")
                    val members = buildList(memberArr.length()) {
                        for (m in 0 until memberArr.length()) add(memberArr.getLong(m))
                    }
                    val label = obj.getString("label")
                    val id = obj.getString("id")
                    // Drop structurally-bad clusters — empty members or blank label would crash
                    // `VocabDistributionSegment.init` downstream. Log + skip beats compose throw.
                    if (members.isEmpty() || label.isBlank()) {
                        Log.w(TAG, "vocab clusters decode: skipping invalid persisted cluster id=$id")
                        continue
                    }
                    add(
                        VocabCluster.fromTrustedJson(
                            clusterId = id,
                            label = label,
                            description = obj.getString("description"),
                            exampleEntryId = obj.getLong("example_entry_id"),
                            memberEntryIds = members,
                        ),
                    )
                }
            }
        } catch (e: JSONException) {
            Log.w(TAG, "vocab clusters decode failed len=${json.length} cause=${e.javaClass.simpleName}")
            emptyList()
        }
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

    private const val TAG: String = "VestigeVocabCodec"
}
