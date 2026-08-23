package com.magician.worldedit.client.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

/**
 * Persistent exact-response cache for WEMC chat.
 *
 * Each entry is keyed by a SHA-256 hash of the exact (provider, model, system
 * prompt, compact player state, request) tuple. If the player issues the same
 * request from the same state, the cached AI response is returned without an
 * HTTP call.
 *
 * The cache is bounded (LRU, [MAX_ENTRIES]) and persisted to disk under the
 * Fabric config directory so it survives game restarts. Players can clear,
 * disable, or inspect the cache with `/wemc chat cache`.
 *
 * Only [AiChatResult.Success] responses are written; failures are never
 * cached, and lookup only returns entries whose TTL has not elapsed.
 */
object AiResponseCache {

    /** Maximum number of entries kept on disk. Oldest is evicted on overflow. */
    const val MAX_ENTRIES = 100

    /** Default time-to-live for cached responses. */
    const val DEFAULT_TTL_MS: Long = 24L * 60L * 60L * 1000L

    /** Cache file lives next to other WEMC config under the Fabric config dir. */
    private val cachePath: Path
        get() = runCatching {
            FabricLoader.getInstance().configDir.resolve("wemc-response-cache.json")
        }.getOrElse {
            // Unit tests do not boot FabricLoader. Keep tests isolated from
            // the user's real cache while preserving production persistence.
            Paths.get(System.getProperty("java.io.tmpdir"), "wemc-response-cache-test.json")
        }

    private val gson = GsonBuilder().setPrettyPrinting().create()

    @Volatile
    private var enabled: Boolean = true

    @Volatile
    private var ttlMs: Long = DEFAULT_TTL_MS

    /** In-memory cache, lazily loaded from disk and persisted on every write. */
    private val entries: MutableList<Entry> = mutableListOf()

    private var loaded = false

    data class Entry(
        val requestHash: String,
        val provider: String,
        val model: String,
        val systemPromptHash: String,
        val requestText: String,
        val responseText: String,
        val createdAt: Long,
        val hits: Int = 0,
    )

    /**
     * Look up a cached response. The hash covers provider + model + system
     * prompt + request text. A hit requires both the hash to match AND the
     * stored entry to still be within its TTL window.
     */
    fun lookup(
        provider: AiProvider,
        model: String,
        systemPrompt: String?,
        request: String,
        now: Long = System.currentTimeMillis(),
    ): Entry? {
        if (!enabled) return null
        ensureLoaded()
        val (reqHash, sysHash) = hashOf(provider, model, systemPrompt, request)
        val match = entries.firstOrNull {
            it.requestHash == reqHash &&
                it.systemPromptHash == sysHash &&
                it.provider == provider.name &&
                it.model == model &&
                (now - it.createdAt) <= ttlMs
        } ?: return null
        // Bump hits; persist asynchronously.
        val updated = match.copy(hits = match.hits + 1)
        val idx = entries.indexOf(match)
        entries[idx] = updated
        persistAsync()
        return updated
    }

    /**
     * Record a successful response. Silently does nothing when disabled.
     * On overflow, evicts the least-recently-used entry (oldest createdAt).
     */
    fun store(
        provider: AiProvider,
        model: String,
        systemPrompt: String?,
        request: String,
        responseText: String,
        now: Long = System.currentTimeMillis(),
    ) {
        if (!enabled) return
        ensureLoaded()
        if (responseText.isBlank()) return
        val (reqHash, sysHash) = hashOf(provider, model, systemPrompt, request)
        // De-duplicate exact-match entries (replace oldest).
        val existingIdx = entries.indexOfFirst {
            it.requestHash == reqHash && it.systemPromptHash == sysHash
        }
        if (existingIdx >= 0) {
            entries[existingIdx] = entries[existingIdx].copy(
                responseText = responseText,
                createdAt = now,
                hits = entries[existingIdx].hits,
            )
            persistAsync()
            return
        }
        entries.add(
            Entry(
                requestHash = reqHash,
                provider = provider.name,
                model = model,
                systemPromptHash = sysHash,
                requestText = request,
                responseText = responseText,
                createdAt = now,
            )
        )
        // Evict LRU if over capacity.
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
        persistAsync()
    }

    /** Drop every entry from memory and disk immediately. */
    fun clear() {
        ensureLoaded()
        synchronized(this) { entries.clear() }
        persistNow()
    }

    /** Read-only status line for `/wemc chat cache status`. */
    fun statusLine(): String {
        ensureLoaded()
        val totalHits = entries.sumOf { it.hits }
        return buildString {
            append("Cache ${if (enabled) "on" else "off"}")
            append(" | entries=${entries.size}/$MAX_ENTRIES")
            append(" | ttl=${ttlMs / 60_000}m")
            append(" | total hits=$totalHits")
        }
    }

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun isEnabled(): Boolean = enabled

    fun setTtlMs(value: Long) {
        ttlMs = value.coerceAtLeast(0L)
    }

    fun getTtlMs(): Long = ttlMs

    /** Number of entries currently in the cache (after load). */
    fun size(): Int {
        ensureLoaded()
        return entries.size
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /**
     * Compute hashes for the cache key. We hash the system prompt text
     * separately so the JSON serializer can stay compact (it only stores
     * the hash, not the multi-kilobyte prompt body).
     */
    private fun hashOf(
        provider: AiProvider,
        model: String,
        systemPrompt: String?,
        request: String,
    ): Pair<String, String> {
        val sha = MessageDigest.getInstance("SHA-256")
        sha.update(provider.name.toByteArray(StandardCharsets.UTF_8))
        sha.update(0)
        sha.update(model.toByteArray(StandardCharsets.UTF_8))
        sha.update(0)
        sha.update((systemPrompt ?: "").toByteArray(StandardCharsets.UTF_8))
        val systemHash = bytesToHex(sha.digest())
        sha.reset()
        sha.update(request.toByteArray(StandardCharsets.UTF_8))
        val requestHash = bytesToHex(sha.digest())
        return requestHash to systemHash
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            entries.clear()
            val path = cachePath
            if (Files.exists(path)) {
                runCatching {
                    Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
                        val root = JsonParser.parseReader(reader).asJsonObject
                        val arr = root.getAsJsonArray("entries") ?: JsonArray()
                        for (e in arr) {
                            val obj = e.asJsonObject
                            entries.add(
                                Entry(
                                    requestHash = obj.get("requestHash").asString,
                                    provider = obj.get("provider").asString,
                                    model = obj.get("model").asString,
                                    systemPromptHash = obj.get("systemPromptHash").asString,
                                    requestText = obj.get("requestText")?.asString ?: "",
                                    responseText = obj.get("responseText").asString,
                                    createdAt = obj.get("createdAt").asLong,
                                    hits = obj.get("hits")?.asInt ?: 0,
                                )
                            )
                        }
                    }
                }
            }
            loaded = true
        }
    }

    private fun persistAsync() {
        // Fire-and-forget write. Avoids blocking the calling thread.
        Thread(::persistNow, "wemc-cache-write").apply { isDaemon = true }.start()
    }

    private fun persistNow() {
        val snapshot = synchronized(this) { entries.toList() }
        val root = JsonObject().apply {
            add("version", com.google.gson.JsonPrimitive(1))
            add("entries", JsonArray().apply {
                snapshot.forEach { entry ->
                    add(JsonObject().apply {
                        addProperty("requestHash", entry.requestHash)
                        addProperty("provider", entry.provider)
                        addProperty("model", entry.model)
                        addProperty("systemPromptHash", entry.systemPromptHash)
                        addProperty("requestText", entry.requestText)
                        addProperty("responseText", entry.responseText)
                        addProperty("createdAt", entry.createdAt)
                        addProperty("hits", entry.hits)
                    })
                }
            })
        }
        runCatching {
            val path = cachePath
            Files.createDirectories(path.parent)
            Files.newBufferedWriter(path, StandardCharsets.UTF_8).use { writer ->
                writer.write(gson.toJson(root))
            }
        }
    }
}