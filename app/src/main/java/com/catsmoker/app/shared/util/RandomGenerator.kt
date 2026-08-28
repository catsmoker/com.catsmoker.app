package com.catsmoker.app.shared.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Random
import java.util.UUID
import java.util.Locale as JavaLocale

object RandomGenerator {
    private val random = Random()

    /**
     * One mobile network, carrying every field the identifiers derived from it need.
     *
     * The SIM identity has to agree with itself: a rendered profile publishes the IMSI, the
     * ICCID, the phone number *and* `gsm.sim.operator.numeric` side by side, so anything that
     * generated them independently produced a SIM that contradicted its own operator. Keeping
     * them on one record makes that impossible to get wrong at the call site.
     */
    data class Operator(
        /** Display name, e.g. `T-Mobile`. */
        val alpha: String,
        /** MCC followed by MNC, e.g. `310260` — the `gsm.*.operator.numeric` value. */
        val numeric: String,
        /** ISO 3166-1 alpha-2 country, e.g. `us`. */
        val countryIso: String,
        /** ITU E.164 country calling code, e.g. `1`. Used by the ICCID and the phone number. */
        val callingCode: String
    ) {
        /** The MNC half of [numeric]: everything after the three-digit MCC. */
        val mnc: String get() = numeric.drop(MCC_LENGTH)
    }

    fun generateIMEI(): String {
        val payload = IMEI_TAC + randomDigits(IMEI_LENGTH - 1 - IMEI_TAC.length)
        return payload + calculateLuhnChecksum(payload)
    }

    fun generateMEID(): String = randomHex(14, uppercase = true)

    fun generateAndroidId(): String = randomHex(16, uppercase = false)

    /**
     * A 15-digit IMSI whose MCC+MNC prefix matches the profile's own operator.
     *
     * An IMSI *is* its network's MCC+MNC followed by the subscriber's MSIN. This used to
     * hard-code `310260` (T-Mobile US) while the operator fields were rolled separately, so a
     * profile could ship an IMSI claiming T-Mobile US next to a `gsm.sim.operator.numeric` of
     * `23415` and a `simCountryIso` of `gb`. That disagreement is a stronger signal than the
     * real values would have been.
     *
     * @param operatorNumeric the profile's MCC+MNC. Anything that is not 5 or 6 digits falls
     *   back to [FALLBACK_OPERATOR_NUMERIC], reproducing the old output for a profile that has
     *   no operator set yet.
     */
    fun generateIMSI(operatorNumeric: String = FALLBACK_OPERATOR_NUMERIC): String {
        val prefix = operatorNumeric.trim()
            .takeIf { it.length in OPERATOR_NUMERIC_LENGTHS && it.all(Char::isDigit) }
            ?: FALLBACK_OPERATOR_NUMERIC
        return prefix + randomDigits(IMSI_LENGTH - prefix.length)
    }

    /**
     * A 19-digit ICCID for [callingCode]'s country and [mnc]'s issuer, Luhn check digit last.
     *
     * `89` is the ITU telecom major industry identifier; the digits after it are the country's
     * calling code, then the issuer. Both used to be hard-coded (`8901` + `260`), so every SIM
     * serial described a US T-Mobile SIM no matter which operator the profile claimed.
     *
     * @param callingCode ITU country calling code; non-numeric input falls back to
     *   [FALLBACK_CALLING_CODE].
     * @param mnc issuer identifier, normally the operator's MNC; non-numeric input falls back
     *   to [FALLBACK_MNC].
     */
    fun generateICCID(
        callingCode: String = FALLBACK_CALLING_CODE,
        mnc: String = FALLBACK_MNC
    ): String {
        // The country field is zero-padded to two digits: a US SIM reads 89-01-260, which is why
        // real T-Mobile serials start 8901260. Unpadded it would be 891260, which no SIM carries.
        val country = (callingCode.digitsOrNull() ?: FALLBACK_CALLING_CODE)
            .padStart(ICCID_COUNTRY_MIN_DIGITS, '0')
        val issuer = mnc.digitsOrNull() ?: FALLBACK_MNC
        // Truncate rather than reject: a long calling code and issuer must still leave room for
        // the random account digits and the check digit.
        val head = (ICCID_MII + country + issuer).take(ICCID_LENGTH - 1)
        val payload = head + randomDigits(ICCID_LENGTH - 1 - head.length)
        return payload + calculateLuhnChecksum(payload)
    }

    /**
     * An E.164 number carrying [callingCode]'s country code.
     *
     * Only the country code is carrier-accurate. The national part is a generic 10-digit block
     * whose first digit avoids 0 and 1 — no numbering plan assigns those — rather than a real
     * per-country plan, because inventing one for each entry in [OPERATORS] would trade one
     * wrong answer for eight. The country code is the half that used to disagree outright:
     * every generated number was `+1`, even on a Vodafone UK profile.
     */
    fun generatePhoneNumber(callingCode: String = FALLBACK_CALLING_CODE): String {
        val code = callingCode.digitsOrNull() ?: FALLBACK_CALLING_CODE
        val leading = 2 + random.nextInt(8)
        return "+$code$leading${randomDigits(NATIONAL_NUMBER_LENGTH - 1)}"
    }

    /** [generateICCID] for a whole [operator], falling back to the defaults when it is null. */
    fun generateICCID(operator: Operator?): String =
        generateICCID(operator?.callingCode ?: FALLBACK_CALLING_CODE, operator?.mnc ?: FALLBACK_MNC)

    /** [generatePhoneNumber] for a whole [operator], falling back to the default when null. */
    fun generatePhoneNumber(operator: Operator?): String =
        generatePhoneNumber(operator?.callingCode ?: FALLBACK_CALLING_CODE)

    /**
     * The known operator publishing [numeric], or null for a hand-entered network this table
     * does not carry — in which case the identifiers fall back to their documented defaults
     * rather than guessing a calling code from the MCC.
     */
    fun operatorForNumeric(numeric: String): Operator? {
        val trimmed = numeric.trim()
        return OPERATORS.firstOrNull { it.numeric == trimmed }
    }

    fun generateGAID(): String = UUID.randomUUID().toString()

    fun generateGSFId(): String = randomHex(16, uppercase = false)

    fun generateMediaDrmId(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun generateAppSetId(): String = UUID.randomUUID().toString()

    fun generateBuildId(): String {
        val prefix = "AP4A" // Android 15 prefix
        val dateFormat = SimpleDateFormat("yyMMdd", JavaLocale.US)
        val date = dateFormat.format(Date())
        val build = random.nextInt(999) + 1
        return String.format(JavaLocale.US, "%s.%s.%03d", prefix, date, build)
    }

    fun generateIncremental(): String {
        return String.format(JavaLocale.US, "%08d", random.nextInt(100000000))
    }

    fun generateFingerprint(brand: String, product: String, device: String, release: String, buildId: String, incremental: String): String {
        val b = brand.lowercase(JavaLocale.US).ifBlank { "google" }
        val p = product.lowercase(JavaLocale.US).ifBlank { "cheetah" }
        val d = device.lowercase(JavaLocale.US).ifBlank { "cheetah" }
        val r = release.ifBlank { "15" }
        return String.format(
            JavaLocale.US,
            "%s/%s/%s:%s/%s/%s:user/release-keys",
            b, p, d, r, buildId, incremental
        )
    }

    fun generateSecurityPatch(): String {
        val cal = Calendar.getInstance()
        val daysAgo = random.nextInt(90)
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", JavaLocale.US)
        return dateFormat.format(cal.getTime())
    }

    fun generateBootloader(device: String): String {
        val d = device.ifBlank { "cheetah" }
        return "$d-1.2-${randomHex(8, uppercase = true)}"
    }

    fun randomTimezone(): String {
        val zones = listOf(
            "America/New_York", "America/Los_Angeles", "America/Chicago", "America/Denver",
            "Europe/London", "Europe/Paris", "Europe/Berlin", "Europe/Rome",
            "Asia/Tokyo", "Asia/Shanghai", "Asia/Seoul", "Asia/Kolkata",
            "Australia/Sydney", "Pacific/Auckland", "Africa/Johannesburg", "America/Sao_Paulo"
        )
        return zones[random.nextInt(zones.size)]
    }

    fun randomLocale(): String {
        val locales = listOf(
            "en-US", "en-GB", "fr-FR", "de-DE", "it-IT", "es-ES", "ja-JP", "zh-CN",
            "ko-KR", "hi-IN", "ru-RU", "pt-BR", "tr-TR", "nl-NL", "ar-SA", "vi-VN"
        )
        return locales[random.nextInt(locales.size)]
    }

    fun randomOperator(): Operator = OPERATORS[random.nextInt(OPERATORS.size)]

    // ----------------------------------------------------------------- internals

    /** @return [this] trimmed when it is a non-empty run of digits, otherwise null. */
    private fun String.digitsOrNull(): String? =
        trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

    private fun randomDigits(count: Int): String {
        if (count <= 0) return ""
        val sb = StringBuilder(count)
        repeat(count) { sb.append(random.nextInt(10)) }
        return sb.toString()
    }

    private fun randomHex(count: Int, uppercase: Boolean): String {
        val chars = if (uppercase) HEX_UPPER else HEX_LOWER
        val sb = StringBuilder(count)
        repeat(count) { sb.append(chars[random.nextInt(chars.length)]) }
        return sb.toString()
    }

    /**
     * Luhn check digit for [number], the payload that will carry it as its final digit.
     *
     * Doubling runs over the payload's **odd** positions counting from the right: the check
     * digit occupies the rightmost slot of the completed number, which pushes the payload's
     * last digit into position 2 — the first doubled one.
     *
     * This used to test `% 2 == 0` and so doubled exactly the complementary half, meaning
     * every IMEI and ICCID the app produced failed the validation it exists to pass — and an
     * IMEI that fails Luhn is a three-line check for any app. Canonical case: the payload
     * `49015420323751` must yield 8, completing the reference IMEI `490154203237518`.
     */
    private fun calculateLuhnChecksum(number: String): Int {
        var sum = 0
        for (i in number.length - 1 downTo 0) {
            var n = Character.getNumericValue(number[i])
            if ((number.length - i) % 2 == 1) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
        }
        return (10 - (sum % 10)) % 10
    }

    private const val MCC_LENGTH = 3
    private const val IMEI_LENGTH = 15
    private const val IMSI_LENGTH = 15
    private const val ICCID_LENGTH = 19
    private const val NATIONAL_NUMBER_LENGTH = 10

    /** Generic Google TAC — the type-allocation prefix every generated IMEI carries. */
    private const val IMEI_TAC = "35847631"

    /** ITU major industry identifier for telecommunications, the first two ICCID digits. */
    private const val ICCID_MII = "89"

    /** ITU writes a one-digit calling code as two digits in an ICCID, so `1` becomes `01`. */
    private const val ICCID_COUNTRY_MIN_DIGITS = 2

    private const val FALLBACK_OPERATOR_NUMERIC = "310260"
    private const val FALLBACK_CALLING_CODE = "1"
    private const val FALLBACK_MNC = "260"

    /** An MNC is two or three digits, so MCC+MNC is five or six. */
    private val OPERATOR_NUMERIC_LENGTHS = MCC_LENGTH + 2..MCC_LENGTH + 3

    private const val HEX_LOWER = "0123456789abcdef"
    private const val HEX_UPPER = "0123456789ABCDEF"

    private val OPERATORS = listOf(
        Operator("T-Mobile", "310260", "us", "1"),
        Operator("Verizon", "311480", "us", "1"),
        Operator("AT&T", "310410", "us", "1"),
        Operator("Vodafone UK", "23415", "gb", "44"),
        Operator("Orange", "20801", "fr", "33"),
        Operator("Deutsche Telekom", "26201", "de", "49"),
        Operator("China Mobile", "46000", "cn", "86"),
        Operator("Jio", "405840", "in", "91")
    )
}
