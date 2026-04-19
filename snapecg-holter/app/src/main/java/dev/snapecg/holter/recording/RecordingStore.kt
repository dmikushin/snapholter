package dev.snapecg.holter.recording

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * SQLite storage for ECG recordings, events, and status logs.
 *
 * Data is flushed every second in chunks to minimize loss on crash.
 */
class RecordingStore(context: Context) : SQLiteOpenHelper(context, "holter.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                address TEXT NOT NULL,
                start_time TEXT NOT NULL,
                end_time TEXT,
                sample_count INTEGER DEFAULT 0,
                firmware TEXT,
                serial TEXT,
                status TEXT DEFAULT 'recording'
            )
        """)

        db.execSQL("""
            CREATE TABLE ecg_chunks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                chunk_index INTEGER NOT NULL,
                sample_offset INTEGER NOT NULL,
                samples BLOB NOT NULL,
                timestamp TEXT NOT NULL,
                FOREIGN KEY(session_id) REFERENCES sessions(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                sample_index INTEGER NOT NULL,
                timestamp TEXT NOT NULL,
                tag TEXT NOT NULL,
                text TEXT NOT NULL,
                FOREIGN KEY(session_id) REFERENCES sessions(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE status_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                timestamp TEXT NOT NULL,
                event TEXT NOT NULL,
                details TEXT,
                FOREIGN KEY(session_id) REFERENCES sessions(id)
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())

    // --- Sessions ---

    /** Resolve session ID: if -1, return the latest session ID. */
    private fun resolveSessionId(sessionId: Long): Long {
        if (sessionId >= 0) return sessionId
        val cursor = readableDatabase.rawQuery(
            "SELECT id FROM sessions ORDER BY id DESC LIMIT 1", null)
        cursor.use {
            return if (it.moveToFirst()) it.getLong(0) else -1
        }
    }

    fun createSession(address: String): Long {
        val values = ContentValues().apply {
            put("address", address)
            put("start_time", now())
        }
        return writableDatabase.insert("sessions", null, values)
    }

    fun closeSession(sessionId: Long, totalSamples: Long) {
        val values = ContentValues().apply {
            put("end_time", now())
            put("sample_count", totalSamples)
            put("status", "completed")
        }
        writableDatabase.update("sessions", values, "id=?", arrayOf(sessionId.toString()))
    }

    fun updateSessionMeta(sessionId: Long, firmware: String?, serial: String?) {
        val values = ContentValues()
        firmware?.let { values.put("firmware", it) }
        serial?.let { values.put("serial", it) }
        if (values.size() > 0) {
            writableDatabase.update("sessions", values, "id=?", arrayOf(sessionId.toString()))
        }
    }

    // --- ECG chunks ---

    private var chunkIndex = 0

    fun writeSamples(sessionId: Long, samples: List<Int>) {
        if (samples.isEmpty()) return

        // Pack as 16-bit signed integers (2 bytes per sample)
        val blob = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = samples[i].toShort()
            blob[i * 2] = (v.toInt() and 0xFF).toByte()
            blob[i * 2 + 1] = (v.toInt() shr 8 and 0xFF).toByte()
        }

        val offset = chunkIndex.toLong() * 200  // approximate
        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("chunk_index", chunkIndex)
            put("sample_offset", offset)
            put("samples", blob)
            put("timestamp", now())
        }
        writableDatabase.insert("ecg_chunks", null, values)
        chunkIndex++
    }

    fun readAllSamples(sessionId: Long): List<Int> {
        val samples = mutableListOf<Int>()
        val cursor = readableDatabase.query(
            "ecg_chunks", arrayOf("samples"),
            "session_id=?", arrayOf(sessionId.toString()),
            null, null, "chunk_index ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                val blob = it.getBlob(0)
                for (i in 0 until blob.size / 2) {
                    val lo = blob[i * 2].toInt() and 0xFF
                    val hi = blob[i * 2 + 1].toInt()
                    samples.add((hi shl 8) or lo)
                }
            }
        }
        return samples
    }

    fun getRecentSamples(sessionId: Long, n: Int): List<Int> {
        val resolved = resolveSessionId(sessionId)
        val all = readAllSamples(resolved)
        return if (all.size > n) all.subList(all.size - n, all.size) else all
    }

    // --- Events ---

    fun addEvent(sessionId: Long, sampleIndex: Long, text: String, tag: String) {
        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("sample_index", sampleIndex)
            put("timestamp", now())
            put("tag", tag)
            put("text", text)
        }
        writableDatabase.insert("events", null, values)
    }

    data class Event(val sampleIndex: Long, val timestamp: String, val tag: String, val text: String)

    fun getEvents(sessionId: Long): List<Event> {
        val resolved = resolveSessionId(sessionId)
        val events = mutableListOf<Event>()
        val cursor = readableDatabase.query(
            "events", null,
            "session_id=?", arrayOf(resolved.toString()),
            null, null, "sample_index ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                events.add(Event(
                    sampleIndex = it.getLong(it.getColumnIndexOrThrow("sample_index")),
                    timestamp = it.getString(it.getColumnIndexOrThrow("timestamp")),
                    tag = it.getString(it.getColumnIndexOrThrow("tag")),
                    text = it.getString(it.getColumnIndexOrThrow("text")),
                ))
            }
        }
        return events
    }

    // --- Status log ---

    fun logStatus(sessionId: Long, event: String, details: String? = null) {
        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("timestamp", now())
            put("event", event)
            put("details", details)
        }
        writableDatabase.insert("status_log", null, values)
    }

    // --- Export ---

    fun exportToXml(sessionId: Long): String {
        val resolved = resolveSessionId(sessionId)
        val session = readableDatabase.query(
            "sessions", null, "id=?", arrayOf(resolved.toString()),
            null, null, null
        )
        session.use { c ->
            if (!c.moveToFirst()) return "<error>Session not found</error>"

            val startTime = c.getString(c.getColumnIndexOrThrow("start_time"))
            val endTime = c.getString(c.getColumnIndexOrThrow("end_time")) ?: now()
            val sampleCount = c.getLong(c.getColumnIndexOrThrow("sample_count"))
            val firmware = c.getString(c.getColumnIndexOrThrow("firmware")) ?: "unknown"

            val events = getEvents(resolved)
            val samples = readAllSamples(resolved)

            val sb = StringBuilder()
            sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            sb.appendLine("<holter_recording>")
            sb.appendLine("  <session>")
            sb.appendLine("    <start_time>$startTime</start_time>")
            sb.appendLine("    <end_time>$endTime</end_time>")
            sb.appendLine("    <device firmware=\"$firmware\" />")
            sb.appendLine("    <sample_rate>200</sample_rate>")
            sb.appendLine("    <total_samples>$sampleCount</total_samples>")
            sb.appendLine("  </session>")

            if (events.isNotEmpty()) {
                sb.appendLine("  <events>")
                for (e in events) {
                    sb.appendLine("    <event time=\"${e.timestamp}\" sample_index=\"${e.sampleIndex}\" tag=\"${e.tag}\">")
                    sb.appendLine("      ${e.text}")
                    sb.appendLine("    </event>")
                }
                sb.appendLine("  </events>")
            }

            sb.appendLine("  <ecg_data encoding=\"csv\" unit=\"adc_minus_baseline\">")
            sb.appendLine("sample_idx,ecg")
            for ((i, s) in samples.withIndex()) {
                sb.appendLine("$i,$s")
            }
            sb.appendLine("  </ecg_data>")
            sb.appendLine("</holter_recording>")

            return sb.toString()
        }
    }
}
