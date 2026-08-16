package com.catsmoker.app.shared.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Random
import java.util.UUID
import java.util.Locale as JavaLocale

object RandomGenerator {
    private val random = Random()

    fun generateIMEI(): String {
        val tac = "35847631" // Generic Google TAC
        val serial = StringBuilder()
        for (i in 0 until 6) serial.append(random.nextInt(10))
        val imeiWithoutCheck = tac + serial.toString()
        return imeiWithoutCheck + calculateLuhnChecksum(imeiWithoutCheck)
    }

    fun generateMEID(): String {
        val meid = StringBuilder()
        val chars = "0123456789ABCDEF"
        for (i in 0 until 14) meid.append(chars[random.nextInt(16)])
        return meid.toString()
    }

    fun generateAndroidId(): String {
        val chars = "0123456789abcdef"
        val sb = StringBuilder()
        for (i in 0 until 16) sb.append(chars[random.nextInt(16)])
        return sb.toString()
    }

    fun generateIMSI(): String {
        val mcc = "310"
        val mnc = "260"
        val msin = StringBuilder()
        for (i in 0 until 9) msin.append(random.nextInt(10))
        return mcc + mnc + msin.toString()
    }

    fun generateICCID(): String {
        val prefix = "8901"
        val issuer = "260"
        val account = StringBuilder()
        for (i in 0 until 11) account.append(random.nextInt(10))
        val iccidWithoutCheck = prefix + issuer + account.toString()
        return iccidWithoutCheck + calculateLuhnChecksum(iccidWithoutCheck)
    }

    fun generatePhoneNumber(): String {
        val areaCode = 200 + random.nextInt(800)
        val exchange = 200 + random.nextInt(800)
        val subscriber = random.nextInt(10000)
        return String.format(JavaLocale.US, "+1%03d%03d%04d", areaCode, exchange, subscriber)
    }

    fun generateGAID(): String = UUID.randomUUID().toString()

    fun generateGSFId(): String {
        val chars = "0123456789abcdef"
        val sb = StringBuilder()
        for (i in 0 until 16) sb.append(chars[random.nextInt(16)])
        return sb.toString()
    }

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
        val hexChars = "0123456789ABCDEF"
        val hex = StringBuilder()
        for (i in 0 until 8) {
            hex.append(hexChars[random.nextInt(16)])
        }
        val d = device.ifBlank { "cheetah" }
        return "$d-1.2-$hex"
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

    fun randomOperator(): Triple<String, String, String> {
        val operators = listOf(
            Triple("T-Mobile", "310260", "us"),
            Triple("Verizon", "311480", "us"),
            Triple("AT&T", "310410", "us"),
            Triple("Vodafone UK", "23415", "gb"),
            Triple("Orange", "20801", "fr"),
            Triple("Deutsche Telekom", "26201", "de"),
            Triple("China Mobile", "46000", "cn"),
            Triple("Jio", "405840", "in")
        )
        return operators[random.nextInt(operators.size)]
    }

    private fun calculateLuhnChecksum(number: String): Int {
        var sum = 0
        for (i in number.length - 1 downTo 0) {
            var n = Character.getNumericValue(number[i])
            if ((number.length - i) % 2 == 0) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
        }
        return (10 - (sum % 10)) % 10
    }
}
