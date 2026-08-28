package com.catsmoker.app.shared.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural checks on the generated identity.
 *
 * These exist because a *self-contradictory* profile is a stronger detection signal than the real
 * values would have been — the same reason `GetPropInterceptor` exists. An IMEI that fails Luhn, or
 * an IMSI whose MCC+MNC disagrees with `gsm.sim.operator.numeric`, is a three-line check for any
 * app, and both used to ship.
 *
 * [passesLuhn] is written from the standard here rather than calling the production helper: reusing
 * that helper would only prove the generator agrees with itself, which it did while producing
 * invalid numbers.
 */
class RandomGeneratorTest {

    // ------------------------------------------------------------------ Luhn

    @Test
    fun luhnValidatorMatchesTheCanonicalReferenceImei() {
        // 490154203237518 is the canonical IMEI test vector; its check digit is 8. This pins the
        // test's own validator before the tests below use it to judge the generator.
        assertTrue(passesLuhn("490154203237518"))
        // 0 is what the old (== 0) parity computed for this payload — the exact regression.
        assertFalse(passesLuhn("490154203237510"))
    }

    @Test
    fun generatedImeiIsFifteenDigitsAndPassesLuhn() {
        repeat(REPEATS) {
            val imei = RandomGenerator.generateIMEI()
            assertEquals("IMEI length: $imei", 15, imei.length)
            assertTrue("IMEI not all digits: $imei", imei.all(Char::isDigit))
            assertTrue("IMEI fails Luhn: $imei", passesLuhn(imei))
        }
    }

    @Test
    fun generatedIccidIsNineteenDigitsAndPassesLuhn() {
        repeat(REPEATS) {
            val iccid = RandomGenerator.generateICCID()
            assertEquals("ICCID length: $iccid", 19, iccid.length)
            assertTrue("ICCID not all digits: $iccid", iccid.all(Char::isDigit))
            assertTrue("ICCID fails Luhn: $iccid", passesLuhn(iccid))
        }
    }

    // ------------------------------------------------------------------ IMSI

    @Test
    fun imsiCarriesTheOperatorsOwnMccMnc() {
        repeat(REPEATS) {
            val fiveDigit = RandomGenerator.generateIMSI("23415")
            assertEquals(15, fiveDigit.length)
            assertTrue("IMSI lost its MCC+MNC: $fiveDigit", fiveDigit.startsWith("23415"))

            val sixDigit = RandomGenerator.generateIMSI("405840")
            assertEquals(15, sixDigit.length)
            assertTrue("IMSI lost its MCC+MNC: $sixDigit", sixDigit.startsWith("405840"))
        }
    }

    @Test
    fun imsiTrimsSurroundingWhitespaceRatherThanFallingBack() {
        val imsi = RandomGenerator.generateIMSI("  23415  ")
        assertEquals(15, imsi.length)
        assertTrue(imsi.startsWith("23415"))
    }

    @Test
    fun imsiFallsBackForAnythingThatIsNotAnMccMnc() {
        // A profile with no operator set, hand-entered junk, or a length no MNC has: all fall back
        // to the documented default rather than emitting a short or non-numeric IMSI.
        for (input in listOf("", "   ", "bogus", "1234", "3102600", "3102x0")) {
            val imsi = RandomGenerator.generateIMSI(input)
            assertEquals("length for input '$input'", 15, imsi.length)
            assertTrue("not all digits for input '$input': $imsi", imsi.all(Char::isDigit))
            assertTrue("no fallback prefix for input '$input': $imsi", imsi.startsWith("310260"))
        }
        assertTrue(RandomGenerator.generateIMSI().startsWith("310260"))
    }

    // ------------------------------------------------------- ICCID and phone

    @Test
    fun iccidCarriesTheCountryCodeAndIssuer() {
        val uk = RandomGenerator.generateICCID(callingCode = "44", mnc = "15")
        assertEquals(19, uk.length)
        assertTrue("ICCID head: $uk", uk.startsWith("894415"))
        assertTrue(passesLuhn(uk))
    }

    @Test
    fun iccidPadsAOneDigitCallingCodeToTwoDigits() {
        // Real US serials read 89-01-260, not 89-1-260.
        val us = RandomGenerator.generateICCID(callingCode = "1", mnc = "260")
        assertTrue("ICCID head: $us", us.startsWith("8901260"))
        assertTrue(passesLuhn(us))
    }

    @Test
    fun iccidFallsBackWhenTheOperatorIsUnknown() {
        val iccid = RandomGenerator.generateICCID(operator = null)
        assertEquals(19, iccid.length)
        assertTrue("ICCID head: $iccid", iccid.startsWith("8901260"))
        assertTrue(passesLuhn(iccid))
    }

    @Test
    fun iccidFallsBackForNonNumericInput() {
        val iccid = RandomGenerator.generateICCID(callingCode = "+44", mnc = "n/a")
        assertEquals(19, iccid.length)
        assertTrue("ICCID not all digits: $iccid", iccid.all(Char::isDigit))
        assertTrue("ICCID head: $iccid", iccid.startsWith("8901260"))
    }

    @Test
    fun phoneNumberCarriesTheOperatorsCallingCode() {
        repeat(REPEATS) {
            val number = RandomGenerator.generatePhoneNumber("44")
            assertTrue("Wrong country code: $number", number.startsWith("+44"))
            val national = number.removePrefix("+44")
            assertEquals("National part length: $number", 10, national.length)
            assertTrue("National part not numeric: $number", national.all(Char::isDigit))
            // No numbering plan assigns a leading 0 or 1 to a subscriber number.
            assertTrue("Bad leading digit: $number", national[0] in '2'..'9')
        }
    }

    @Test
    fun phoneNumberFallsBackWhenTheOperatorIsUnknown() {
        val number = RandomGenerator.generatePhoneNumber(operator = null)
        assertTrue("Wrong country code: $number", number.startsWith("+1"))
        assertEquals(12, number.length)
    }

    // -------------------------------------------------------- operator table

    @Test
    fun everyOperatorInTheTableIsSelfConsistent() {
        val seen = LinkedHashSet<RandomGenerator.Operator>()
        repeat(TABLE_DRAWS) { seen.add(RandomGenerator.randomOperator()) }
        // Enough draws that missing an entry is not a real possibility; guards against the loop
        // silently exercising one row.
        assertTrue("Only ${seen.size} distinct operators drawn", seen.size >= 5)

        for (op in seen) {
            assertTrue("Blank name: $op", op.alpha.isNotBlank())
            assertTrue("MCC+MNC must be 5-6 digits: $op", op.numeric.length in 5..6)
            assertTrue("MCC+MNC not numeric: $op", op.numeric.all(Char::isDigit))
            assertTrue("Blank calling code: $op", op.callingCode.isNotBlank())
            assertTrue("Calling code not numeric: $op", op.callingCode.all(Char::isDigit))
            assertEquals("Country ISO must be alpha-2: $op", 2, op.countryIso.length)
            assertEquals("Country ISO must be lowercase: $op", op.countryIso.lowercase(), op.countryIso)
            // The MNC is what the ICCID uses as its issuer identifier.
            assertTrue("MNC must be 2-3 digits: $op", op.mnc.length in 2..3)
            assertTrue("MNC not numeric: $op", op.mnc.all(Char::isDigit))
            assertEquals("MNC must be the numeric minus its MCC: $op", op.numeric, op.numeric.take(3) + op.mnc)
        }
    }

    @Test
    fun operatorLookupRoundTripsAndTrims() {
        val op = RandomGenerator.randomOperator()
        assertEquals(op, RandomGenerator.operatorForNumeric(op.numeric))
        assertEquals(op, RandomGenerator.operatorForNumeric("  ${op.numeric}  "))
    }

    @Test
    fun operatorLookupReturnsNullForAHandEnteredNetwork() {
        // Null is the signal to use the documented fallbacks rather than guess a calling code.
        assertNull(RandomGenerator.operatorForNumeric("999999"))
        assertNull(RandomGenerator.operatorForNumeric(""))
        assertNull(RandomGenerator.operatorForNumeric("not-a-network"))
    }

    @Test
    fun aRolledOperatorProducesASimIdentityThatAgreesWithIt() {
        // The whole point of the Operator record: what the editor writes into the profile must
        // agree with what renderConfig publishes as gsm.sim.operator.numeric.
        repeat(REPEATS) {
            val op = RandomGenerator.randomOperator()
            assertTrue(RandomGenerator.generateIMSI(op.numeric).startsWith(op.numeric))
            assertTrue(
                RandomGenerator.generateICCID(op).startsWith(
                    "89" + op.callingCode.padStart(2, '0') + op.mnc
                )
            )
            assertTrue(RandomGenerator.generatePhoneNumber(op).startsWith("+${op.callingCode}"))
        }
    }

    // ---------------------------------------------------------- other fields

    @Test
    fun hexIdentifiersHaveTheRightWidthAndAlphabet() {
        assertHex(RandomGenerator.generateMEID(), length = 14, uppercase = true)
        assertHex(RandomGenerator.generateAndroidId(), length = 16, uppercase = false)
        assertHex(RandomGenerator.generateGSFId(), length = 16, uppercase = false)
        assertHex(RandomGenerator.generateMediaDrmId(), length = 64, uppercase = false)
    }

    @Test
    fun advertisingIdsAreUuidShaped() {
        for (id in listOf(RandomGenerator.generateGAID(), RandomGenerator.generateAppSetId())) {
            assertTrue("Not UUID-shaped: $id", id.matches(UUID_PATTERN))
        }
    }

    @Test
    fun bootloaderCarriesTheDeviceCodeAndFallsBackWhenBlank() {
        val husky = RandomGenerator.generateBootloader("husky")
        assertTrue("Bootloader: $husky", husky.startsWith("husky-1.2-"))
        assertHex(husky.removePrefix("husky-1.2-"), length = 8, uppercase = true)
        assertTrue(RandomGenerator.generateBootloader("").startsWith("cheetah-1.2-"))
    }

    @Test
    fun fingerprintFallsBackFieldByFieldRatherThanEmittingEmptySegments() {
        val complete = RandomGenerator.generateFingerprint(
            brand = "Google", product = "husky", device = "husky",
            release = "14", buildId = "AP4A.240101.001", incremental = "11111111"
        )
        assertEquals(
            "google/husky/husky:14/AP4A.240101.001/11111111:user/release-keys",
            complete
        )
        // A half-filled profile must still render a structurally valid fingerprint: renderConfig
        // derives ro.build.type and ro.build.tags by splitting this string's tail.
        val sparse = RandomGenerator.generateFingerprint("", "", "", "", "ID", "1")
        assertEquals("google/cheetah/cheetah:15/ID/1:user/release-keys", sparse)
        assertFalse("Empty segment in: $sparse", sparse.contains("//"))
    }

    // ----------------------------------------------------------------- helpers

    private fun assertHex(value: String, length: Int, uppercase: Boolean) {
        assertEquals("Length of $value", length, value.length)
        val alphabet = if (uppercase) "0123456789ABCDEF" else "0123456789abcdef"
        assertTrue("Not $length-char hex ($alphabet): $value", value.all { it in alphabet })
    }

    /**
     * Luhn validation written from the standard: walking right to left, every second digit is
     * doubled, starting with the one immediately left of the check digit.
     */
    private fun passesLuhn(number: String): Boolean {
        var sum = 0
        var doubling = false
        for (i in number.indices.reversed()) {
            var digit = number[i] - '0'
            if (doubling) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            sum += digit
            doubling = !doubling
        }
        return sum % 10 == 0
    }

    private companion object {
        /** A broken checksum or prefix fails on most, not all, random payloads. */
        const val REPEATS = 200
        const val TABLE_DRAWS = 500
        val UUID_PATTERN =
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}
