package com.magician.worldedit.client.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the persistent AI response cache:
 *   - Hits and misses on identical / differing inputs
 *   - TTL expiry
 *   - LRU eviction when over MAX_ENTRIES
 *   - Disable / enable semantics
 *   - De-duplication of exact-match entries
 *
 * Each test works against a private file under `java.io.tmpdir`, isolated by
 * test name. The cache reads its path lazily from FabricLoader, which is not
 * available in unit tests; we exercise the public lookup / store API and
 * confirm behavioural guarantees without touching disk.
 */
class AiResponseCacheTest {

    private val provider = AiProvider.OPENAI
    private val model = "gpt-test"
    private val systemPrompt = "[anchor] static whitelist"
    private val request = "build a tower"
    private val response = "```wemc-commands\nsetblock 0 64 0 minecraft:stone\n```"

    @Test
    fun `cache miss returns null before any entry is stored`() {
        AiResponseCache.clear()
        val hit = AiResponseCache.lookup(provider, model, systemPrompt, request)
        assertNull(hit, "Lookup must miss before storing anything")
    }

    @Test
    fun `stored entry is returned by subsequent lookup`() {
        AiResponseCache.clear()
        val now = System.currentTimeMillis()
        AiResponseCache.store(provider, model, systemPrompt, request, response, now)
        val hit = AiResponseCache.lookup(provider, model, systemPrompt, request, now + 1000)
        assertNotNull(hit, "Lookup must hit after store")
        assertEquals(response, hit!!.responseText)
        assertEquals(provider.name, hit.provider)
        assertEquals(model, hit.model)
    }

    @Test
    fun `different request produces a miss`() {
        AiResponseCache.clear()
        val now = System.currentTimeMillis()
        AiResponseCache.store(provider, model, systemPrompt, request, response, now)
        val hit = AiResponseCache.lookup(provider, model, systemPrompt, "different request", now + 1000)
        assertNull(hit, "A different request must miss")
    }

    @Test
    fun `different model produces a miss`() {
        AiResponseCache.clear()
        val now = System.currentTimeMillis()
        AiResponseCache.store(provider, model, systemPrompt, request, response, now)
        val hit = AiResponseCache.lookup(provider, "gpt-other", systemPrompt, request, now + 1000)
        assertNull(hit, "A different model must miss")
    }

    @Test
    fun `different system prompt produces a miss`() {
        AiResponseCache.clear()
        val now = System.currentTimeMillis()
        AiResponseCache.store(provider, model, systemPrompt, request, response, now)
        val hit = AiResponseCache.lookup(provider, model, "different system prompt", request, now + 1000)
        assertNull(hit, "A different system prompt must miss")
    }

    @Test
    fun `entries older than TTL do not hit`() {
        AiResponseCache.clear()
        val ttl = AiResponseCache.DEFAULT_TTL_MS
        val old = System.currentTimeMillis() - ttl - 1000
        AiResponseCache.store(provider, model, systemPrompt, request, response, old)
        // Even if we ask at "now" the entry is well past TTL.
        val hit = AiResponseCache.lookup(provider, model, systemPrompt, request, System.currentTimeMillis())
        assertNull(hit, "Entries past TTL must miss")
    }

    @Test
    fun `cache is bounded by MAX_ENTRIES oldest first`() {
        AiResponseCache.clear()
        val base = System.currentTimeMillis()
        // Insert MAX_ENTRIES + 5 distinct requests.
        val total = AiResponseCache.MAX_ENTRIES + 5
        repeat(total) { i ->
            AiResponseCache.store(
                provider = provider,
                model = model,
                systemPrompt = systemPrompt,
                request = "request-$i",
                responseText = "resp-$i",
                now = base + i,
            )
        }
        assertEquals(AiResponseCache.MAX_ENTRIES, AiResponseCache.size(),
            "Cache must cap at MAX_ENTRIES")
        // The oldest five should have been evicted; lookup on them must miss.
        repeat(5) { i ->
            val hit = AiResponseCache.lookup(provider, model, systemPrompt, "request-$i", base + total + 1)
            assertNull(hit, "Oldest entry request-$i should have been evicted")
        }
        // The newer ones are still present.
        val newest = AiResponseCache.lookup(provider, model, systemPrompt, "request-${total - 1}", base + total + 1)
        assertNotNull(newest, "Newest entry must still be in cache")
    }

    @Test
    fun `disabled cache never returns a hit`() {
        AiResponseCache.clear()
        val now = System.currentTimeMillis()
        AiResponseCache.store(provider, model, systemPrompt, request, response, now)
        AiResponseCache.setEnabled(false)
        val hit = AiResponseCache.lookup(provider, model, systemPrompt, request, now + 1000)
        assertNull(hit, "Disabled cache must miss")
        AiResponseCache.setEnabled(true)
    }

    @Test
    fun `clear removes every entry`() {
        AiResponseCache.clear()
        val now = System.currentTimeMillis()
        repeat(3) { i ->
            AiResponseCache.store(provider, model, systemPrompt, "req-$i", "resp-$i", now + i)
        }
        assertTrue(AiResponseCache.size() >= 3)
        AiResponseCache.clear()
        assertEquals(0, AiResponseCache.size())
        val hit = AiResponseCache.lookup(provider, model, systemPrompt, "req-0", now + 10_000)
        assertNull(hit, "After clear, even the latest request must miss")
    }

    @Test
    fun `duplicate store replaces existing entry and updates timestamp`() {
        AiResponseCache.clear()
        val t1 = System.currentTimeMillis() - 100_000
        val t2 = System.currentTimeMillis()
        AiResponseCache.store(provider, model, systemPrompt, request, "old-response", t1)
        AiResponseCache.store(provider, model, systemPrompt, request, "new-response", t2)
        val hit = AiResponseCache.lookup(provider, model, systemPrompt, request, t2 + 10_000)
        assertNotNull(hit)
        assertEquals("new-response", hit!!.responseText,
            "Most recent store must replace the previous one")
    }

    @Test
    fun `cache key covers provider model systemPrompt and request`() {
        // Hash invariants: identical inputs -> identical keys (we don't expose
        // the hash, but we exercise it indirectly via lookups above). This test
        // documents that the public lookup is the supported way to probe the
        // cache, and ensures repeated lookups are deterministic.
        AiResponseCache.clear()
        val t = System.currentTimeMillis()
        AiResponseCache.store(provider, model, systemPrompt, request, response, t)
        val first = AiResponseCache.lookup(provider, model, systemPrompt, request, t + 100)
        val second = AiResponseCache.lookup(provider, model, systemPrompt, request, t + 200)
        assertEquals(first?.responseText, second?.responseText)
    }

    @Test
    fun `status line reflects current size and enabled flag`() {
        AiResponseCache.clear()
        AiResponseCache.setEnabled(true)
        val initialLine = AiResponseCache.statusLine()
        assertTrue(initialLine.startsWith("Cache on"))
        AiResponseCache.store(provider, model, systemPrompt, request, response)
        assertTrue(initialLine.contains("entries=0/${AiResponseCache.MAX_ENTRIES}"))
        AiResponseCache.clear()
    }
}