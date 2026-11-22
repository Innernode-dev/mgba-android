package com.retro.mgba.data.cheats

import android.content.Context
import android.net.Uri
import com.retro.mgba.R
import com.retro.mgba.core.NativeCore
import com.retro.mgba.data.storage.StorageHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Single cheat entry persisted per game and synchronized to mGBA's native mCheatDevice.
 *
 * Invariants:
 * - [id] is non-blank and unique within a game's cheat list.
 * - [name] is non-blank and trimmed.
 * - [lines] is non-empty; every line is trimmed and non-blank.
 * - [format] matches mGBA's GBACheatType or GBCheatType enum (0 = autodetect).
 */
data class CheatEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val lines: List<String>,
    val format: Int = FORMAT_AUTODETECT,
    val enabled: Boolean = true
) {
    companion object {
        const val FORMAT_AUTODETECT = 0
        // GBA formats (mgba/internal/gba/cheats.h)
        const val GBA_FORMAT_CODEBREAKER = 1
        const val GBA_FORMAT_GAMESHARK = 2
        const val GBA_FORMAT_ACTION_REPLAY = 3
        const val GBA_FORMAT_VBA = 4

        // GB/GBC formats (mgba/internal/gb/cheats.h)
        const val GB_FORMAT_GAMESHARK = 1
        const val GB_FORMAT_GAME_GENIE = 2
        const val GB_FORMAT_VBA = 3
    }
}

data class CheatFormatOption(
    val nativeType: Int,
    val labelResId: Int,
    val exampleHint: String
)

object CheatRepository {

    fun sanitizeGameTitle(rawTitle: String?): String {
        val base = rawTitle?.takeIf { it.isNotBlank() } ?: "game"
        return base.replace(Regex("[^a-zA-Z0-9._ -]"), "_").trim().ifEmpty { "game" }
    }

    fun getFormatsForPlatform(platform: Int): List<CheatFormatOption> {
        return if (platform == NativeCore.PLATFORM_GB) {
            listOf(
                CheatFormatOption(CheatEntry.FORMAT_AUTODETECT, R.string.cheat_format_auto, "01FF12C0 / 00A-12B-34C"),
                CheatFormatOption(CheatEntry.GB_FORMAT_GAMESHARK, R.string.cheat_format_gameshark, "01FF12C0"),
                CheatFormatOption(CheatEntry.GB_FORMAT_GAME_GENIE, R.string.cheat_format_game_genie, "00A-12B-34C"),
                CheatFormatOption(CheatEntry.GB_FORMAT_VBA, R.string.cheat_format_raw, "01234567:FF")
            )
        } else {
            listOf(
                CheatFormatOption(CheatEntry.FORMAT_AUTODETECT, R.string.cheat_format_auto, "82025840 0044"),
                CheatFormatOption(CheatEntry.GBA_FORMAT_GAMESHARK, R.string.cheat_format_gameshark, "D8BAE4D9 4864DCE5"),
                CheatFormatOption(CheatEntry.GBA_FORMAT_ACTION_REPLAY, R.string.cheat_format_action_replay, "7881A409 E2026E0C"),
                CheatFormatOption(CheatEntry.GBA_FORMAT_CODEBREAKER, R.string.cheat_format_codebreaker, "82025840 0044"),
                CheatFormatOption(CheatEntry.GBA_FORMAT_VBA, R.string.cheat_format_raw, "02025840:0044")
            )
        }
    }

    fun getFormatLabelRes(platform: Int, format: Int): Int {
        return getFormatsForPlatform(platform)
            .firstOrNull { it.nativeType == format }
            ?.labelResId
            ?: R.string.cheat_format_auto
    }

    /**
     * Normalizes user-pasted or imported cheat text into clean individual code lines.
     * Supports newline-separated blocks and '+'-separated Libretro lines.
     */
    fun normalizeCodeLines(rawText: String): List<String> {
        if (rawText.isBlank()) return emptyList()
        return rawText
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .split('\n', '+')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    fun getCheatFile(context: Context, sanitizedGameTitle: String): File {
        val dir = StorageHelper.getCheatsDir(context)
        val safeName = sanitizeGameTitle(sanitizedGameTitle)
        return File(dir, "$safeName.json")
    }

    fun loadCheats(context: Context, sanitizedGameTitle: String): List<CheatEntry> {
        val file = getCheatFile(context, sanitizedGameTitle)
        if (!file.exists() || !file.isFile) return emptyList()

        return try {
            val raw = file.readText(Charsets.UTF_8)
            if (raw.isBlank()) return emptyList()
            val arr = JSONArray(raw)
            val result = mutableListOf<CheatEntry>()
            val seenIds = mutableSetOf<String>()

            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                if (name.isEmpty()) continue

                val linesArr = obj.optJSONArray("lines") ?: continue
                val lines = mutableListOf<String>()
                for (j in 0 until linesArr.length()) {
                    val line = linesArr.optString(j, "").trim()
                    if (line.isNotEmpty()) lines.add(line)
                }
                if (lines.isEmpty()) continue

                val rawId = obj.optString("id", "").trim()
                val id = if (rawId.isNotEmpty() && seenIds.add(rawId)) {
                    rawId
                } else {
                    UUID.randomUUID().toString().also { seenIds.add(it) }
                }

                result.add(
                    CheatEntry(
                        id = id,
                        name = name,
                        lines = lines,
                        format = obj.optInt("format", CheatEntry.FORMAT_AUTODETECT).coerceIn(0, 4),
                        enabled = obj.optBoolean("enabled", false)
                    )
                )
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveCheats(context: Context, sanitizedGameTitle: String, cheats: List<CheatEntry>): Boolean {
        val file = getCheatFile(context, sanitizedGameTitle)
        val arr = JSONArray()
        for (cheat in cheats) {
            val cleanName = cheat.name.trim()
            val cleanLines = cheat.lines.map { it.trim() }.filter { it.isNotEmpty() }
            if (cleanName.isEmpty() || cleanLines.isEmpty()) continue

            val obj = JSONObject().apply {
                put("id", cheat.id.ifBlank { UUID.randomUUID().toString() })
                put("name", cleanName)
                put("format", cheat.format.coerceIn(0, 4))
                put("enabled", cheat.enabled)
                val linesArr = JSONArray()
                cleanLines.forEach { linesArr.put(it) }
                put("lines", linesArr)
            }
            arr.put(obj)
        }

        val payload = arr.toString(2)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        return try {
            tmp.writeText(payload, Charsets.UTF_8)
            if (tmp.renameTo(file)) {
                true
            } else {
                if (file.exists()) file.delete()
                if (tmp.renameTo(file)) {
                    true
                } else {
                    file.writeText(payload, Charsets.UTF_8)
                    tmp.delete()
                    true
                }
            }
        } catch (_: Exception) {
            tmp.delete()
            false
        }
    }

    /**
     * Synchronizes the full cheat list into the active native mGBA core.
     * Clears any previous cheat sets first (unpatching ROM patches & detaching hooks),
     * then registers all sets in order so GBA Master Codes propagate encryption keys.
     *
     * @return Number of cheat sets successfully applied to the native core.
     */
    fun syncToCore(core: NativeCore, cheats: List<CheatEntry>): Int {
        if (core.handle == 0L) return 0
        core.clearCheats()
        var appliedCount = 0
        for (cheat in cheats) {
            if (cheat.lines.isEmpty()) continue
            val ok = core.addCheatSet(
                name = cheat.name,
                lines = cheat.lines,
                type = cheat.format,
                enabled = cheat.enabled
            )
            if (ok && cheat.enabled) {
                appliedCount++
            }
        }
        return appliedCount
    }

    /**
     * Reads and parses a user-selected .cht / .txt file (supports both mGBA .cht and Libretro .cht formats).
     */
    fun importChtFromUri(context: Context, uri: Uri): List<CheatEntry> {
        val text = try {
            context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return parseChtContent(text)
    }

    fun parseChtContent(rawText: String): List<CheatEntry> {
        if (rawText.isBlank()) return emptyList()
        val lines = rawText.replace("\r\n", "\n").replace("\r", "\n").split('\n')

        // Detect Libretro .cht key=value format (cheat0_desc, cheat0_code, cheat0_enable)
        val isLibretro = lines.any { it.trimStart().startsWith("cheats =") || it.trimStart().startsWith("cheat0_code") }
        if (isLibretro) {
            return parseLibretroCht(lines)
        }

        // Standard mGBA .cht format: # Name, optional !disabled, followed by code lines
        val result = mutableListOf<CheatEntry>()
        var currentName: String? = null
        var currentEnabled = true
        var nextDisabled = false
        val currentLines = mutableListOf<String>()

        fun flush() {
            val name = currentName
            if (name != null && currentLines.isNotEmpty()) {
                result.add(
                    CheatEntry(
                        name = name,
                        lines = currentLines.toList(),
                        format = CheatEntry.FORMAT_AUTODETECT,
                        enabled = currentEnabled
                    )
                )
            }
            currentLines.clear()
        }

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#") -> {
                    flush()
                    currentName = line.removePrefix("#").trim().ifEmpty { "Cheat ${result.size + 1}" }
                    currentEnabled = !nextDisabled
                    nextDisabled = false
                }
                line.startsWith("!") -> {
                    val directive = line.removePrefix("!").trim()
                    if (directive.equals("disabled", ignoreCase = true)) {
                        nextDisabled = true
                    }
                }
                else -> {
                    if (currentName == null) {
                        currentName = "Cheat ${result.size + 1}"
                        currentEnabled = !nextDisabled
                        nextDisabled = false
                    }
                    normalizeCodeLines(line).forEach { currentLines.add(it) }
                }
            }
        }
        flush()
        return result
    }

    private fun parseLibretroCht(lines: List<String>): List<CheatEntry> {
        val descMap = mutableMapOf<Int, String>()
        val codeMap = mutableMapOf<Int, String>()
        val enableMap = mutableMapOf<Int, Boolean>()

        val regex = Regex("""^cheat(\d+)_(desc|code|enable)\s*=\s*(.+)$""")
        for (rawLine in lines) {
            val match = regex.matchEntire(rawLine.trim()) ?: continue
            val index = match.groupValues[1].toIntOrNull() ?: continue
            val key = match.groupValues[2]
            val value = match.groupValues[3].trim().removeSurrounding("\"")
            when (key) {
                "desc" -> descMap[index] = value
                "code" -> codeMap[index] = value
                "enable" -> enableMap[index] = value.equals("true", ignoreCase = true)
            }
        }

        val indices = codeMap.keys.sorted()
        val result = mutableListOf<CheatEntry>()
        for (idx in indices) {
            val codeLines = normalizeCodeLines(codeMap[idx].orEmpty())
            if (codeLines.isEmpty()) continue
            val name = descMap[idx]?.trim()?.takeIf { it.isNotEmpty() } ?: "Cheat ${idx + 1}"
            val enabled = enableMap[idx] ?: false
            result.add(
                CheatEntry(
                    name = name,
                    lines = codeLines,
                    format = CheatEntry.FORMAT_AUTODETECT,
                    enabled = enabled
                )
            )
        }
        return result
    }
}
