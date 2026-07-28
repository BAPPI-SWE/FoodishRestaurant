package com.yumzy.restaurant.utils

import android.content.Context

/**
 * Lightweight, persistent tracker used by OrderMonitorService (the single source of truth for
 * alarms - see note in that file) to make sure each order id only ever triggers ONE "new order"
 * alarm and ONE "cancelled order" alarm, no matter how many times the Firestore listener
 * restarts (Firestore replays the entire initial snapshot as ADDED events on every restart,
 * which used to cause a flood of repeat alarms for already-active orders).
 *
 * It also holds a short-lived LOCAL FALLBACK timestamp for "when did this device first see this
 * order as Cancelled" - used only for the few seconds/moments before Firestore's own
 * `cancelledObservedAt` server timestamp (see Models.kt / PartnerOrder) has propagated back down
 * to this device. Once that Firestore field is present, it is always treated as the source of
 * truth for the 10-minute countdown - not this local fallback.
 */
object OrderAlertTracker {
    private const val PREFS_NAME = "YumzyPartnerPrefs"
    private const val KEY_ALERTED_NEW = "alerted_new_order_ids"
    private const val KEY_ALERTED_CANCEL = "alerted_cancel_order_ids"
    private const val KEY_CANCEL_FALLBACK_TS = "cancel_fallback_timestamps" // "orderId|timestamp"

    const val CANCEL_GRACE_PERIOD_MS = 10 * 60 * 1000L // 10 minutes

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------------- New-order alarm dedup ----------------

    fun hasAlertedNewOrder(context: Context, orderId: String): Boolean =
        prefs(context).getStringSet(KEY_ALERTED_NEW, emptySet())?.contains(orderId) == true

    fun markNewOrderAlerted(context: Context, orderId: String) {
        val p = prefs(context)
        val current = (p.getStringSet(KEY_ALERTED_NEW, emptySet()) ?: emptySet()).toMutableSet()
        current.add(orderId)
        p.edit().putStringSet(KEY_ALERTED_NEW, current).apply()
    }

    // ---------------- Cancelled-order alarm dedup ----------------

    fun hasAlertedCancelledOrder(context: Context, orderId: String): Boolean =
        prefs(context).getStringSet(KEY_ALERTED_CANCEL, emptySet())?.contains(orderId) == true

    fun markCancelledOrderAlerted(context: Context, orderId: String) {
        val p = prefs(context)
        val current = (p.getStringSet(KEY_ALERTED_CANCEL, emptySet()) ?: emptySet()).toMutableSet()
        current.add(orderId)
        p.edit().putStringSet(KEY_ALERTED_CANCEL, current).apply()
    }

    // ---------------- Local fallback "first seen cancelled" timestamp ----------------

    private fun readFallbackTimestamps(context: Context): MutableMap<String, Long> {
        val raw = prefs(context).getStringSet(KEY_CANCEL_FALLBACK_TS, emptySet()) ?: emptySet()
        val map = mutableMapOf<String, Long>()
        raw.forEach { entry ->
            val parts = entry.split("|")
            if (parts.size == 2) {
                val ts = parts[1].toLongOrNull()
                if (ts != null) map[parts[0]] = ts
            }
        }
        return map
    }

    private fun writeFallbackTimestamps(context: Context, map: Map<String, Long>) {
        val encoded = map.entries.map { "${it.key}|${it.value}" }.toSet()
        prefs(context).edit().putStringSet(KEY_CANCEL_FALLBACK_TS, encoded).apply()
    }

    /** Returns the existing fallback timestamp for this order, recording "now" the first time it's asked for. */
    fun getOrRecordFallbackCancelTimestamp(context: Context, orderId: String): Long {
        val map = readFallbackTimestamps(context)
        map[orderId]?.let { return it }
        val now = System.currentTimeMillis()
        map[orderId] = now
        writeFallbackTimestamps(context, map)
        return now
    }

    /** Call occasionally (e.g. service start) to stop the pref set from growing forever. */
    fun purgeExpiredFallbackTimestamps(context: Context) {
        val now = System.currentTimeMillis()
        val kept = readFallbackTimestamps(context).filter { now - it.value < CANCEL_GRACE_PERIOD_MS }
        writeFallbackTimestamps(context, kept)
    }
}