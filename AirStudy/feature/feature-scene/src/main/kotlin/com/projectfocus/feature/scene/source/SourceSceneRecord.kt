package com.projectfocus.feature.scene.source

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Caller supplies the source-key suffix; this layer does not infer an account/device. */
data class SourceSceneScope(val suffix: String)

/** The six serialized source fields. iconKey is an icon/type key, never a unique id. */
data class SourceSceneRecord(
    val name: String,
    val iconKey: String,
    val color: Int,
    val isCustom: Boolean = false,
    val cloudId: Long = -1L,
    val clientUuid: String? = null,
)

enum class SourceSceneCollection { BUILT_IN, ENABLED, ALL_CREATED_CUSTOM }


data class SourceSceneOption(val optionId: String, val record: SourceSceneRecord)

data class SourceSceneSnapshot(
    val scope: SourceSceneScope,
    val collection: SourceSceneCollection,
    val options: List<SourceSceneOption>,
) {
    val records: List<SourceSceneRecord> get() = options.map { it.record }
    fun find(optionId: String): SourceSceneOption? = options.firstOrNull { it.optionId == optionId }
}

internal object SourceSceneOptionIds {
    /** Stable for unchanged records across reorder; exact duplicates retain separate ids.
     *
     * This is implementation-owned identity, not a claim about original cloud identity.
     * An edit to any retained field can change the id. Compiled R ids and locale labels
     * are excluded, so rebuilding resources/changing language does not change it.
     */
    fun snapshot(
        scope: SourceSceneScope,
        collection: SourceSceneCollection,
        records: List<SourceSceneRecord>,
    ): SourceSceneSnapshot {
        val occurrences = mutableMapOf<String, Int>()
        val options = records.map { record ->
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { output ->
                fun string(value: String) {
                    val bytes = value.toByteArray(Charsets.UTF_8)
                    output.writeInt(bytes.size)
                    output.write(bytes)
                }
                string(scope.suffix)
                string(collection.name)
                string(record.name)
                string(record.iconKey)
                output.writeInt(record.color)
                output.writeBoolean(record.isCustom)
                output.writeLong(record.cloudId)
                output.writeBoolean(record.clientUuid != null)
                record.clientUuid?.let(::string)
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val occurrence = occurrences.getOrDefault(digest, 0)
            occurrences[digest] = occurrence + 1
            SourceSceneOption("apk2-scene:$digest:$occurrence", record)
        }
        return SourceSceneSnapshot(scope, collection, options)
    }
}
