package com.example.agent.tool

import com.example.agent.model.Tool
import com.example.agent.model.ToolPermission
import com.example.agent.model.ToolResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Phase 6 — first built-in tool.
 *
 * It is deliberately small and completely real (no stubbed data): it answers questions such
 * as "jam berapa sekarang?" with the device clock. File, terminal and network tools belong to
 * Phase 7/8 and must go through the workspace + permission layers instead.
 *
 * The arguments are parsed without org.json so the tool is testable in plain JVM unit tests
 * (the bare org.json stubs throw "not mocked" outside of Robolectric).
 */
class CurrentTimeTool(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val defaultTimeZone: TimeZone = TimeZone.getDefault()
) : Tool {

    override val id: String = "builtin.current_time"
    override val name: String = "current_time"
    override val description: String =
        "Mengembalikan tanggal dan waktu sekarang. Gunakan bila pengguna menanyakan jam, hari, tanggal, atau selisih waktu."

    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "timezone_offset_hours": {
              "type": "number",
              "description": "Offset zona waktu dalam jam terhadap UTC, contoh 7 untuk WIB. Kosongkan untuk memakai zona waktu perangkat."
            }
          },
          "required": []
        }
    """.trimIndent()

    override val permission: ToolPermission = ToolPermission.SAFE

    override suspend fun execute(input: String): ToolResult {
        return try {
            val offsetHours = extractTimezoneOffsetHours(input)
            val zone = if (offsetHours != null) {
                TimeZone.getTimeZone(offsetString(offsetHours))
            } else {
                defaultTimeZone
            }
            val now = clock()

            val human = SimpleDateFormat("EEEE, d MMMM yyyy HH:mm:ss", Locale.getDefault())
                .apply { timeZone = zone }
                .format(Date(now))

            val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(now))

            val offsetLabel = offsetString(if (offsetHours != null) offsetHours else currentOffsetHours(zone))

            ToolResult(
                success = true,
                output = "Waktu sekarang: $human (UTC$offsetLabel)\nISO 8601 (UTC): $iso\nEpoch millis: $now",
                metadata = mapOf(
                    "timezone" to zone.id,
                    "epochMillis" to now.toString()
                )
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                output = "",
                error = "Gagal membaca waktu: ${e.message}"
            )
        }
    }

    /**
     * Accepts a JSON body ("timezone_offset_hours": 7), a quoted value or a bare number, and
     * returns null when the offset is absent or out of range. Matches the key explicitly so an
     * unrelated number elsewhere in the arguments can never be mistaken for the offset.
     */
    private fun extractTimezoneOffsetHours(input: String): Double? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        val raw = NUMBER_REGEX
            .find(trimmed)?.groupValues?.getOrNull(1)
            ?.toDoubleOrNull()
            ?: return null

        return if (raw.isFinite() && raw in -14.0..14.0) raw else null
    }

    private fun currentOffsetHours(zone: TimeZone): Double {
        val millis = zone.getOffset(clock()).toDouble()
        return millis / 3_600_000.0
    }

    private fun offsetString(hours: Double): String {
        val sign = if (hours < 0) "-" else "+"
        val abs = kotlin.math.abs(hours)
        val whole = abs.toInt()
        val minutes = ((abs - whole) * 60).toInt()
        return String.format(Locale.US, "%s%02d:%02d", sign, whole, minutes)
    }

    private companion object {
        // "timezone_offset_hours": 7  /  "timezone_offset_hours": "7.5"  /  bare 7
        val NUMBER_REGEX = Regex(
            """(?:"timezone_offset_hours"\s*:\s*"?|^\s*)(-?\d+(?:\.\d+)?)"""
        )
    }
}
