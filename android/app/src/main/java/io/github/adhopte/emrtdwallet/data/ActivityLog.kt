package io.github.adhopte.emrtdwallet.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

enum class ActivityType {
    WALLET_CREATED, SECURITY_CHANGED, ISSUED, ISSUANCE_REJECTED, ISSUANCE_FAILED,
    PRESENTED, PRESENTATION_DECLINED, PRESENTATION_FAILED, DELETED,
}

@Serializable
data class ActivityEntry(
    val id: String = UUID.randomUUID().toString(),
    val time: Long = System.currentTimeMillis(),
    val type: ActivityType,
    val title: String,
    val detail: String = "",
    /** Issuer or verifier involved, if any. */
    val party: String? = null,
    /** Credentials or attributes involved (e.g. the claims disclosed to a verifier). */
    val items: List<String> = emptyList(),
)

/** Local, append-only history of what happened in this wallet (kept on the device only). */
class ActivityLog(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(ActivityEntry.serializer())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<ActivityEntry>> = _entries.asStateFlow()

    private fun load(): List<ActivityEntry> =
        runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())

    @Synchronized
    fun add(entry: ActivityEntry) {
        val updated = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
        _entries.value = updated
        persist(updated)
    }

    fun add(type: ActivityType, title: String, detail: String = "", party: String? = null, items: List<String> = emptyList()) =
        add(ActivityEntry(type = type, title = title, detail = detail, party = party, items = items))

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
        persist(emptyList())
    }

    private fun persist(list: List<ActivityEntry>) {
        scope.launch {
            synchronized(file) {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(json.encodeToString(serializer, list))
                tmp.renameTo(file)
            }
        }
    }

    private companion object {
        const val MAX_ENTRIES = 500
    }
}
