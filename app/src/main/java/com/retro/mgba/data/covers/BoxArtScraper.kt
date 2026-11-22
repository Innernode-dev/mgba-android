package com.retro.mgba.data.covers

import android.content.Context
import com.retro.mgba.data.db.GameEntity
import com.retro.mgba.data.storage.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Automatic Box Art Downloader using the open libretro-thumbnails repository.
 * Tries No-Intro normalized title candidates with common region tags, validates
 * PNG headers, and caches covers persistently in /files/covers/.
 */
object BoxArtScraper {

    private const val BASE_URL = "https://thumbnails.libretro.com"
    private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024 // 5 MB safety cap
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun systemFolder(system: String): String = when (system.uppercase()) {
        "GBA" -> "Nintendo - Game Boy Advance"
        "GBC" -> "Nintendo - Game Boy Color"
        else -> "Nintendo - Game Boy"
    }

    /**
     * Generates ranked No-Intro candidate filenames (without .png) for a given ROM title.
     */
    fun buildCandidateNames(rawTitle: String, rawFileName: String? = null): List<String> {
        val candidates = linkedSetOf<String>()

        fun sanitizeLibretro(s: String): String {
            return s.replace(Regex("""[&*/:`<>?\\|]"""), "_")
                .replace("é", "e")
                .replace("É", "E")
                .replace(Regex("""\s+"""), " ")
                .trim()
        }

        // 1. Original filename without extension (often already exact No-Intro format!)
        if (!rawFileName.isNullOrBlank()) {
            val fromFile = rawFileName.substringBeforeLast('.').trim()
            if (fromFile.isNotEmpty()) {
                candidates.add(sanitizeLibretro(fromFile))
            }
        }

        // 2. Cleaned title with colon normalized to " - " (No-Intro convention)
        val cleanBase = sanitizeLibretro(
            rawTitle
                .replace(Regex("""\s*\[[^\]]*\]"""), "")
                .replace(":", " -")
                .trim()
        )
        val noParens = sanitizeLibretro(cleanBase.replace(Regex("""\s*\([^)]*\)"""), ""))

        val baseVariants = linkedSetOf<String>().apply {
            if (cleanBase.isNotEmpty()) add(cleanBase)
            if (noParens.isNotEmpty()) add(noParens)
            // Handle common Pokemon shorthand ("Pokemon Emerald" -> "Pokemon - Emerald Version")
            if (noParens.startsWith("Pokemon ", ignoreCase = true) && !noParens.contains(" - ")) {
                val sub = noParens.removePrefix("Pokemon ").removePrefix("pokemon ").trim()
                if (sub.isNotEmpty()) {
                    add("Pokemon - $sub")
                    if (!sub.endsWith("Version", ignoreCase = true)) {
                        add("Pokemon - $sub Version")
                    }
                }
            }
        }

        val regions = listOf(
            "(USA, Europe)",
            "(USA)",
            "(Europe)",
            "(World)",
            "(USA, Australia)",
            "(Europe) (En,Fr,De,Es,It)",
            "(USA) (Rev 1)",
            "(Japan)"
        )

        for (base in baseVariants) {
            candidates.add(base)
            if (!base.contains("(")) {
                for (region in regions) {
                    candidates.add("$base $region")
                }
            }
        }

        return candidates.toList()
    }

    private fun encodePathSegment(segment: String): String {
        return URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
    }

    private fun downloadPng(urlStr: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 6000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "mGBA-Android/1.2")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null

            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_IMAGE_BYTES) return null
                    out.write(buf, 0, read)
                }
            }
            val bytes = out.toByteArray()
            if (bytes.size < PNG_MAGIC.size) return null
            for (i in PNG_MAGIC.indices) {
                if (bytes[i] != PNG_MAGIC[i]) return null
            }
            bytes
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Attempts to fetch and save the official box art for [game].
     * Returns the persistent local file URI string if found, or null otherwise.
     */
    suspend fun fetchCoverForGame(context: Context, game: GameEntity): String? = withContext(Dispatchers.IO) {
        val sysEncoded = encodePathSegment(systemFolder(game.system))
        val rawFileName = android.net.Uri.parse(game.uri).lastPathSegment?.substringAfterLast('/')
        val candidates = buildCandidateNames(game.title, rawFileName)

        for (candidate in candidates) {
            val fileEncoded = encodePathSegment("$candidate.png")
            val url = "$BASE_URL/$sysEncoded/Named_Boxarts/$fileEncoded"
            val bytes = downloadPng(url)
            if (bytes != null) {
                return@withContext StorageHelper.saveDownloadedCoverBytes(context, game.uri, bytes)
            }
        }
        null
    }
}
