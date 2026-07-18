package tk.glucodata

import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import tk.glucodata.Log.doLog

/**
 * Wear Sync v2: the plan-of-record replacement for the legacy mirror tunnel.
 * One mechanism for live values AND history backfill: the watch asks for
 * everything since a timestamp, the phone answers with chunked
 * [time, auto*10, raw*10] triples served straight from native storage, the
 * watch writes them back through the idempotent minute-addressed stream API.
 * New readings on the phone push a small tail chunk through the same path, so
 * repeats are free and there is no session state to corrupt.
 *
 * Wire format (big-endian), version 1:
 *  request  (watch→phone, SYNC2_REQ_PATH):  [u8 ver][i64 fromSec]
 *  chunk    (phone→watch, SYNC2_CHUNK_PATH):
 *   [u8 ver][u8 flags bit0=final][u16 count][u8 serialLen][serial utf8]
 *   [count × (i64 timeSec, i32 auto10, i32 raw10)]
 */
object WearSync2 {
    private const val LOG_ID = "WearSync2"
    private const val VERSION = 1
    private const val MAX_TRIPLES_PER_CHUNK = 600 // ~14.5KB, under MessageClient limits
    private const val TAIL_TRIPLES = 8
    private const val PUSH_THROTTLE_MS = 45_000L
    private const val BACKFILL_HORIZON_SEC = 24L * 3600L

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "WearSync2").apply { isDaemon = true }
    }
    private val lastPushMs = AtomicLong(0L)

    // ---- phone side ----

    /** Called from the reading dispatch path; throttled tail push. */
    @JvmStatic
    fun pushTail() {
        val now = System.currentTimeMillis()
        val last = lastPushMs.get()
        if (now - last < PUSH_THROTTLE_MS || !lastPushMs.compareAndSet(last, now)) return
        executor.execute { runCatching { serveSince(tailStartSec()) }.onFailure { Log.stack(LOG_ID, "pushTail", it) } }
    }

    /** Serve the full backfill horizon (fresh-watch bootstrap). */
    @JvmStatic
    fun serveAll() {
        executor.execute { runCatching { serveSince(sanitizeFrom(0L)) }.onFailure { Log.stack(LOG_ID, "serveAll", it) } }
    }

    /** Handle an incoming request from the watch. */
    @JvmStatic
    fun onRequest(data: ByteArray?) {
        val fromSec = runCatching {
            val buf = ByteBuffer.wrap(data ?: return)
            if (buf.get().toInt() != VERSION) return
            buf.long
        }.getOrNull() ?: return
        executor.execute { runCatching { serveSince(sanitizeFrom(fromSec)) }.onFailure { Log.stack(LOG_ID, "onRequest", it) } }
    }

    private fun tailStartSec(): Long = System.currentTimeMillis() / 1000L - TAIL_TRIPLES * 60L

    private fun sanitizeFrom(fromSec: Long): Long {
        val floor = System.currentTimeMillis() / 1000L - BACKFILL_HORIZON_SEC
        return fromSec.coerceAtLeast(floor)
    }

    private fun serveSince(fromSec: Long) {
        val serial = runCatching { SensorIdentity.resolveMainSensor() }.getOrNull()
            ?: runCatching { Natives.lastsensorname() }.getOrNull().takeUnless { it.isNullOrEmpty() }
            ?: return
        val triples = runCatching { Natives.getGlucoseHistoryForSensor(serial, fromSec) }.getOrNull()
        if (triples == null || triples.size < 3) return
        val total = triples.size / 3
        var index = 0
        while (index < total) {
            val count = minOf(MAX_TRIPLES_PER_CHUNK, total - index)
            val final = index + count >= total
            sendChunk(serial, triples, index, count, final)
            index += count
        }
        if (doLog) Log.i(LOG_ID, "served $total triples for $serial since $fromSec")
    }

    private fun sendChunk(serial: String, triples: LongArray, offset: Int, count: Int, final: Boolean) {
        val serialBytes = serial.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(1 + 1 + 2 + 1 + serialBytes.size + count * 16)
        buf.put(VERSION.toByte())
        buf.put(if (final) 1 else 0)
        buf.putShort(count.toShort())
        buf.put(serialBytes.size.toByte())
        buf.put(serialBytes)
        for (i in 0 until count) {
            val base = (offset + i) * 3
            buf.putLong(triples[base])
            buf.putInt(triples[base + 1].toInt())
            buf.putInt(triples[base + 2].toInt())
        }
        MessageSender.sendSyncMessage(MessageSender.SYNC2_CHUNK_PATH, buf.array())
    }

    // ---- watch side ----

    /** Ask the phone for everything we don't have yet. */
    @JvmStatic
    fun requestSync() {
        executor.execute {
            runCatching {
                // Always ask for the full horizon: writes are idempotent and
                // the whole day is ≤3 chunks, while "since last reading" left
                // permanent holes behind any gap or wipe.
                val fromSec = System.currentTimeMillis() / 1000L - BACKFILL_HORIZON_SEC
                val buf = ByteBuffer.allocate(9)
                buf.put(VERSION.toByte())
                buf.putLong(fromSec)
                MessageSender.sendSyncMessage(MessageSender.SYNC2_REQ_PATH, buf.array())
                if (doLog) Log.i(LOG_ID, "requested sync from $fromSec")
            }.onFailure { Log.stack(LOG_ID, "requestSync", it) }
        }
    }

    /** Ingest a chunk into native storage. */
    @JvmStatic
    fun onChunk(data: ByteArray?) {
        executor.execute {
            runCatching {
                val buf = ByteBuffer.wrap(data ?: return@execute)
                if (buf.get().toInt() != VERSION) return@execute
                val final = buf.get().toInt() and 1 != 0
                val count = buf.short.toInt() and 0xFFFF
                val serialLen = buf.get().toInt() and 0xFF
                val serialBytes = ByteArray(serialLen); buf.get(serialBytes)
                val serial = String(serialBytes, Charsets.UTF_8)
                if (serial.isEmpty() || count <= 0) return@execute
                var written = 0
                var earliest = 0L
                for (i in 0 until count) {
                    val t = buf.long
                    val auto10 = buf.int
                    val raw10 = buf.int
                    if (t <= 0L || auto10 <= 0) continue
                    if (earliest == 0L) {
                        earliest = t
                        // Native scale contract (g.cpp addGlucoseStreamInternal):
                        // glucose param = mgdl/10 (native ×10), raw param = plain
                        // mgdl. Triples carry mgdl*10.
                        Natives.ensureSensorShell(serial, (t - 3600L).coerceAtLeast(1L))
                    }
                    val rawMgdl = if (raw10 > 0) raw10 / 10f else 0f
                    Natives.addGlucoseStreamWithRawTemp(t, auto10 / 100f, rawMgdl, 0f, serial)
                    written++
                }
                if (written > 0) {
                    runCatching {
                        if (Natives.lastsensorname().isNullOrEmpty()) Natives.setcurrentsensor(serial)
                    }
                    UiRefreshBus.requestDataRefresh()
                }
                if (doLog) Log.i(LOG_ID, "ingested $written/$count triples for $serial final=$final")
            }.onFailure { Log.stack(LOG_ID, "onChunk", it) }
        }
    }
}
