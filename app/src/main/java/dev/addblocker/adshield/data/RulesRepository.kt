package dev.addblocker.adshield.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

enum class ListKind(val storageKey: String) {
    BUNDLED("bundled"),
    REMOTE("remote"),
    IMPORTED("imported")
}

data class BlockList(
    val id: String,
    val name: String,
    val kind: ListKind,
    /** Asset path for bundled lists, absolute file path for downloaded/imported ones. */
    val location: String,
    val enabled: Boolean,
    val ruleCount: Int = 0,
    val updatedAt: Long = 0L,
    val description: String = ""
)

data class RulesState(
    val ready: Boolean = false,
    val activeRules: Int = 0,
    val lists: List<BlockList> = emptyList(),
    val customBlocked: List<String> = emptyList(),
    val customAllowed: List<String> = emptyList(),
    val busyId: String? = null,
    val error: String? = null
)

/**
 * Owns the block lists (bundled assets, downloaded lists, imported files) and the
 * custom allow/block rules, and publishes an immutable [RuleMatcher] snapshot that
 * the VPN service reads on every query.
 */
object RulesRepository {

    private const val TAG = "RulesRepository"
    private const val LISTS_FILE = "lists.json"
    private const val MAX_LIST_BYTES = 32L * 1024 * 1024
    private const val MAX_ENTRIES_PER_LIST = 500_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var initialized = false

    private lateinit var appContext: Context
    private lateinit var listsDir: File

    @Volatile
    var matcher: RuleMatcher = RuleMatcher.EMPTY
        private set

    private val _state = MutableStateFlow(RulesState())
    val state: StateFlow<RulesState> = _state.asStateFlow()

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            listsDir = File(appContext.filesDir, "lists").apply { mkdirs() }
            initialized = true
        }
        rebuild()
    }

    // ---------------------------------------------------------------- rebuilding

    fun rebuild() {
        if (!initialized) return
        scope.launch { rebuildNow() }
    }

    suspend fun rebuildNow(): Unit = withContext(Dispatchers.IO) {
        if (!initialized) return@withContext

        val lists = readLists()
        val blocked = HashSet<String>(1 shl 14)
        val allowed = HashSet<String>(64)
        val refreshed = ArrayList<BlockList>(lists.size)
        var problem: String? = null

        for (list in lists) {
            if (!list.enabled) {
                refreshed.add(list)
                continue
            }
            var unique = 0
            try {
                unique = loadEntries(list) { blocked.add(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Could not load list ${list.id}", e)
                problem = "Could not read “${list.name}”"
            }
            refreshed.add(list.copy(ruleCount = if (unique > 0) unique else list.ruleCount))
        }

        val customAllowed = Prefs.customAllowed.sorted()
        val customBlocked = Prefs.customBlocked.sorted()
        for (domain in customAllowed) allowed.add(domain)
        for (domain in customBlocked) blocked.add(domain)

        val snapshot = RuleMatcher(blocked, allowed)
        matcher = snapshot
        writeLists(refreshed)

        _state.value = RulesState(
            ready = true,
            activeRules = snapshot.blockCount,
            lists = refreshed,
            customBlocked = customBlocked,
            customAllowed = customAllowed,
            busyId = null,
            error = problem
        )
    }

    private fun loadEntries(list: BlockList, sink: (String) -> Boolean): Int {
        var count = 0
        openStream(list).use { raw ->
            BufferedReader(InputStreamReader(raw, Charsets.UTF_8), 64 * 1024).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val domain = Domains.parseLine(line) ?: continue
                    if (sink(domain)) count++
                    if (count >= MAX_ENTRIES_PER_LIST) break
                }
            }
        }
        return count
    }

    private fun openStream(list: BlockList): InputStream = when (list.kind) {
        ListKind.BUNDLED -> appContext.assets.open(list.location)
        ListKind.REMOTE, ListKind.IMPORTED -> File(list.location).inputStream()
    }

    // -------------------------------------------------------------- list storage

    private fun seedLists(): List<BlockList> = listOf(
        BlockList(
            id = "bundled_ads",
            name = "Ad networks",
            kind = ListKind.BUNDLED,
            location = "blocklists/ads.txt",
            enabled = true,
            description = "Mobile ad SDKs, mediation networks and display ad servers."
        ),
        BlockList(
            id = "bundled_trackers",
            name = "Trackers & telemetry",
            kind = ListKind.BUNDLED,
            location = "blocklists/trackers.txt",
            enabled = true,
            description = "Analytics, attribution and profiling endpoints."
        ),
        BlockList(
            id = "bundled_strict",
            name = "Strict (may break apps)",
            kind = ListKind.BUNDLED,
            location = "blocklists/strict.txt",
            enabled = false,
            description = "Aggressive rules. Some apps, logins or messengers may stop working."
        )
    )

    private fun listsFile(): File = File(appContext.filesDir, LISTS_FILE)

    private fun readLists(): List<BlockList> {
        val file = listsFile()
        if (!file.exists()) {
            val seeds = seedLists()
            writeLists(seeds)
            return seeds
        }
        return try {
            val array = JSONArray(file.readText())
            val result = ArrayList<BlockList>(array.length())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                result.add(
                    BlockList(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        kind = ListKind.values().firstOrNull { it.storageKey == o.optString("kind") }
                            ?: ListKind.BUNDLED,
                        location = o.getString("location"),
                        enabled = o.optBoolean("enabled", true),
                        ruleCount = o.optInt("ruleCount", 0),
                        updatedAt = o.optLong("updatedAt", 0L),
                        description = o.optString("description", "")
                    )
                )
            }
            if (result.isEmpty()) seedLists() else result
        } catch (e: Exception) {
            Log.w(TAG, "lists.json is unreadable, restoring defaults", e)
            seedLists()
        }
    }

    private fun writeLists(lists: List<BlockList>) {
        try {
            val array = JSONArray()
            for (list in lists) {
                val o = JSONObject()
                o.put("id", list.id)
                o.put("name", list.name)
                o.put("kind", list.kind.storageKey)
                o.put("location", list.location)
                o.put("enabled", list.enabled)
                o.put("ruleCount", list.ruleCount)
                o.put("updatedAt", list.updatedAt)
                o.put("description", list.description)
                array.put(o)
            }
            listsFile().writeText(array.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Could not persist list metadata", e)
        }
    }

    // ----------------------------------------------------------------- mutations

    fun setListEnabled(id: String, enabled: Boolean) {
        scope.launch {
            val lists = readLists().map { if (it.id == id) it.copy(enabled = enabled) else it }
            writeLists(lists)
            rebuildNow()
        }
    }

    fun deleteList(id: String) {
        scope.launch {
            val lists = readLists()
            val target = lists.firstOrNull { it.id == id } ?: return@launch
            if (target.kind != ListKind.BUNDLED) {
                runCatching { File(target.location).delete() }
            }
            writeLists(lists.filterNot { it.id == id })
            rebuildNow()
        }
    }

    suspend fun addRemoteList(rawUrl: String): Result<BlockList> = withContext(Dispatchers.IO) {
        val url = normalizeUrl(rawUrl)
            ?: return@withContext Result.failure(IllegalArgumentException("Enter a http(s) list URL"))

        val id = "remote_" + Integer.toHexString(url.hashCode()) + "_" +
            System.currentTimeMillis().toString(36)
        val file = File(listsDir, "$id.txt")

        _state.value = _state.value.copy(busyId = id, error = null)
        try {
            val count = downloadToList(url, file)
            if (count <= 0) {
                file.delete()
                throw IOException("No usable domains were found at that address")
            }
            val list = BlockList(
                id = id,
                name = displayNameFor(url),
                kind = ListKind.REMOTE,
                location = file.absolutePath,
                enabled = true,
                ruleCount = count,
                updatedAt = System.currentTimeMillis(),
                description = url
            )
            writeLists(readLists() + list)
            rebuildNow()
            Result.success(list)
        } catch (e: Exception) {
            Log.w(TAG, "Remote list failed: $url", e)
            runCatching { file.delete() }
            _state.value = _state.value.copy(busyId = null, error = e.message ?: "Download failed")
            Result.failure(e)
        }
    }

    suspend fun refreshList(id: String): Result<BlockList> = withContext(Dispatchers.IO) {
        val list = readLists().firstOrNull { it.id == id }
            ?: return@withContext Result.failure(IllegalArgumentException("List not found"))
        if (list.kind != ListKind.REMOTE) {
            return@withContext Result.failure(IllegalArgumentException("Only downloaded lists can be refreshed"))
        }

        _state.value = _state.value.copy(busyId = id, error = null)
        try {
            val file = File(list.location)
            val count = downloadToList(list.description.ifBlank { list.location }, file)
            if (count <= 0) throw IOException("The list came back empty")
            val updated = list.copy(ruleCount = count, updatedAt = System.currentTimeMillis())
            writeLists(readLists().map { if (it.id == id) updated else it })
            rebuildNow()
            Result.success(updated)
        } catch (e: Exception) {
            Log.w(TAG, "Refresh failed for ${list.id}", e)
            _state.value = _state.value.copy(busyId = null, error = e.message ?: "Refresh failed")
            Result.failure(e)
        }
    }

    suspend fun importFromUri(uri: Uri): Result<BlockList> = withContext(Dispatchers.IO) {
        val id = "import_" + System.currentTimeMillis().toString(36)
        val file = File(listsDir, "$id.txt")
        _state.value = _state.value.copy(busyId = id, error = null)
        try {
            val resolver = appContext.contentResolver
            val count = resolver.openInputStream(uri)?.use { input ->
                writeNormalized(input, file)
            } ?: throw IOException("Could not open that file")

            if (count <= 0) {
                file.delete()
                throw IOException("No usable domains were found in that file")
            }

            val name = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.takeIf { it.isNotBlank() }
                ?: "Imported list"

            val list = BlockList(
                id = id,
                name = name,
                kind = ListKind.IMPORTED,
                location = file.absolutePath,
                enabled = true,
                ruleCount = count,
                updatedAt = System.currentTimeMillis(),
                description = "Imported from device storage"
            )
            writeLists(readLists() + list)
            rebuildNow()
            Result.success(list)
        } catch (e: Exception) {
            Log.w(TAG, "Import failed", e)
            runCatching { file.delete() }
            _state.value = _state.value.copy(busyId = null, error = e.message ?: "Import failed")
            Result.failure(e)
        }
    }

    // --------------------------------------------------------------- custom rules

    suspend fun addCustomBlocked(raw: String): Result<String> =
        addCustom(raw, Prefs.customBlocked) { Prefs.customBlocked = it }

    suspend fun addCustomAllowed(raw: String): Result<String> =
        addCustom(raw, Prefs.customAllowed) { Prefs.customAllowed = it }

    private suspend fun addCustom(
        raw: String,
        existing: Set<String>,
        save: (Set<String>) -> Unit
    ): Result<String> = withContext(Dispatchers.Default) {
        val domain = Domains.normalize(raw)
            ?: return@withContext Result.failure(IllegalArgumentException("Enter a domain like ads.example.com"))
        if (existing.contains(domain)) {
            return@withContext Result.success(domain)
        }
        save(existing + domain)
        rebuildNow()
        Result.success(domain)
    }

    suspend fun removeCustom(domain: String, blockedRule: Boolean) = withContext(Dispatchers.Default) {
        if (blockedRule) {
            Prefs.customBlocked = Prefs.customBlocked - domain
        } else {
            Prefs.customAllowed = Prefs.customAllowed - domain
        }
        rebuildNow()
    }

    // ------------------------------------------------------------------ download

    private fun normalizeUrl(raw: String): String? {
        var value = raw.trim()
        if (value.isEmpty()) return null
        if (!value.startsWith("http://", ignoreCase = true) &&
            !value.startsWith("https://", ignoreCase = true)
        ) {
            value = "https://$value"
        }
        return try {
            val url = URL(value)
            if (url.protocol != "http" && url.protocol != "https") null
            else if (url.host.isNullOrBlank()) null
            else url.toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun displayNameFor(url: String): String {
        val host = runCatching { URL(url).host }.getOrNull() ?: url
        val tail = url.substringAfterLast('/').take(28)
        return if (tail.isBlank()) host else "$host/$tail"
    }

    private fun downloadToList(url: String, target: File): Int {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AdShield/1.0 (Android)")
            setRequestProperty("Accept", "text/plain, */*")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Server returned HTTP $code")
            val count = connection.inputStream.use { input -> writeNormalized(input, target) }
            return count
        } finally {
            connection.disconnect()
        }
    }

    /** Parses a hosts file / domain list, writing unique normalised domains. */
    private fun writeNormalized(input: InputStream, target: File): Int {
        var count = 0
        var bytes = 0L
        val seen = HashSet<String>(4096)
        BufferedReader(InputStreamReader(input, Charsets.UTF_8), 64 * 1024).use { reader ->
            target.bufferedWriter(Charsets.UTF_8, 64 * 1024).use { writer ->
                while (true) {
                    val line = reader.readLine() ?: break
                    bytes += line.length + 1L
                    if (bytes > MAX_LIST_BYTES) break
                    val domain = Domains.parseLine(line) ?: continue
                    if (!seen.add(domain)) continue
                    writer.write(domain)
                    writer.newLine()
                    count++
                    if (count >= MAX_ENTRIES_PER_LIST) break
                }
            }
        }
        return count
    }

    // -------------------------------------------------------------------- presets

    data class Preset(val title: String, val url: String, val note: String)

    val presets: List<Preset> = listOf(
        Preset(
            "StevenBlack unified hosts",
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            "~80k entry classic hosts list"
        ),
        Preset(
            "AdAway default",
            "https://adaway.org/hosts.txt",
            "Ad/tracker hosts maintained by AdAway"
        ),
        Preset(
            "OISD small",
            "https://small.oisd.nl/domainswild",
            "Well curated domain list, low breakage"
        ),
        Preset(
            "1Hosts Lite",
            "https://raw.githubusercontent.com/badmojr/1Hosts/master/Lite/domains.txt",
            "Balanced ads + trackers"
        )
    )
}
