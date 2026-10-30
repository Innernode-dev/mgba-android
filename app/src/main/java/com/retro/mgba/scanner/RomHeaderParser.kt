package com.retro.mgba.scanner

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.nio.charset.StandardCharsets

/**
 * Parsed cartridge metadata extracted from binary ROM headers.
 */
data class RomHeaderInfo(
    val title: String,
    val gameCode: String?,
    val makerCode: String?,
    val system: RomSystem,
    val isChecksumValid: Boolean,
    val romSizeBytes: Long? = null,
    val cgbFlag: Int? = null,
    val sgbFlag: Int? = null,
    val cartridgeType: Int? = null
)

/**
 * Low-level binary ROM header reader.
 * Inspects GBA and GB/GBC header structures via ContentResolver ParcelFileDescriptor.
 * Reads only the required 192-336 byte header prefix to eliminate memory and IPC overhead.
 */
object RomHeaderParser {
    private const val TAG = "RomHeaderParser"

    // GBA Header Layout:
    // 0x0000 - 0x0003: ARM branch instruction
    // 0x0004 - 0x009F: Nintendo Logo (156 bytes)
    // 0x00A0 - 0x00AB: Game Title (12 bytes, uppercase ASCII, null-padded)
    // 0x00AC - 0x00AF: Game Code (4 bytes ASCII, e.g. "BPEE")
    // 0x00B0 - 0x00B1: Maker Code (2 bytes ASCII, e.g. "01")
    // 0x00B2: Fixed value 0x96
    // 0x00BC: Software Version
    // 0x00BD: Complement Checksum
    private const val GBA_HEADER_MIN_SIZE = 192

    // GB/GBC Header Layout:
    // 0x0100 - 0x0103: Entry point (NOP, JP 0x0150)
    // 0x0104 - 0x0133: Nintendo Logo (48 bytes)
    // 0x0134 - 0x0143: Title (16 bytes on DMG; 11 bytes title + 4 bytes manufacturer code + 1 byte CGB flag on CGB)
    // 0x0143: CGB Flag (0x80 = DMG/CGB, 0xC0 = CGB only)
    // 0x0146: SGB Flag (0x03 = SGB)
    // 0x0147: Cartridge Type
    // 0x0148: ROM Size indicator
    // 0x0149: RAM Size indicator
    // 0x014A: Destination Code (0x00 = Japan, 0x01 = Overseas)
    // 0x014D: Header Checksum
    private const val GB_HEADER_MIN_SIZE = 336

    /**
     * Inspects a ROM file directly from an Android SAF Document URI.
     * Safely opens a read-only file descriptor and reads only the first 512 bytes.
     */
    fun parseHeader(context: Context, documentUri: Uri, system: RomSystem): RomHeaderInfo? {
        return try {
            context.contentResolver.openFileDescriptor(documentUri, "r")?.use { pfd ->
                parseHeaderFromFd(pfd, system)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read ROM header for $documentUri: ${e.message}")
            null
        }
    }

    /**
     * Reads header from an open ParcelFileDescriptor.
     */
    fun parseHeaderFromFd(pfd: ParcelFileDescriptor, system: RomSystem): RomHeaderInfo? {
        val minBytes = when (system) {
            RomSystem.GBA -> GBA_HEADER_MIN_SIZE
            RomSystem.GB, RomSystem.GBC -> GB_HEADER_MIN_SIZE
        }

        val buffer = ByteArray(minBytes)
        FileInputStream(pfd.fileDescriptor).use { input ->
            var bytesRead = 0
            while (bytesRead < minBytes) {
                val read = input.read(buffer, bytesRead, minBytes - bytesRead)
                if (read == -1) break
                bytesRead += read
            }
            if (bytesRead < minBytes) {
                return null
            }
        }

        return parseHeaderFromBytes(buffer, system)
    }

    /**
     * Parses cartridge header bytes into RomHeaderInfo.
     */
    fun parseHeaderFromBytes(bytes: ByteArray, system: RomSystem): RomHeaderInfo? {
        return when (system) {
            RomSystem.GBA -> parseGbaHeader(bytes)
            RomSystem.GB, RomSystem.GBC -> parseGbHeader(bytes, system)
        }
    }

    private fun parseGbaHeader(bytes: ByteArray): RomHeaderInfo? {
        if (bytes.size < GBA_HEADER_MIN_SIZE) return null

        // 1. Verify fixed byte at 0x00B2 (Must be 0x96 for valid GBA ROM)
        val fixedByte = bytes[0x00B2].toInt() and 0xFF
        val isFixedValid = fixedByte == 0x96

        // 2. Extract Game Title (0x00A0..0x00AB, 12 bytes)
        val rawTitle = extractAscii(bytes, 0x00A0, 12)
        val title = rawTitle.trim().trim('\u0000')

        // 3. Extract Game Code (0x00AC..0x00AF, 4 bytes)
        val rawGameCode = extractAscii(bytes, 0x00AC, 4).trim().trim('\u0000')
        val gameCode = if (rawGameCode.isNotBlank() && rawGameCode.all { it.isLetterOrDigit() }) {
            rawGameCode
        } else {
            null
        }

        // 4. Extract Maker Code (0x00B0..0x00B1, 2 bytes)
        val rawMakerCode = extractAscii(bytes, 0x00B0, 2).trim().trim('\u0000')
        val makerCode = if (rawMakerCode.isNotBlank()) rawMakerCode else null

        // 5. Calculate complement checksum (0x00BD)
        // Checksum formula: -(sum(bytes[0x00A0..0x00BC]) + 0x19) & 0xFF
        var sum = 0
        for (i in 0x00A0..0x00BC) {
            sum += (bytes[i].toInt() and 0xFF)
        }
        val expectedChecksum = (-(sum + 0x19)) and 0xFF
        val actualChecksum = bytes[0x00BD].toInt() and 0xFF
        val isChecksumValid = (expectedChecksum == actualChecksum) && isFixedValid

        return RomHeaderInfo(
            title = if (title.isNotBlank()) title else "Unknown GBA Game",
            gameCode = gameCode,
            makerCode = makerCode,
            system = RomSystem.GBA,
            isChecksumValid = isChecksumValid
        )
    }

    private fun parseGbHeader(bytes: ByteArray, requestedSystem: RomSystem): RomHeaderInfo? {
        if (bytes.size < GB_HEADER_MIN_SIZE) return null

        // CGB Flag at 0x0143: 0x80 = CGB/DMG compatible, 0xC0 = CGB only
        val cgbFlag = bytes[0x0143].toInt() and 0xFF
        val isCgb = (cgbFlag == 0x80 || cgbFlag == 0xC0)
        val effectiveSystem = if (isCgb) RomSystem.GBC else requestedSystem

        // 1. Extract Title & Game Code
        val title: String
        val gameCode: String?

        if (isCgb) {
            // CGB: Title is 11 bytes (0x0134..0x013E), Manufacturer code is 4 bytes (0x013F..0x0142)
            val rawTitle = extractAscii(bytes, 0x0134, 11).trim().trim('\u0000')
            val rawCode = extractAscii(bytes, 0x013F, 4).trim().trim('\u0000')
            title = if (rawTitle.isNotBlank()) rawTitle else "Unknown GB Game"
            gameCode = if (rawCode.isNotBlank() && rawCode.all { it.isLetterOrDigit() }) rawCode else null
        } else {
            // DMG: Title is full 16 bytes (0x0134..0x0143)
            val rawTitle = extractAscii(bytes, 0x0134, 16).trim().trim('\u0000')
            title = if (rawTitle.isNotBlank()) rawTitle else "Unknown GB Game"
            gameCode = null
        }

        // 2. Header Checksum (0x014D)
        // Checksum formula: -(sum(bytes[0x0134..0x014C]) + 1) & 0xFF
        var chk = 0
        for (i in 0x0134..0x014C) {
            chk = (chk - (bytes[i].toInt() and 0xFF) - 1) and 0xFF
        }
        val actualChecksum = bytes[0x014D].toInt() and 0xFF
        val isChecksumValid = (chk == actualChecksum)

        // 3. Cartridge & Size info
        val sgbFlag = bytes[0x0146].toInt() and 0xFF
        val cartridgeType = bytes[0x0147].toInt() and 0xFF
        val romSizeCode = bytes[0x0148].toInt() and 0xFF
        val romSizeBytes = if (romSizeCode <= 8) {
            32768L shl romSizeCode
        } else {
            null
        }

        return RomHeaderInfo(
            title = title,
            gameCode = gameCode,
            makerCode = null,
            system = effectiveSystem,
            isChecksumValid = isChecksumValid,
            romSizeBytes = romSizeBytes,
            cgbFlag = cgbFlag,
            sgbFlag = sgbFlag,
            cartridgeType = cartridgeType
        )
    }

    private fun extractAscii(bytes: ByteArray, offset: Int, length: Int): String {
        val end = minOf(offset + length, bytes.size)
        val chunk = bytes.copyOfRange(offset, end)
        return String(chunk, StandardCharsets.ISO_8859_1)
    }
}
