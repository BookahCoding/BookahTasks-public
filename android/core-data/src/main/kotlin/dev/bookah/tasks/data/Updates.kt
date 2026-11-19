package dev.bookah.tasks.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** What "Check for updates" found. */
sealed interface UpdateResult {
    data class UpToDate(val version: String) : UpdateResult

    /** [pageUrl] is the release page on GitHub (the user downloads and installs from there). */
    data class Available(val version: String, val pageUrl: String) : UpdateResult
    data object Unreachable : UpdateResult
    data object Failed : UpdateResult
}

/** "1.2.3" or "v1.2.3" as numbers, or null for anything else. */
fun parseVersion(text: String): List<Int>? {
    val m = Regex("^v?(\\d{1,4})\\.(\\d{1,4})\\.(\\d{1,4})$").matchEntire(text.trim()) ?: return null
    return m.groupValues.drop(1).map { it.toInt() }
}

fun isNewerVersion(candidate: List<Int>, current: List<Int>): Boolean {
    for (i in 0 until 3) {
        if (candidate[i] != current[i]) return candidate[i] > current[i]
    }
    return false
}

@Serializable
private data class GhRelease(val tag_name: String = "", val html_url: String = "", val draft: Boolean = false)

/**
 * Asks GitHub's public releases list of [repo] ("owner/name") whether a newer version exists. Sends nothing about the user or the calendars:
 * one anonymous GET with the app's generic User-Agent. Only called when the user taps the button, and only in builds made with a releases repository.
 */
class UpdateChecker(private val http: OkHttpClient, private val apiBase: String = "https://api.github.com") {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(repo: String, currentVersion: String): UpdateResult = withContext(Dispatchers.IO) {
        if (!Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(repo)) return@withContext UpdateResult.Failed
        val current = parseVersion(currentVersion) ?: return@withContext UpdateResult.Failed
        val req = Request.Builder().url("$apiBase/repos/$repo/releases?per_page=30")
            .header("User-Agent", Http.USER_AGENT).header("Accept", "application/vnd.github+json").build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext UpdateResult.Failed
                val releases = try { json.decodeFromString<List<GhRelease>>(resp.body.string()) } catch (e: Exception) { return@withContext UpdateResult.Failed }
                var best: Pair<List<Int>, GhRelease>? = null
                for (r in releases) {
                    if (r.draft) continue
                    val v = parseVersion(r.tag_name) ?: continue
                    // only a page on this repository is ever offered to the user
                    if (!r.html_url.startsWith("https://github.com/$repo/")) continue
                    if (best == null || isNewerVersion(v, best.first)) best = v to r
                }
                val found = best
                if (found != null && isNewerVersion(found.first, current)) UpdateResult.Available(found.first.joinToString("."), found.second.html_url)
                else UpdateResult.UpToDate(currentVersion)
            }
        } catch (e: IOException) {
            UpdateResult.Unreachable
        }
    }
}
