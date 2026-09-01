package com.malik05255.market

internal class BarcodeCapturePolicy(
    private val requiredHits: Int = 2,
    private val maxGapMs: Long = 900L
) {
    private var candidate: String? = null
    private var hits: Int = 0
    private var lastSeenAt: Long = 0L

    fun observe(rawValue: String?, nowMs: Long): String? {
        val value = normalizeRetailBarcode(rawValue) ?: return null
        if (candidate == value && nowMs - lastSeenAt <= maxGapMs) {
            hits += 1
        } else {
            candidate = value
            hits = 1
        }
        lastSeenAt = nowMs
        return value.takeIf { hits >= requiredHits }
    }
}

internal fun normalizeRetailBarcode(rawValue: String?): String? {
    val value = rawValue?.trim().orEmpty()
    if (value.isEmpty() || value.any { !it.isDigit() }) return null
    if (value.length !in setOf(8, 12, 13, 14)) return null
    return value.takeIf(::hasValidGtinCheckDigit)
}

private fun hasValidGtinCheckDigit(value: String): Boolean {
    var sum = 0
    value.dropLast(1).reversed().forEachIndexed { index, ch ->
        sum += ch.digitToInt() * if (index % 2 == 0) 3 else 1
    }
    return value.last().digitToInt() == (10 - (sum % 10)) % 10
}
