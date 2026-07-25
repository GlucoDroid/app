package tk.glucodata.drivers.ottai

private val CN_MOBILE_NUMBER = Regex("1[3-9]\\d{9}")

/** Validates the 11 subscriber digits entered after the field's fixed +86 prefix. */
internal fun normalizeOttaiCnPhone(raw: String): String? =
    raw.takeIf(CN_MOBILE_NUMBER::matches)
