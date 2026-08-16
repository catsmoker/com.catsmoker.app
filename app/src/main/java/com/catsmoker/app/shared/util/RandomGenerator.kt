package com.catsmoker.app.shared.util

import java.util.Random
import java.util.UUID

object RandomGenerator {
    private val random = Random()

    fun generateIMEI(): String {
        val imei = StringBuilder()
        for (i in 0 until 14) imei.append(random.nextInt(10))
        imei.append(calculateLuhnChecksum(imei.toString()))
        return imei.toString()
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
        val imsi = StringBuilder("310260") // Example MCC/MNC
        for (i in 0 until 9) imsi.append(random.nextInt(10))
        return imsi.toString()
    }

    fun generateICCID(): String {
        val iccid = StringBuilder("8901")
        for (i in 0 until 15) iccid.append(random.nextInt(10))
        iccid.append(calculateLuhnChecksum(iccid.toString()))
        return iccid.toString()
    }

    fun generatePhoneNumber(): String {
        val sb = StringBuilder("+1")
        for (i in 0 until 10) sb.append(random.nextInt(10))
        return sb.toString()
    }

    fun generateGAID(): String = UUID.randomUUID().toString()

    fun generateGSFId(): String {
        val chars = "0123456789abcdef"
        val sb = StringBuilder()
        for (i in 0 until 16) sb.append(chars[random.nextInt(16)])
        return sb.toString()
    }

    fun generateMediaDrmId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun generateAppSetId(): String = UUID.randomUUID().toString()

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
