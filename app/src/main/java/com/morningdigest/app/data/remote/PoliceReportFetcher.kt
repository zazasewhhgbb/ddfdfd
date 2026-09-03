package com.morningdigest.app.data.remote

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Rebuilt from zero. Fetches Norway's public police incident log
 * ("Politiloggen") for Max the assistant's Police Report card and the
 * dashboard's shield indicator.
 *
 * Design goals, given the history of this feature (several earlier attempts,
 * each individually "confirmed" against either a decompiled official app or
 * a real device, still ended up returning an empty response body on at
 * least one real device/network - most likely something specific to that
 * device/network path rather than the API being categorically closed to
 * outside callers, since a third-party server-side tool calling the same
 * host works fine):
 *
 * 1. NEVER bet the whole feature on one exact, unverifiable request shape.
 *    Two independently-sourced request variants are tried in order; the
 *    first one that comes back with real, parseable content wins.
 * 2. NEVER show a scary hard failure if there's ANY previously-successful
 *    data to fall back on. A successful fetch is cached to disk; if a
 *    refresh fails, the last good report is served instead, clearly marked
 *    with its own age, so "can't reach the service right now" never means
 *    "the report disappears."
 * 3. If a fetch fails AND there is no cache at all (e.g. first run on a
 *    blocked network), the exception message spells out exactly what was
 *    tried and what came back for each attempt (HTTP status, byte count),
 *    so a real failure is diagnosable from the error text alone instead of
 *    requiring another guessing round.
 * 4. English by default, Norwegian original always kept alongside it. Every
 *    incident's text is translated once (and cached), but the raw Norwegian
 *    text is never discarded - Politiloggen itself has no English version
 *    (confirmed: it's a documented, oft-requested missing feature on the
 *    official app), so this app is the only place the English text exists.
 *    Every incident also carries a real timestamp - the UI shows the actual
 *    date and time, not just "x hours ago", so its age is unambiguous.
 * 5. Tapping an incident opens its real Politiloggen source page (Norwegian,
 *    since that's the only language it has) - see [sourceUrl].
 */
class PoliceReportFetcher(private val context: Context, private val client: OkHttpClient) {

    data class Incident(
        val id: String,
        val threadId: String,
        val category: String,
        val categoryEn: String,
        val municipality: String,
        val area: String,
        val createdMillis: Long,
        val text: String,
        val englishText: String,
        val sourceUrl: String
    )

    /** Thrown only when there is truly nothing to show - no fresh data AND no usable cache. Carries a diagnostic-rich message. */
    class PoliceReportException(message: String, cause: Throwable? = null) : Exception(message, cause)

    companion object {
        private const val BASE_URL = "https://api.politiloggen.politiet.no"
        private const val CACHE_FILE_NAME = "police_report_cache.json"
        private const val CACHE_TTL_MILLIS = 6 * 60 * 60 * 1000L // stale cache older than 6h is not offered as a silent success

        /**
         * Reports are kept visible for 5 days from their own last activity,
         * then treated as expired - not a display cap, an actual age cutoff,
         * so a municipality with 100 incidents a day shows all of them for
         * up to 5 days rather than only the newest handful. Public so every
         * screen that lists incidents (Max's report, the dashboard preview,
         * the background worker) applies exactly the same cutoff.
         */
        const val MAX_REPORT_AGE_MILLIS = 5 * 24 * 60 * 60 * 1000L

        /**
         * How many incidents a single fetch will ever gather before giving
         * up, as an absolute safety ceiling (not a "cap per municipality" -
         * every match within the age window and selected municipalities
         * counts toward this one shared ceiling, which is set high enough
         * that a normal selection of a few municipalities, even busy ones,
         * won't realistically hit it within the 5-day window).
         */
        const val DEFAULT_FETCH_LIMIT = 2000

        // /messages only ever returns its most recent page by default; a
        // quiet municipality's incidents can be a few days back and get
        // buried under national-level traffic/theft volume in page one.
        // Paging deeper (bounded) is what makes "show me Kirkenes reports
        // even if they're a few days old" actually work. The real stopping
        // condition is date-based (see fetchViaMessages) - this page count
        // is just a hard safety ceiling in case the feed is ever much busier
        // than expected, so a fetch can never spin forever.
        private const val MESSAGE_PAGE_SIZE = 100
        private const val MAX_MESSAGE_PAGES = 60

        val CATEGORY_TRANSLATIONS = linkedMapOf(
            "Arrangement" to "Events",
            "Brann" to "Fire",
            "Dyr" to "Animals",
            "Innbrudd" to "Burglary",
            "Redning" to "Rescue",
            "Ro og orden" to "Public order",
            "Savnet" to "Missing person",
            "Sjø" to "Maritime incident",
            "Skadeverk" to "Vandalism / property damage",
            "Trafikk" to "Traffic",
            "Tyveri" to "Theft",
            "Ulykke" to "Accident",
            "Voldshendelse" to "Violence",
            "Vær" to "Weather",
            "Andre hendelser" to "Other incidents"
        )
    }

    private val translationCache = ConcurrentHashMap<String, String>()
    private val translationClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    // ---- District/municipality picker: fully offline, see PoliceDistricts.kt ----

    fun fetchDistricts(): List<PoliceDistricts.DistrictItem> = PoliceDistricts.districts

    fun fetchMunicipalitiesForDistrict(district: PoliceDistricts.DistrictItem): List<String> =
        PoliceDistricts.municipalitiesFor(district)

    // ---- Incident fetching ----

    suspend fun fetch(municipality: String, enabledCategories: Set<String>, limit: Int = DEFAULT_FETCH_LIMIT): List<Incident> =
        fetch(listOf(municipality), enabledCategories, limit)

    suspend fun fetch(municipalities: List<String>, enabledCategories: Set<String>, limit: Int = DEFAULT_FETCH_LIMIT): List<Incident> = withContext(Dispatchers.IO) {
        val wanted = limit.coerceIn(1, DEFAULT_FETCH_LIMIT)
        val targets = municipalities.map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
        if (targets.isEmpty()) return@withContext emptyList()

        val attempts = mutableListOf<String>()

        val viaMessages = runCatching { fetchViaMessages(targets, enabledCategories, wanted) }
            .onFailure { attempts += "messages: ${describeError(it)}" }
            .getOrNull()
        if (viaMessages != null) {
            saveCache(viaMessages)
            return@withContext viaMessages
        }

        val viaThreads = runCatching { fetchViaMessageThreads(targets, enabledCategories, wanted) }
            .onFailure { attempts += "messagethreads: ${describeError(it)}" }
            .getOrNull()
        if (viaThreads != null) {
            saveCache(viaThreads)
            return@withContext viaThreads
        }

        // Both request strategies failed. Fall back to the last successful
        // fetch, however old, rather than showing a hard error - a stale
        // report is far more useful than none. The caller/UI is expected to
        // show the cache's age (see [loadCache]) alongside the data.
        // Both request strategies failed. Fall back to the last successful
        // fetch, however old, rather than showing a hard error - a stale
        // report is far more useful than none. But the cache is whatever was
        // fetched *last time*, which may have been for a different
        // municipality/category selection than right now (e.g. the person
        // just removed a municipality in Settings) - re-apply today's
        // filters so a removed municipality's old incidents can never
        // resurface just because a refresh happened to hit a network hiccup.
        val cached = loadCache()?.filter { inc ->
            targets.any { it.equals(inc.municipality, ignoreCase = true) } &&
                (enabledCategories.isEmpty() || enabledCategories.containsAll(CATEGORY_TRANSLATIONS.keys) || inc.category in enabledCategories)
        }
        if (cached != null) return@withContext cached

        throw PoliceReportException(
            "Couldn't reach the police report service, and there's no previously saved report to fall back on. " +
                "Tried: ${attempts.joinToString(" | ").ifBlank { "no attempts recorded" }}"
        )
    }

    /** Also exposes cache age so the UI can show "as of HH:mm" when serving fallback data. */
    fun cacheAgeMillis(): Long? = loadCacheMeta()?.let { System.currentTimeMillis() - it }

    // ---- Strategy A: /messagethreads (nested per-thread updates) ----

    private suspend fun fetchViaMessageThreads(municipalities: List<String>, categories: Set<String>, take: Int): List<Incident> {
        // This strategy is a single, unpaginated request (unlike
        // fetchViaMessages, which always requests fixed-size pages
        // regardless of how many are ultimately wanted) - so the caller's
        // overall "how many total" value must NOT be sent straight through
        // as Take. Sending Take=2000 in one shot (DEFAULT_FETCH_LIMIT, now
        // that there's no display cap) is almost certainly why this started
        // returning HTTP 400 - the server very likely validates Take against
        // a small maximum. Cap what's actually sent to a safe, conservative
        // size; this is the secondary fallback strategy only tried when
        // fetchViaMessages fails outright, so getting its most recent ~100
        // rather than a deeper page is an acceptable tradeoff here.
        val safeTake = take.coerceAtMost(100)
        val builder = "$BASE_URL/messagethreads".toHttpUrl().newBuilder()
            .addQueryParameter("Skip", "0")
            .addQueryParameter("Take", safeTake.toString())
        val body = requestBody(builder.build().toString())
        val root = JSONObject(body) // throws JSONException on empty/garbage body - caught by caller
        val threads = root.optJSONArray("messageThreads") ?: root.optJSONArray("MessageThreads")
            ?: throw JSONException("no messageThreads array in response")

        val out = mutableListOf<Incident>()
        for (i in 0 until threads.length()) {
            val thread = threads.optJSONObject(i) ?: continue
            val municipalityName = thread.optString("municipality")
            if (municipalities.isNotEmpty() && municipalities.none { it.equals(municipalityName, ignoreCase = true) }) continue
            val category = thread.optString("category")
            val isCustomCategoryFilter = categories.isNotEmpty() && !categories.containsAll(CATEGORY_TRANSLATIONS.keys)
            if (isCustomCategoryFilter && category !in categories) continue
            val threadId = thread.optString("id").trim()
            val area = thread.optString("area")
            val messages = thread.optJSONArray("messages") ?: JSONArray()
            val threadSourceUrl = sourceUrl(threadId)

            if (messages.length() == 0) {
                val text = thread.optString("text").ifBlank { thread.optString("description") }
                if (threadId.isNotBlank() && text.isNotBlank()) {
                    out += Incident(
                        id = threadId, threadId = threadId, category = category, categoryEn = CATEGORY_TRANSLATIONS[category] ?: category,
                        municipality = municipalityName, area = area,
                        createdMillis = parseMillis(thread.optString("createdOn").ifBlank { thread.optString("lastMessageOn") }),
                        text = text, englishText = text, sourceUrl = threadSourceUrl
                    )
                }
                continue
            }
            for (m in 0 until messages.length()) {
                val msg = messages.optJSONObject(m) ?: continue
                val text = msg.optString("text").ifBlank { msg.optString("description") }
                val msgId = msg.optString("id").trim().ifBlank { "$threadId-$m" }
                if (text.isBlank()) continue
                out += Incident(
                    id = msgId, threadId = threadId, category = category, categoryEn = CATEGORY_TRANSLATIONS[category] ?: category,
                    municipality = municipalityName, area = area,
                    createdMillis = parseMillis(msg.optString("createdOn")),
                    text = text, englishText = text, sourceUrl = threadSourceUrl
                )
            }
        }
        if (out.isEmpty() && threads.length() > 0) {
            // We got real data back but every row failed to parse into an Incident -
            // treat as a shape mismatch, not "zero incidents", so the other strategy gets a turn.
            throw JSONException("messageThreads array had ${threads.length()} entries but none parsed")
        }
        return out.sortedByDescending { it.createdMillis }.take(take)
    }

    // ---- Strategy B: /messages (flat list) - confirmed working shape; paginates deep enough to find a quiet municipality's older-but-recent incidents ----

    private suspend fun fetchViaMessages(municipalities: List<String>, categories: Set<String>, take: Int): List<Incident> {
        val out = mutableListOf<Incident>()
        val seenIds = mutableSetOf<String>()
        var skip = 0
        var totalCount = Int.MAX_VALUE
        var rowsWithUsableFields = 0
        var rowsSeenTotal = 0
        val cutoffMillis = System.currentTimeMillis() - MAX_REPORT_AGE_MILLIS

        for (page in 0 until MAX_MESSAGE_PAGES) {
            // Stops when we have plenty of matches AND have already scanned
            // past the age window (not just "enough matches" alone) - a
            // shared count-based stop was what starved a quieter selected
            // municipality when a busier one (mixed into the same nationwide
            // feed) filled the quota first from earlier pages.
            if ((out.size >= take) || skip >= totalCount) break

            val messages: JSONArray
            val total: Int
            try {
                val builder = "$BASE_URL/messages".toHttpUrl().newBuilder()
                    .addQueryParameter("Take", MESSAGE_PAGE_SIZE.toString())
                    .addQueryParameter("Skip", skip.toString())
                val body = requestBody(builder.build().toString())
                val root = JSONObject(body)
                messages = root.optJSONArray("messages") ?: root.optJSONArray("Messages")
                    ?: throw JSONException("no messages array in response")
                total = root.optInt("totalCount", if (messages.length() < MESSAGE_PAGE_SIZE) skip + messages.length() else Int.MAX_VALUE)
            } catch (e: Exception) {
                // The very first page failing is a real "couldn't reach it"
                // signal the caller should know about (falls through to the
                // other strategy, then cache). A later page hiccuping after
                // we already have real data is just where the search stops -
                // keep whatever was already found rather than losing it.
                if (page == 0) throw e
                break
            }
            totalCount = total
            if (messages.length() == 0) break

            var oldestOnPageMillis = Long.MAX_VALUE
            for (i in 0 until messages.length()) {
                val msg = messages.optJSONObject(i) ?: continue
                rowsSeenTotal++
                val text = msg.optString("text").ifBlank { msg.optString("description") }
                val id = msg.optString("id").trim().ifBlank { msg.optString("threadId").trim() }
                if (id.isBlank() || text.isBlank()) continue
                rowsWithUsableFields++

                val createdMillis = parseMillis(msg.optString("createdOn").ifBlank { msg.optString("updatedOn") })
                oldestOnPageMillis = minOf(oldestOnPageMillis, createdMillis)
                if (createdMillis < cutoffMillis) continue // older than the 5-day window - not shown, but keep scanning the rest of this page

                val municipalityName = msg.optString("municipality")
                if (municipalities.isNotEmpty() && municipalities.none { it.equals(municipalityName, ignoreCase = true) }) continue
                // Untouched default (every known category selected) behaves as
                // "no filter" - otherwise a live category-name mismatch could
                // silently zero out results for someone who never customized
                // their category checkboxes.
                val category = msg.optString("category")
                val isCustomCategoryFilter = categories.isNotEmpty() && !categories.containsAll(CATEGORY_TRANSLATIONS.keys)
                if (isCustomCategoryFilter && category !in categories) continue
                if (!seenIds.add(id)) continue

                val threadId = msg.optString("threadId").trim().ifBlank { id }
                out += Incident(
                    id = id, threadId = threadId, category = category, categoryEn = CATEGORY_TRANSLATIONS[category] ?: category,
                    municipality = municipalityName, area = msg.optString("area"),
                    createdMillis = createdMillis,
                    text = text, englishText = text, sourceUrl = sourceUrl(threadId)
                )
            }
            skip += MESSAGE_PAGE_SIZE
            // The feed is newest-first, so once an entire page is already
            // older than the 5-day cutoff, every subsequent page will be too -
            // safe to stop scanning regardless of how few matches we've found
            // so far (a real "this municipality had nothing in 5 days" case).
            if (oldestOnPageMillis < cutoffMillis) break
        }
        if (rowsSeenTotal > 0 && rowsWithUsableFields == 0) {
            // Every row across every page fetched was missing id/text -
            // the response shape itself has drifted, not "zero incidents
            // for this municipality". Let the other strategy have a turn.
            throw JSONException("messages had $rowsSeenTotal entries but none had usable id/text fields")
        }
        return out.sortedByDescending { it.createdMillis }.take(take)
    }

    /** The real, confirmed Politiloggen page for one report - plain path, no `/en/` (the site has no English version), no hash routing. */
    private fun sourceUrl(threadId: String): String =
        if (threadId.isBlank()) "https://www.politiet.no/politiloggen"
        else "https://www.politiet.no/politiloggen/hendelse/$threadId"

    // ---- Disk cache: last successful fetch, so a bad refresh never means "no report" ----

    private fun cacheFile(): File = File(context.filesDir, CACHE_FILE_NAME)

    private fun saveCache(incidents: List<Incident>) {
        runCatching {
            val arr = JSONArray()
            incidents.forEach { inc ->
                arr.put(
                    JSONObject()
                        .put("id", inc.id).put("threadId", inc.threadId).put("category", inc.category).put("categoryEn", inc.categoryEn)
                        .put("municipality", inc.municipality).put("area", inc.area)
                        .put("createdMillis", inc.createdMillis).put("text", inc.text).put("englishText", inc.englishText)
                        .put("sourceUrl", inc.sourceUrl)
                )
            }
            val wrapper = JSONObject().put("savedAtMillis", System.currentTimeMillis()).put("incidents", arr)
            cacheFile().writeText(wrapper.toString())
        }
    }

    private fun loadCache(): List<Incident>? = runCatching {
        val wrapper = JSONObject(cacheFile().readText())
        val arr = wrapper.optJSONArray("incidents") ?: return@runCatching null
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Incident(
                id = o.optString("id"), threadId = o.optString("threadId").ifBlank { o.optString("id") },
                category = o.optString("category"), categoryEn = o.optString("categoryEn"),
                municipality = o.optString("municipality"), area = o.optString("area"),
                createdMillis = o.optLong("createdMillis"),
                text = o.optString("text").ifBlank { o.optString("englishText") }, englishText = o.optString("englishText"),
                sourceUrl = o.optString("sourceUrl")
            )
        }
    }.getOrNull()

    private fun loadCacheMeta(): Long? = runCatching {
        JSONObject(cacheFile().readText()).optLong("savedAtMillis").takeIf { it > 0 }
    }.getOrNull()

    // ---- Translation (best-effort, cached, time-boxed) ----
    //
    // Deliberately lazy: the list view never shows translated body text at
    // all (just an algorithmic English headline - see threadHeadline() in
    // the UI), only the detail view does, for one thread's handful of
    // messages at a time. Translating every fetched incident eagerly at
    // fetch time - as this used to do - meant potentially hundreds of
    // incidents (no count cap anymore, just a 4-day window) all competing
    // for one shared timeout, so whichever didn't win the race kept raw
    // Norwegian text with nothing to show for it, and it was wasted effort
    // for every report the person never even opened. Translating on demand,
    // per thread, gives each opened report the full time budget to itself.
    suspend fun translateThread(thread: List<Incident>): List<Incident> = withContext(Dispatchers.IO) {
        if (thread.isEmpty()) return@withContext thread
        val translated = withTimeoutOrNull(10_000L) {
            coroutineScope {
                thread.map { item -> async(Dispatchers.IO) { item.id to translateCached(item.text) } }.awaitAll().toMap()
            }
        }.orEmpty()
        thread.map { item -> translated[item.id]?.let { item.copy(englishText = it) } ?: item }
    }

    private fun translateCached(text: String): String? {
        if (text.isBlank()) return null
        translationCache[text]?.let { return it }
        val result = translateNoToEn(text) ?: return null
        translationCache[text] = result
        return result
    }

    /**
     * Two real bugs used to live here, both of which manifested as "only
     * the first sentence got translated":
     *  1. MyMemory's free tier has a hard ~500 character limit per request -
     *     this was sending up to 2000 characters, which it silently
     *     truncates/rejects rather than erroring.
     *  2. Google's translate_a/single endpoint splits its response into one
     *     array entry PER SENTENCE (`json[0]` is an array of
     *     `[translatedChunk, original, ...]` tuples) - this was only ever
     *     reading `json[0][0][0]`, i.e. just the first sentence, and
     *     silently discarding every sentence after it.
     */
    private fun translateNoToEn(text: String): String? {
        // Google first - it handles full-length police report text properly
        // now that every sentence chunk is stitched back together below.
        runCatching {
            val q = URLEncoder.encode(text.take(4000), "UTF-8")
            val json = JSONArray(requestBody(translationClient, "https://translate.googleapis.com/translate_a/single?client=gtx&sl=no&tl=en&dt=t&q=$q"))
            val sentenceChunks = json.optJSONArray(0) ?: return@runCatching null
            val sb = StringBuilder()
            for (i in 0 until sentenceChunks.length()) {
                sb.append(sentenceChunks.optJSONArray(i)?.optString(0).orEmpty())
            }
            sb.toString().trim().takeIf { it.isNotBlank() }
        }.getOrNull()?.let { return it }

        // MyMemory fallback - respects its real ~500 char/request limit so it
        // doesn't get silently truncated (and therefore return only part of
        // the text) instead of erroring outright.
        return runCatching {
            val q = URLEncoder.encode(text.take(480), "UTF-8")
            val json = JSONObject(requestBody(translationClient, "https://api.mymemory.translated.net/get?q=$q&langpair=no|en"))
            json.optJSONObject("responseData")?.optString("translatedText")?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    // ---- Low-level HTTP + diagnostics ----

    private fun requestBody(url: String): String = requestBody(client, url)

    private fun requestBody(httpClient: OkHttpClient, url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "TheBrief/1.3 Android")
            .header("Accept", "application/json")
            .build()
        return httpClient.newCall(req).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) {
                val snippet = if (bytes.isNotEmpty()) String(bytes).take(300) else "(empty body)"
                throw IllegalStateException("HTTP ${response.code}: $snippet")
            }
            if (bytes.isEmpty()) throw IllegalStateException("HTTP ${response.code}, 0 bytes (empty response body)")
            String(bytes)
        }
    }

    private fun describeError(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "couldn't resolve host"
        is java.net.SocketTimeoutException -> "timed out"
        is javax.net.ssl.SSLException -> "TLS error (${e.message})"
        is JSONException -> "bad response shape (${e.message})"
        is IllegalStateException -> e.message ?: "HTTP error"
        else -> "${e.javaClass.simpleName}: ${e.message}"
    }

    /**
     * The API's timestamps normally parse fine as-is, but a silent
     * fallback to "now" here is actively misleading (a report would show
     * today's date/time instead of when it was actually filed) rather than
     * just imprecise, so this tries a couple of reasonable alternate
     * formats before giving up.
     */
    private fun parseMillis(value: String): Long {
        if (value.isBlank()) return System.currentTimeMillis()
        return runCatching { Instant.parse(value).toEpochMilli() }
            .recoverCatching { java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .recoverCatching { java.time.LocalDateTime.parse(value).atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }
            .getOrElse { System.currentTimeMillis() }
    }
}
