package dev.anchildress1.vestige.storage

import android.util.Log
import dev.anchildress1.vestige.model.EntryLensReceipt
import dev.anchildress1.vestige.model.Lens
import org.json.JSONArray
import org.json.JSONObject

/** JSON codec for the markdown/ObjectBox `lens_receipts` field. */
object EntryLensReceiptJson {
    fun encode(receipts: List<EntryLensReceipt>): String {
        if (receipts.isEmpty()) return "[]"
        val array = JSONArray()
        receipts.forEach { receipt ->
            array.put(
                JSONObject()
                    .put(KEY_LENS, receipt.lens.name)
                    .put(KEY_EXTRACTED, receipt.extracted)
                    .put(KEY_FIELDS, jsonObject(receipt.fields))
                    .put(KEY_FLAGS, JSONArray(receipt.flags))
                    .put(KEY_ATTEMPT_COUNT, receipt.attemptCount)
                    .put(KEY_ELAPSED_MS, receipt.elapsedMs)
                    .put(KEY_LAST_ERROR, receipt.lastError ?: JSONObject.NULL),
            )
        }
        return array.toString()
    }

    /** Lenient decode: a corrupt blob collapses to `[]`. Use [decodeOrNull] when the caller must
     *  tell "no receipts stored" apart from "stored receipts unreadable". */
    fun decode(json: String?): List<EntryLensReceipt> = decodeOrNull(json) ?: emptyList()

    /**
     * Returns the parsed receipts, an empty list for a legitimately-empty blob, or `null` when the
     * blob is non-empty but unparseable — so a corrupt receipt is not silently rendered as
     * "lens never ran".
     */
    fun decodeOrNull(json: String?): List<EntryLensReceipt>? {
        if (json.isNullOrBlank() || json.trim() == "[]") return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { index ->
                val obj = array.getJSONObject(index)
                val lens = Lens.valueOf(obj.getString(KEY_LENS))
                EntryLensReceipt(
                    lens = lens,
                    extracted = obj.optBoolean(KEY_EXTRACTED, false),
                    fields = normalizeObject(obj.optJSONObject(KEY_FIELDS)),
                    flags = (normalize(obj.opt(KEY_FLAGS)) as? List<*>)
                        ?.mapNotNull { it as? String }
                        ?: emptyList(),
                    attemptCount = obj.optInt(KEY_ATTEMPT_COUNT, 0),
                    elapsedMs = obj.optLong(KEY_ELAPSED_MS, 0L),
                    lastError = obj.optString(KEY_LAST_ERROR).takeIf { it.isNotBlank() },
                )
            }
        }.getOrElse {
            Log.w(TAG, "malformed lensReceiptsJson (len=${json.length})")
            null
        }
    }

    private fun jsonObject(map: Map<String, Any?>): JSONObject = JSONObject().also { target ->
        map.forEach { (key, value) -> target.put(key, jsonValue(value)) }
    }

    private fun jsonArray(values: Iterable<Any?>): JSONArray = JSONArray().also { array ->
        values.forEach { value -> array.put(jsonValue(value)) }
    }

    private fun jsonValue(value: Any?): Any? = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> jsonObject(value.entries.associate { it.key.toString() to it.value })
        is Iterable<*> -> jsonArray(value)
        is Array<*> -> jsonArray(value.asIterable())
        else -> value
    }

    private fun normalize(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().associateWith { key -> normalize(value.opt(key)) }
        is JSONArray -> List(value.length()) { idx -> normalize(value.opt(idx)) }
        else -> value
    }

    private fun normalizeObject(value: JSONObject?): Map<String, Any?> =
        value?.keys()?.asSequence()?.associateWith { key -> normalize(value.opt(key)) }.orEmpty()

    private const val TAG = "EntryLensReceiptJson"
    private const val KEY_LENS = "lens"
    private const val KEY_EXTRACTED = "extracted"
    private const val KEY_FIELDS = "fields"
    private const val KEY_FLAGS = "flags"
    private const val KEY_ATTEMPT_COUNT = "attempt_count"
    private const val KEY_ELAPSED_MS = "elapsed_ms"
    private const val KEY_LAST_ERROR = "last_error"
}
