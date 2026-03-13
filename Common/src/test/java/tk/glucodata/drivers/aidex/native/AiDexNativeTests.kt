// JugglucoNG — AiDex Native Kotlin Driver
// AiDexNativeTests.kt — Unit tests for CRC, crypto, parsing, key derivation
// Test vectors from NIST SP 800-38A, captured HCI traces, and live sensor data
//
// Ported from iGlucco Tests/AiDexProtocolTests.swift (68 tests)

package tk.glucodata.drivers.aidex.native

import org.junit.Assert.*
import org.junit.Test
import tk.glucodata.drivers.aidex.native.crypto.AesCfb128
import tk.glucodata.drivers.aidex.native.crypto.Crc16CcittFalse
import tk.glucodata.drivers.aidex.native.crypto.Crc8Maxim
import tk.glucodata.drivers.aidex.native.crypto.SerialCrypto
import tk.glucodata.drivers.aidex.native.protocol.AiDexCommandBuilder
import tk.glucodata.drivers.aidex.native.protocol.AiDexKeyExchange
import tk.glucodata.drivers.aidex.native.protocol.AiDexOpcodes
import tk.glucodata.drivers.aidex.native.protocol.AiDexParser

// ============================================================================
// MARK: - CRC-8/MAXIM Tests
// ============================================================================

class Crc8MaximTests {

    @Test
    fun testEmptyInput() {
        assertEquals(0x00, Crc8Maxim.checksum(byteArrayOf()))
    }

    @Test
    fun testSingleByte() {
        // CRC-8/MAXIM of [0x00] should be 0x00 (identity)
        assertEquals(0x00, Crc8Maxim.checksum(byteArrayOf(0x00)))
    }

    @Test
    fun testKnownValues() {
        // CRC-8/MAXIM("123456789") = 0xA1 (standard check value)
        val check = byteArrayOf(0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39)
        assertEquals(0xA1, Crc8Maxim.checksum(check))
    }

    @Test
    fun testMultipleBytes() {
        val bytes = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50)
        // Just verify it produces a consistent result
        val result = Crc8Maxim.checksum(bytes)
        assertEquals(result, Crc8Maxim.checksum(bytes))
    }
}

// ============================================================================
// MARK: - CRC-16/CCITT-FALSE Tests
// ============================================================================

class Crc16CcittFalseTests {

    // Known test vectors from HCI trace
    @Test
    fun testPostBondConfig() {
        // CRC-16(0x10) = 0xF3C1
        assertEquals(0xF3C1, Crc16CcittFalse.checksum(byteArrayOf(0x10)))
    }

    @Test
    fun testGetBroadcastData() {
        // CRC-16(0x11) = 0xE3E0
        assertEquals(0xE3E0, Crc16CcittFalse.checksum(byteArrayOf(0x11)))
    }

    @Test
    fun testGetDeviceInfo() {
        // CRC-16(0x21) = 0xD5B3
        assertEquals(0xD5B3, Crc16CcittFalse.checksum(byteArrayOf(0x21)))
    }

    @Test
    fun testDeleteBond() {
        // CRC-16(0xF2) = 0x2EAD
        assertEquals(0x2EAD, Crc16CcittFalse.checksum(byteArrayOf(0xF2.toByte())))
    }

    @Test
    fun testMultipleBytes() {
        val bytes = byteArrayOf(0x23, 0x40, 0x38)
        val result = Crc16CcittFalse.checksum(bytes)
        assertEquals(result, Crc16CcittFalse.checksum(bytes))
    }

    @Test
    fun testMakeCommand_NoParams() {
        // makeCommand(0x11) should produce [0x11, 0xE0, 0xE3]
        val cmd = Crc16CcittFalse.makeCommand(0x11)
        assertArrayEquals(byteArrayOf(0x11, 0xE0.toByte(), 0xE3.toByte()), cmd)
    }

    @Test
    fun testMakeCommand_PostBondConfig() {
        // makeCommand(0x10) should produce [0x10, 0xC1, 0xF3]
        val cmd = Crc16CcittFalse.makeCommand(0x10)
        assertArrayEquals(byteArrayOf(0x10, 0xC1.toByte(), 0xF3.toByte()), cmd)
    }

    @Test
    fun testMakeCommand_DeleteBond() {
        // makeCommand(0xF2) should produce [0xF2, 0xAD, 0x2E]
        val cmd = Crc16CcittFalse.makeCommand(0xF2)
        assertArrayEquals(byteArrayOf(0xF2.toByte(), 0xAD.toByte(), 0x2E), cmd)
    }

    @Test
    fun testMakeCommand_WithParams() {
        // From live log: plaintext=23 40 38 9D A9
        // opcode=0x23, params=[0x40, 0x38], CRC=[0x9D, 0xA9]
        val cmd = Crc16CcittFalse.makeCommand(0x23, 0x40, 0x38)
        assertArrayEquals(byteArrayOf(0x23, 0x40, 0x38, 0x9D.toByte(), 0xA9.toByte()), cmd)
    }

    @Test
    fun testMakeCommandWithU16() {
        // From live log: plaintext=23 40 38 9D A9
        // offset = 0x3840 = 14400
        val cmd = Crc16CcittFalse.makeCommandWithU16(0x23, 14400)
        assertArrayEquals(byteArrayOf(0x23, 0x40, 0x38, 0x9D.toByte(), 0xA9.toByte()), cmd)
    }

    @Test
    fun testValidateResponse() {
        // makeCommand already validates CRC round-trip
        val cmd = Crc16CcittFalse.makeCommand(0x21)
        assertTrue(Crc16CcittFalse.validateResponse(cmd))
    }

    @Test
    fun testValidateResponse_CorruptData() {
        // Corrupt: flip a bit
        val cmd = Crc16CcittFalse.makeCommand(0x21)
        cmd[0] = (cmd[0].toInt() xor 0x01).toByte()
        assertFalse(Crc16CcittFalse.validateResponse(cmd))
    }

    @Test
    fun testValidateResponse_TooShort() {
        assertFalse(Crc16CcittFalse.validateResponse(byteArrayOf(0x11)))
        assertFalse(Crc16CcittFalse.validateResponse(byteArrayOf()))
    }

    // Verify CRC-16 on multiple live log command plaintexts
    @Test
    fun testLiveLogPlaintextCRCs() {
        data class CrcVector(val plaintext: ByteArray, val label: String)

        val vectors = listOf(
            CrcVector(byteArrayOf(0x23, 0x40, 0x38, 0x9D.toByte(), 0xA9.toByte()), "GET_HISTORIES_RAW(14400)"),
            CrcVector(byteArrayOf(0x23, 0xB7.toByte(), 0x38, 0xCB.toByte(), 0x23), "GET_HISTORIES_RAW(14519)"),
            CrcVector(byteArrayOf(0x23, 0x2E, 0x39, 0x99.toByte(), 0x91.toByte()), "GET_HISTORIES_RAW(14638)"),
            CrcVector(byteArrayOf(0x23, 0xA5.toByte(), 0x39, 0xFB.toByte(), 0x56), "GET_HISTORIES_RAW(14757)"),
            CrcVector(byteArrayOf(0x23, 0x1C, 0x3A, 0x0D, 0xC2.toByte()), "GET_HISTORIES_RAW(14876)"),
            CrcVector(byteArrayOf(0x23, 0x93.toByte(), 0x3A, 0xAB.toByte(), 0xC9.toByte()), "GET_HISTORIES_RAW(14995)"),
            CrcVector(byteArrayOf(0x23, 0x0A, 0x3B, 0xF9.toByte(), 0x7B), "GET_HISTORIES_RAW(15114)"),
            CrcVector(byteArrayOf(0x23, 0x81.toByte(), 0x3B, 0x9B.toByte(), 0xBC.toByte()), "GET_HISTORIES_RAW(15233)"),
            CrcVector(byteArrayOf(0x23, 0xF8.toByte(), 0x3B, 0x5A, 0x0E), "GET_HISTORIES_RAW(15352)"),
        )

        for (v in vectors) {
            assertTrue("CRC failed for ${v.label}", Crc16CcittFalse.validateResponse(v.plaintext))
        }
    }
}

// ============================================================================
// MARK: - Serial Crypto Tests
// ============================================================================

class SerialCryptoTests {

    @Test
    fun testCharToNumeric_Digits() {
        assertEquals(0, SerialCrypto.charToNumeric('0'))
        assertEquals(5, SerialCrypto.charToNumeric('5'))
        assertEquals(9, SerialCrypto.charToNumeric('9'))
    }

    @Test
    fun testCharToNumeric_Letters() {
        assertEquals(10, SerialCrypto.charToNumeric('A'))
        assertEquals(15, SerialCrypto.charToNumeric('F'))
        assertEquals(31, SerialCrypto.charToNumeric('V'))
        assertEquals(35, SerialCrypto.charToNumeric('Z'))
    }

    @Test
    fun testSnToBytes() {
        // "2222267V4E" -> [2, 2, 2, 2, 2, 6, 7, 31, 4, 14]
        val bytes = SerialCrypto.snToBytes("2222267V4E")
        assertArrayEquals(byteArrayOf(2, 2, 2, 2, 2, 6, 7, 31, 4, 14), bytes)
    }

    @Test
    fun testDeriveSecret() {
        // Known vector: SN "2222267V4E" -> secret = 4b76169576da80e4eeacf886230873d2
        val secret = SerialCrypto.deriveSecret("2222267V4E")
        val expected = byteArrayOf(
            0x4b, 0x76, 0x16, 0x95.toByte(), 0x76, 0xda.toByte(), 0x80.toByte(), 0xe4.toByte(),
            0xee.toByte(), 0xac.toByte(), 0xf8.toByte(), 0x86.toByte(), 0x23, 0x08, 0x73, 0xd2.toByte()
        )
        assertArrayEquals(expected, secret)
    }

    @Test
    fun testDeriveIV() {
        // Known vector: SN "2222267V4E" -> IV = 14cb6a3a39b96c448ebc39185f70f8aa
        val iv = SerialCrypto.deriveIv("2222267V4E")
        val expected = byteArrayOf(
            0x14, 0xcb.toByte(), 0x6a, 0x3a, 0x39, 0xb9.toByte(), 0x6c, 0x44,
            0x8e.toByte(), 0xbc.toByte(), 0x39, 0x18, 0x5f, 0x70, 0xf8.toByte(), 0xaa.toByte()
        )
        assertArrayEquals(expected, iv)
    }

    @Test
    fun testStripPrefix() {
        assertEquals("2222267V4E", SerialCrypto.stripPrefix("AiDEX X-2222267V4E"))
        assertEquals("2222267V4E", SerialCrypto.stripPrefix("X-2222267V4E"))
        assertEquals("2222267V4E", SerialCrypto.stripPrefix("2222267V4E"))
    }

    @Test
    fun testDeriveSecretAndIV_Length() {
        val secret = SerialCrypto.deriveSecret("22222689WH")
        val iv = SerialCrypto.deriveIv("22222689WH")
        assertEquals(16, secret.size)
        assertEquals(16, iv.size)
    }

    @Test
    fun testDeriveSecretDeterministic() {
        val s1 = SerialCrypto.deriveSecret("2222267V4E")
        val s2 = SerialCrypto.deriveSecret("2222267V4E")
        assertArrayEquals(s1, s2)
    }

    @Test
    fun testDifferentSNsDifferentKeys() {
        val s1 = SerialCrypto.deriveSecret("2222267V4E")
        val s2 = SerialCrypto.deriveSecret("22222689WH")
        assertFalse(s1.contentEquals(s2))
    }
}

// ============================================================================
// MARK: - AES-128-CFB Crypto Tests
// ============================================================================

class CryptoEngineTests {

    // NIST SP 800-38A F.3.13 AES-128-CFB test vector
    private val nistKey = byteArrayOf(
        0x2b, 0x7e, 0x15, 0x16, 0x28, 0xae.toByte(), 0xd2.toByte(), 0xa6.toByte(),
        0xab.toByte(), 0xf7.toByte(), 0x15, 0x88.toByte(), 0x09, 0xcf.toByte(), 0x4f, 0x3c
    )
    private val nistIV = byteArrayOf(
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
        0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f
    )
    private val nistPlaintext = byteArrayOf(
        0x6b, 0xc1.toByte(), 0xbe.toByte(), 0xe2.toByte(), 0x2e, 0x40, 0x9f.toByte(), 0x96.toByte(),
        0xe9.toByte(), 0x3d, 0x7e, 0x11, 0x73, 0x93.toByte(), 0x17, 0x2a
    )
    private val nistCiphertext = byteArrayOf(
        0x3b, 0x3f, 0xd9.toByte(), 0x2e, 0xb7.toByte(), 0x2d, 0xad.toByte(), 0x20,
        0x33, 0x34, 0x49, 0xf8.toByte(), 0xe8.toByte(), 0x3c, 0xfb.toByte(), 0x4a
    )

    @Test
    fun testNISTEncrypt() {
        val encrypted = AesCfb128.encrypt(nistPlaintext, nistKey, nistIV)
        assertNotNull(encrypted)
        assertArrayEquals(nistCiphertext, encrypted)
    }

    @Test
    fun testNISTDecrypt() {
        val decrypted = AesCfb128.decrypt(nistCiphertext, nistKey, nistIV)
        assertNotNull(decrypted)
        assertArrayEquals(nistPlaintext, decrypted)
    }

    @Test
    fun testNISTRoundTrip() {
        val encrypted = AesCfb128.encrypt(nistPlaintext, nistKey, nistIV)!!
        val decrypted = AesCfb128.decrypt(encrypted, nistKey, nistIV)
        assertArrayEquals(nistPlaintext, decrypted)
    }

    @Test
    fun testECBEncrypt_NIST() {
        // ECB(key, IV) XOR plaintext == ciphertext
        val ecb = AesCfb128.ecbEncrypt(nistIV, nistKey)
        assertNotNull(ecb)
        assertEquals(16, ecb!!.size)
        val xored = ByteArray(16) { i -> (ecb[i].toInt() xor nistPlaintext[i].toInt()).toByte() }
        assertArrayEquals(nistCiphertext, xored)
    }

    @Test
    fun testCustomKeyRoundTrip() {
        // Custom key from runSelfTest()
        val testKey = byteArrayOf(
            0xAC.toByte(), 0x4C, 0x8E.toByte(), 0xCD.toByte(), 0xD8.toByte(), 0x76, 0x1B, 0x51,
            0x2E, 0xEB.toByte(), 0x95.toByte(), 0xD7.toByte(), 0x07, 0x94.toByte(), 0x29, 0x12
        )
        val testIV = byteArrayOf(
            0x48, 0x1B, 0x33, 0x85.toByte(), 0x5D, 0xD7.toByte(), 0xB8.toByte(), 0xB8.toByte(),
            0xFB.toByte(), 0xDE.toByte(), 0x15, 0xDC.toByte(), 0xE1.toByte(), 0x11, 0x00, 0x0B
        )
        val testPlain = byteArrayOf(
            0x01, 0x00, 0x02, 0x00, 0x2B, 0x00, 0x3F, 0x84.toByte(),
            0x4F, 0x03, 0x86.toByte(), 0x0B, 0x43, 0x02, 0x00, 0x7D
        )

        val encrypted = AesCfb128.encrypt(testPlain, testKey, testIV)
        assertNotNull(encrypted)
        val decrypted = AesCfb128.decrypt(encrypted!!, testKey, testIV)
        assertArrayEquals(testPlain, decrypted)
    }

    @Test
    fun testDecryptWithWrongKey() {
        val wrongKey = ByteArray(16) { 0xFF.toByte() }
        val decrypted = AesCfb128.decrypt(nistCiphertext, wrongKey, nistIV)
        assertNotNull(decrypted) // decryption succeeds but produces wrong data
        assertFalse(nistPlaintext.contentEquals(decrypted))
    }

    @Test
    fun testMultiBlockRoundTrip() {
        // Test with 48 bytes (3 blocks)
        val multiBlock = ByteArray(48) { 0xAB.toByte() }
        val encrypted = AesCfb128.encrypt(multiBlock, nistKey, nistIV)
        assertNotNull(encrypted)
        assertEquals(48, encrypted!!.size)
        val decrypted = AesCfb128.decrypt(encrypted, nistKey, nistIV)
        assertArrayEquals(multiBlock, decrypted)
    }
}

// ============================================================================
// MARK: - F003 Data Frame Parsing Tests
// ============================================================================

class DataFrameParsingTests {

    @Test
    fun testParseDataFrame_RawrExample() {
        // From rawr.txt: 01 00 02 00 2B 00 3F 84 4F 03 86 0B 43 02 00 7D CE
        // glucose = bytes[6..7] LE with mask 0x03FF = 0x843F & 0x03FF = 63 mg/dL
        // i1 = bytes[8..9] LE = 0x034F / 100 = 8.47
        // i2 = bytes[10..11] LE = 0x0B86 / 100 = 29.50
        // CRC = bytes[15..16] LE = 0xCE7D
        val frame = byteArrayOf(
            0x01, 0x00, 0x02, 0x00, 0x2B, 0x00, 0x3F, 0x84.toByte(),
            0x4F, 0x03, 0x86.toByte(), 0x0B, 0x43, 0x02, 0x00, 0x7D, 0xCE.toByte()
        )

        val parsed = AiDexParser.parseDataFrame(frame)
        assertNotNull(parsed)

        assertEquals(0x01, parsed!!.opcode)
        // rawGlucosePacked = 0x843F, glucose = 0x843F & 0x03FF = 0x003F = 63
        assertEquals(0x843F, parsed.rawGlucosePacked)
        assertEquals(63.0f, parsed.glucoseMgDl, 0.01f)
        assertEquals(8.47f, parsed.i1, 0.01f)
        assertEquals(29.50f, parsed.i2, 0.01f)
        assertEquals(0xCE7D, parsed.crc16)
        assertTrue(parsed.isValid) // 63 is in range [20, 500]
    }

    @Test
    fun testParseDataFrame_WrongLength() {
        assertNull(AiDexParser.parseDataFrame(byteArrayOf(0x01, 0x02, 0x03)))
        assertNull(AiDexParser.parseDataFrame(ByteArray(16)))
        assertNull(AiDexParser.parseDataFrame(ByteArray(18)))
    }

    @Test
    fun testParseDataFrame_SentinelValue() {
        // Sentinel: rawGlucose = 1023 (0x03FF)
        val frame = ByteArray(17)
        frame[6] = 0xFF.toByte() // glucosePacked lo = 0xFF
        frame[7] = 0x03          // glucosePacked hi = 0x03 -> 0x03FF & 0x03FF = 1023
        val parsed = AiDexParser.parseDataFrame(frame)
        assertNotNull(parsed)
        assertFalse(parsed!!.isValid)
        assertEquals(1023.0f, parsed.glucoseMgDl, 0.01f) // sentinel, but isValid=false
    }

    @Test
    fun testParseDataFrame_HalfScaleOpcode() {
        // opcode 0xD2 -> scaling = 0.5
        val frame = ByteArray(17)
        frame[0] = 0xD2.toByte() // half-scale opcode
        frame[6] = 100            // rawGlucose = 100
        frame[7] = 0x00
        val parsed = AiDexParser.parseDataFrame(frame)
        assertNotNull(parsed)
        assertEquals(50.0f, parsed!!.glucoseMgDl, 0.01f)
        assertTrue(parsed.isValid)
    }

    @Test
    fun testParseDataFrame_DirectOpcode() {
        // opcode 0xA1 -> scaling = 1.0
        val frame = ByteArray(17)
        frame[0] = 0xA1.toByte()
        frame[6] = 80  // rawGlucose = 80
        frame[7] = 0x00
        val parsed = AiDexParser.parseDataFrame(frame)
        assertNotNull(parsed)
        assertEquals(80.0f, parsed!!.glucoseMgDl, 0.01f)
    }

    @Test
    fun testClassifyFrame() {
        assertEquals(AiDexParser.FrameType.DATA, AiDexParser.classifyFrame(ByteArray(17)))
        assertEquals(AiDexParser.FrameType.STATUS, AiDexParser.classifyFrame(ByteArray(5)))
        assertEquals(AiDexParser.FrameType.UNKNOWN, AiDexParser.classifyFrame(ByteArray(10)))
        assertEquals(AiDexParser.FrameType.UNKNOWN, AiDexParser.classifyFrame(byteArrayOf()))
    }
}

// ============================================================================
// MARK: - History Response Parsing Tests
// ============================================================================

class HistoryParsingTests {

    @Test
    fun testParseHistoryResponse_LiveData() {
        // From live log: decrypted response for GET_HISTORIES_RAW(14400)
        val payload = byteArrayOf(
            0x40, 0x38,             // startOffset = 14400
            0x42, 0x80.toByte(),    // entry 0: glucose=66
            0x42, 0x40,             // entry 1: glucose=66
            0x41, 0x80.toByte(),    // entry 2: glucose=65
            0x3F, 0x80.toByte(),    // entry 3: glucose=63
            0x3E, 0x80.toByte(),    // entry 4: glucose=62
            0x3D, 0x40,             // entry 5: glucose=61
            0x3E, 0x80.toByte(),    // entry 6: glucose=62
        )

        val records = AiDexParser.parseHistoryResponse(payload)
        assertEquals(7, records.size)

        // Verify first few entries match live log
        assertEquals(14400, records[0].timeOffsetMinutes)
        assertEquals(66, records[0].glucoseMgDl)
        assertFalse(records[0].isSentinel)

        assertEquals(14401, records[1].timeOffsetMinutes)
        assertEquals(66, records[1].glucoseMgDl)

        assertEquals(14402, records[2].timeOffsetMinutes)
        assertEquals(65, records[2].glucoseMgDl)

        assertEquals(14403, records[3].timeOffsetMinutes)
        assertEquals(63, records[3].glucoseMgDl)

        assertEquals(14404, records[4].timeOffsetMinutes)
        assertEquals(62, records[4].glucoseMgDl)

        assertEquals(14405, records[5].timeOffsetMinutes)
        assertEquals(61, records[5].glucoseMgDl)

        assertEquals(14406, records[6].timeOffsetMinutes)
        assertEquals(62, records[6].glucoseMgDl)
    }

    @Test
    fun testParseHistoryResponse_Sentinel() {
        // Entry with glucose = 1023 (sentinel)
        val payload = byteArrayOf(
            0x00, 0x00,         // startOffset = 0
            0xFF.toByte(), 0x03 // glucose = 0xFF | ((0x03 & 0x03) << 8) = 0x3FF = 1023
        )
        val records = AiDexParser.parseHistoryResponse(payload)
        assertEquals(1, records.size)
        assertTrue(records[0].isSentinel)
        assertEquals(1023, records[0].glucoseMgDl)
    }

    @Test
    fun testParseHistoryResponse_StatusBit() {
        // Test statusBit extraction: (b1 & 0x04) != 0
        val payload = byteArrayOf(
            0x00, 0x00,
            0x42, 0x84.toByte(), // 0x84 & 0x04 = 0x04 -> statusBit = true
            0x42, 0x80.toByte(), // 0x80 & 0x04 = 0x00 -> statusBit = false
        )
        val records = AiDexParser.parseHistoryResponse(payload)
        assertEquals(2, records.size)
        assertTrue(records[0].statusBit)
        assertFalse(records[1].statusBit)
    }

    @Test
    fun testParseHistoryResponse_TooShort() {
        assertEquals(0, AiDexParser.parseHistoryResponse(byteArrayOf()).size)
        assertEquals(0, AiDexParser.parseHistoryResponse(byteArrayOf(0x00)).size)
        assertEquals(0, AiDexParser.parseHistoryResponse(byteArrayOf(0x00, 0x00, 0x00)).size)
    }

    @Test
    fun testParseHistoryResponse_OddBodyBytes() {
        // Body of 3 bytes -> 1 full row (2 bytes) + 1 leftover (discarded)
        val payload = byteArrayOf(0x00, 0x00, 0x42, 0x80.toByte(), 0xFF.toByte())
        val records = AiDexParser.parseHistoryResponse(payload)
        assertEquals(1, records.size)
    }
}

// ============================================================================
// MARK: - Calibration Response Parsing Tests
// ============================================================================

class CalibrationParsingTests {

    @Test
    fun testParseCalibrationResponse_Valid() {
        // Synthetic calibration record:
        // startIndex = 1 (u16 LE)
        // Row: timeOffset=100, refGlucose=120, cf=150(=1.50), offset=50(=0.50)
        val payload = byteArrayOf(
            0x01, 0x00,     // startIndex = 1
            0x64, 0x00,     // timeOffset = 100
            0x78, 0x00,     // refGlucose = 120
            0x96.toByte(), 0x00, // cf = 150
            0x32, 0x00,     // offset = 50
        )
        val records = AiDexParser.parseCalibrationResponse(payload)
        assertEquals(1, records.size)
        assertEquals(100, records[0].timeOffsetMinutes)
        assertEquals(120, records[0].referenceGlucoseMgDl)
        assertEquals(1.50f, records[0].calibrationFactor, 0.01f)
        assertEquals(0.50f, records[0].calibrationOffset, 0.01f)
    }

    @Test
    fun testParseCalibrationResponse_TooShort() {
        assertEquals(0, AiDexParser.parseCalibrationResponse(byteArrayOf()).size)
        assertEquals(0, AiDexParser.parseCalibrationResponse(ByteArray(9)).size)
    }

    @Test
    fun testParseCalibrationResponse_ImplausibleStartIndex() {
        // startIndex > 10000 should be rejected
        val payload = ByteArray(10)
        payload[0] = 0x12           // 0x2712 = 10002
        payload[1] = 0x27
        assertEquals(0, AiDexParser.parseCalibrationResponse(payload).size)
    }

    @Test
    fun testParseCalibrationResponse_BodyNotMultipleOf8() {
        // Body of 7 bytes (not multiple of 8) should be rejected
        val payload = ByteArray(9) // 2 header + 7 body
        assertEquals(0, AiDexParser.parseCalibrationResponse(payload).size)
    }
}

// ============================================================================
// MARK: - Opcode Scaling Tests
// ============================================================================

class OpcodeScalingTests {

    @Test
    fun testDirectOpcodes() {
        for (opcode in listOf(0xA1, 0xA4, 0x5B, 0xD7)) {
            assertEquals(
                "Direct opcode 0x${opcode.toString(16)} should have scaling 1.0",
                1.0f, AiDexOpcodes.scalingFactor(opcode)!!
            )
        }
    }

    @Test
    fun testHalfScaleOpcodes() {
        assertEquals(0.5f, AiDexOpcodes.scalingFactor(0xD2)!!)
    }

    @Test
    fun testUnknownOpcodes() {
        assertNull(AiDexOpcodes.scalingFactor(0x00))
        assertNull(AiDexOpcodes.scalingFactor(0x01))
        assertNull(AiDexOpcodes.scalingFactor(0xFF))
    }
}

// ============================================================================
// MARK: - Hex Conversion Utility Tests
// ============================================================================

class HexUtilityTests {

    @Test
    fun testHexString() {
        val data = byteArrayOf(0x01, 0xAB.toByte(), 0xFF.toByte(), 0x00)
        assertEquals("01 AB FF 00", AiDexParser.hexString(data))
    }

    @Test
    fun testCompactHex() {
        val data = byteArrayOf(0x01, 0xAB.toByte(), 0xFF.toByte(), 0x00)
        assertEquals("01ABFF00", AiDexParser.compactHex(data))
    }

    @Test
    fun testDataFromHex() {
        val hex = "01 AB FF 00"
        val data = AiDexParser.dataFromHex(hex)
        assertArrayEquals(byteArrayOf(0x01, 0xAB.toByte(), 0xFF.toByte(), 0x00), data)
    }

    @Test
    fun testDataFromHex_Compact() {
        val hex = "01ABFF00"
        val data = AiDexParser.dataFromHex(hex)
        assertArrayEquals(byteArrayOf(0x01, 0xAB.toByte(), 0xFF.toByte(), 0x00), data)
    }

    @Test
    fun testHexRoundTrip() {
        val original = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val hex = AiDexParser.compactHex(original)
        val restored = AiDexParser.dataFromHex(hex)
        assertArrayEquals(original, restored)
    }
}

// ============================================================================
// MARK: - Frame Classification Tests
// ============================================================================

class FrameClassificationTests {

    @Test
    fun testHandshakeEcho() {
        val sent = byteArrayOf(0x6F, 0x01, 0x02)
        val received = byteArrayOf(0x6F, 0xAA.toByte(), 0xBB.toByte())
        assertTrue(AiDexParser.isHandshakeEcho(sent, received))
    }

    @Test
    fun testHandshakeEcho_Mismatch() {
        val sent = byteArrayOf(0x6F, 0x01, 0x02)
        val received = byteArrayOf(0x70, 0xAA.toByte(), 0xBB.toByte())
        assertFalse(AiDexParser.isHandshakeEcho(sent, received))
    }

    @Test
    fun testHandshakeEcho_Empty() {
        assertFalse(AiDexParser.isHandshakeEcho(byteArrayOf(), byteArrayOf()))
    }
}

// ============================================================================
// MARK: - Advertisement Parsing Tests
// ============================================================================

class AdvertisementTests {

    @Test
    fun testIsAiDexDevice_ValidNames() {
        assertTrue(AiDexOpcodes.isAiDexDevice("AiDEX X-2222267V4E"))
        assertTrue(AiDexOpcodes.isAiDexDevice("AiDEX X-22222689WH"))
    }

    @Test
    fun testIsAiDexDevice_InvalidNames() {
        assertFalse(AiDexOpcodes.isAiDexDevice("Dexcom G7"))
        assertFalse(AiDexOpcodes.isAiDexDevice(null))
        assertFalse(AiDexOpcodes.isAiDexDevice(""))
    }
}

// ============================================================================
// MARK: - Status Frame Parsing Tests
// ============================================================================

class StatusFrameTests {

    @Test
    fun testParseStatusFrame_Valid() {
        val data = byteArrayOf(0xAA.toByte(), 0x01, 0x02, 0x03, 0x04)
        val frame = AiDexParser.parseStatusFrame(data)
        assertNotNull(frame)
        assertEquals(0xAA, frame!!.header)
    }

    @Test
    fun testParseStatusFrame_WrongLength() {
        assertNull(AiDexParser.parseStatusFrame(byteArrayOf(0x01, 0x02)))
        assertNull(AiDexParser.parseStatusFrame(ByteArray(17)))
    }
}

// ============================================================================
// MARK: - Live Sensor Encrypt/Decrypt Integration Tests
// ============================================================================

class LiveSensorCryptoTests {

    @Test
    fun testCRC16_OnDecryptedResponse_14400() {
        val decryptedHex = "23 01 40 38 42 80 42 40 41 80 3F 80 3E 80 3D 40 3E 80 43 80 46 80 48 40 49 80 49 80 49 80 48 40 46 80 44 80 43 80 42 40 41 80 40 80 3F 80 3D 40 3D 80 3C 80 3C 80 3D 40 3D 80 3E 80 3E 80 3E 40 3E 80 3E 80 3E 80 3E 40 3D 80 3D 80 3C 80 3C 40 3C 80 3D 80 3E 80 3F 40 3F 80 3E 80 3D 80 3C 40 3B 80 3B 80 3B 80 3B 40 3B 80 3B 80 3B 80 3C 40 3C 80 3C 80 3C 80 3C 40 3C 80 3C 80 3C 80 3B 40 3B 80 3B 80 3C 80 3C 40 3D 80 3D 80 3D 80 3D 00 3D 80 3E 80 41 80 45 40 47 80 46 80 44 80 45 40 45 80 45 80 44 80 43 40 43 80 41 80 3F 80 3E 40 3E 80 3E 80 3E 80 3E 40 3F 80 3F 80 3F 80 3F 40 3F 80 3F 80 3F 80 3F 40 3F 80 3F 80 3E 80 3E 40 3E 80 3D 80 3C 80 3B 40 3B 80 3B 80 3B 80 3B 40 3B 80 3B 80 3C 80 3C 40 3C 80 3C 80 3C 80 3D 40 3F 80 8B 7D"
        val data = AiDexParser.dataFromHex(decryptedHex)
        assertEquals(244, data.size)
        assertTrue(Crc16CcittFalse.validateResponse(data))
    }

    @Test
    fun testCRC16_OnDecryptedResponse_14519() {
        val decryptedHex = "23 01 B7 38 41 80 41 80 41 40 41 80 40 80 3E 80 3D 40 3D 80 3D 80 3E 80 3E 40 3F 80 3E 80 3F 80 3F 00 3F 80 40 80 40 80 41 00 41 80 41 80 40 80 3F 00 3F 80 3E 80 3D 80 3D 00 3C 80 3C 80 3D 80 3D 00 3C 80 3B 80 3B 80 3B 40 3D 80 3D 80 3F 80 3F 40 3E 80 3D 80 3C 80 3B 40 3B 80 3A 80 3A 80 3A 40 39 80 3A 80 3A 80 3A 40 3A 80 3A 80 3A 80 39 40 39 80 39 80 39 80 39 40 3A 80 3C 80 3E 80 43 40 45 80 45 80 44 80 43 40 42 80 41 80 41 80 41 40 43 80 44 80 44 80 44 40 44 80 44 80 43 80 43 40 46 80 4A 80 4C 80 4D 40 4C 80 50 80 56 80 5C 40 60 80 61 80 62 80 62 40 61 80 60 80 5E 80 5B 40 59 80 57 80 55 80 54 40 54 80 53 80 53 80 53 40 52 80 52 80 50 80 4E 40 4C 80 4B 80 4B 80 4B 40 4C 80 4B 80 4A 80 49 40 48 80 47 80 46 80 45 40 F6 CE"
        val data = AiDexParser.dataFromHex(decryptedHex)
        assertEquals(244, data.size)
        assertTrue(Crc16CcittFalse.validateResponse(data))
    }

    @Test
    fun testCRC16_OnDecryptedResponse_14638() {
        val decryptedHex = "23 01 2E 39 42 80 40 80 40 80 41 40 42 80 43 80 42 80 41 40 40 80 40 80 40 80 41 40 42 80 42 80 42 80 44 40 45 80 44 80 44 80 42 40 40 80 3E 80 3E 80 3E 40 3E 80 3F 80 3F 80 3F 40 3F 80 40 80 40 80 40 40 41 80 41 80 41 80 41 40 42 80 44 80 46 80 47 40 47 80 46 80 45 80 45 40 44 80 43 80 43 80 42 40 43 80 43 80 44 80 44 40 45 80 44 80 45 80 47 40 48 80 48 80 47 80 45 40 42 80 3F 80 3D 80 3C 40 3C 80 3D 80 3E 80 3F 40 40 80 41 80 43 80 45 40 48 80 4D 80 52 80 54 40 58 80 5A 80 59 80 57 40 55 80 56 80 5A 80 5E 40 60 80 60 80 5F 80 5F 40 5E 80 5D 80 57 80 4E 40 47 80 45 80 44 80 45 40 45 80 46 80 47 80 49 40 4B 80 4E 80 4F 80 51 40 51 80 52 80 50 80 4E 40 4B 80 48 80 44 80 42 40 3F 80 3E 80 3E 80 3E 40 3F 80 40 80 42 80 C0 95"
        val data = AiDexParser.dataFromHex(decryptedHex)
        assertEquals(244, data.size)
        assertTrue(Crc16CcittFalse.validateResponse(data))
    }

    @Test
    fun testHistoryParsing_FromDecryptedResponse_14400() {
        // Parse the payload portion (skip opcode+status at start, CRC at end)
        val decryptedHex = "23 01 40 38 42 80 42 40 41 80 3F 80 3E 80 3D 40 3E 80 43 80 46 80 48 40 49 80 49 80 49 80 48 40 46 80 44 80 43 80 42 40 41 80 40 80 3F 80 3D 40 3D 80 3C 80 3C 80 3D 40 3D 80 3E 80 3E 80 3E 40 3E 80 3E 80 3E 80 3E 40 3D 80 3D 80 3C 80 3C 40 3C 80 3D 80 3E 80 3F 40 3F 80 3E 80 3D 80 3C 40 3B 80 3B 80 3B 80 3B 40 3B 80 3B 80 3B 80 3C 40 3C 80 3C 80 3C 80 3C 40 3C 80 3C 80 3C 80 3B 40 3B 80 3B 80 3C 80 3C 40 3D 80 3D 80 3D 80 3D 00 3D 80 3E 80 41 80 45 40 47 80 46 80 44 80 45 40 45 80 45 80 44 80 43 40 43 80 41 80 3F 80 3E 40 3E 80 3E 80 3E 80 3E 40 3F 80 3F 80 3F 80 3F 40 3F 80 3F 80 3F 80 3F 40 3F 80 3F 80 3E 80 3E 40 3E 80 3D 80 3C 80 3B 40 3B 80 3B 80 3B 80 3B 40 3B 80 3B 80 3C 80 3C 40 3C 80 3C 80 3C 80 3D 40 3F 80 8B 7D"
        val fullData = AiDexParser.dataFromHex(decryptedHex)

        // Payload = bytes[2..<242] (skip opcode+status at start, CRC at end)
        val payload = fullData.copyOfRange(2, fullData.size - 2)
        val records = AiDexParser.parseHistoryResponse(payload)

        // 238 body bytes / 2 = 119 entries (minus 2 byte header = 240-2=238)
        // Actually: payload is 240 bytes (244-2-2=240), first 2 bytes header, body is 238 / 2 = 119
        assertEquals(119, records.size)

        // Verify against logged values
        assertEquals(66, records[0].glucoseMgDl) // 0x42
        assertEquals(14400, records[0].timeOffsetMinutes)

        assertEquals(66, records[1].glucoseMgDl) // 0x42
        assertEquals(65, records[2].glucoseMgDl) // 0x41
        assertEquals(63, records[3].glucoseMgDl) // 0x3F
        assertEquals(62, records[4].glucoseMgDl) // 0x3E

        // Last entry: 0x3F = 63
        assertEquals(63, records[118].glucoseMgDl)
        assertEquals(14518, records[118].timeOffsetMinutes)
    }
}

// ============================================================================
// MARK: - Key Exchange Tests
// ============================================================================

class KeyExchangeTests {

    @Test
    fun testKeyExchangeInit() {
        val ke = AiDexKeyExchange("2222267V4E")

        // Secret should match known vector
        val expectedSecret = byteArrayOf(
            0x4b, 0x76, 0x16, 0x95.toByte(), 0x76, 0xda.toByte(), 0x80.toByte(), 0xe4.toByte(),
            0xee.toByte(), 0xac.toByte(), 0xf8.toByte(), 0x86.toByte(), 0x23, 0x08, 0x73, 0xd2.toByte()
        )
        assertArrayEquals(expectedSecret, ke.snSecret)

        // IV should match known vector
        val expectedIv = byteArrayOf(
            0x14, 0xcb.toByte(), 0x6a, 0x3a, 0x39, 0xb9.toByte(), 0x6c, 0x44,
            0x8e.toByte(), 0xbc.toByte(), 0x39, 0x18, 0x5f, 0x70, 0xf8.toByte(), 0xaa.toByte()
        )
        assertArrayEquals(expectedIv, ke.snIv)

        // Initial state
        assertFalse(ke.isComplete)
        assertNull(ke.pairKey)
        assertNull(ke.sessionKey)
    }

    @Test
    fun testKeyExchangeChallenge() {
        val ke = AiDexKeyExchange("2222267V4E")
        val challenge = ke.getChallenge()
        assertArrayEquals(ke.snSecret, challenge)
    }

    @Test
    fun testKeyExchangeReset() {
        val ke = AiDexKeyExchange("2222267V4E")
        ke.onPairKeyReceived(ByteArray(16) { 0x42 })
        assertNotNull(ke.pairKey)
        ke.reset()
        assertNull(ke.pairKey)
        assertNull(ke.sessionKey)
        assertFalse(ke.isComplete)
    }

    @Test
    fun testKeyExchangeEncryptDecryptFailsWithoutSessionKey() {
        val ke = AiDexKeyExchange("2222267V4E")
        assertNull(ke.encrypt(byteArrayOf(0x10, 0xC1.toByte(), 0xF3.toByte())))
        assertNull(ke.decrypt(byteArrayOf(0x10, 0xC1.toByte(), 0xF3.toByte())))
    }

    @Test
    fun testPostBondConfigFailsWithoutSessionKey() {
        val ke = AiDexKeyExchange("2222267V4E")
        assertNull(ke.getPostBondConfig())
    }
}

// ============================================================================
// MARK: - Command Builder Tests
// ============================================================================

class CommandBuilderTests {

    @Test
    fun testCommandBuilderFailsWithoutSessionKey() {
        val ke = AiDexKeyExchange("2222267V4E")
        val builder = AiDexCommandBuilder(ke)

        assertNull(builder.getDeviceInfo())
        assertNull(builder.getBroadcastData())
        assertNull(builder.getHistoryRange())
        assertNull(builder.getHistoriesRaw(14400))
        assertNull(builder.getHistories(14400))
        assertNull(builder.deleteBond())
        assertNull(builder.reset())
    }
}

// ============================================================================
// MARK: - Brief History (0x24) Parsing Tests
// ============================================================================

class BriefHistoryParsingTests {

    @Test
    fun testParseBriefHistory_Synthetic() {
        // 2 bytes header + 1 x 5-byte entry
        // i1_raw = 847 (8.47 -> rawValue=84.7, sensorGlucose=8.47*18.0182=152.6)
        // i2_raw = 2950 (29.50)
        // vc_raw = 50 (0.50)
        val payload = byteArrayOf(
            0x00, 0x00, // startOffset = 0
            0x4F, 0x03, // i1 = 0x034F = 847
            0x86.toByte(), 0x0B, // i2 = 0x0B86 = 2950
            0x32,       // vc = 50
        )
        val records = AiDexParser.parseBriefHistoryResponse(payload)
        assertEquals(1, records.size)

        val r = records[0]
        assertEquals(0, r.timeOffsetMinutes)
        assertEquals(8.47f, r.i1, 0.01f)
        assertEquals(29.50f, r.i2, 0.01f)
        assertEquals(0.50f, r.vc, 0.01f)
        assertEquals(84.7f, r.rawValue, 0.1f)
        assertEquals(8.47f * 18.0182f, r.sensorGlucose, 0.1f)
    }

    @Test
    fun testParseBriefHistory_InvalidEntry() {
        // All-zero entry should be skipped
        val payload = byteArrayOf(
            0x00, 0x00, // startOffset = 0
            0x00, 0x00, // i1 = 0
            0x00, 0x00, // i2 = 0
            0x00,       // vc = 0
        )
        val records = AiDexParser.parseBriefHistoryResponse(payload)
        assertEquals(0, records.size) // invalid entry filtered out
    }

    @Test
    fun testParseBriefHistory_TooShort() {
        assertEquals(0, AiDexParser.parseBriefHistoryResponse(byteArrayOf()).size)
        assertEquals(0, AiDexParser.parseBriefHistoryResponse(byteArrayOf(0x00)).size)
        assertEquals(0, AiDexParser.parseBriefHistoryResponse(ByteArray(6)).size) // 2+4 < 2+5
    }
}
