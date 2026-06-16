package tk.glucodata.drivers.ottai

import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline tests for Ottai BLE Auth V2 crypto. Proves the mechanics (ECDH
 * symmetry, session-key char-pick, signature construction, time3 reorder) are
 * self-consistent. Validation against a real device is Phase 3.
 */
class OttaiBleAuthTests {

    @Test
    fun sessionKeyPick_followsPos2Then1or3Pattern() {
        // sharedHex[p] = hexdigit(p % 16). Picks at positions 2,3,6,7,10,11,...
        // -> digits 2,3,6,7,a,b,e,f repeating.
        val sharedHex = (0 until 64).joinToString("") { (it % 16).toString(16) }
        val key = OttaiBleAuth.sessionKeyPick(sharedHex)
        assertNotNull(key)
        assertEquals("2367abef2367abef2367abef2367abef", key)
        assertTrue(key!!.length >= 32)
    }

    @Test
    fun sessionKeyPick_tooShortReturnsNull() {
        assertEquals(null, OttaiBleAuth.sessionKeyPick("00"))
    }

    @Test
    fun ecdh_isSymmetric_andYieldsStableSessionKey() {
        val a = OttaiBleAuth.generateKeyPair()
        val b = OttaiBleAuth.generateKeyPair()
        val aPub = a.public as ECPublicKey
        val bPub = b.public as ECPublicKey

        val keyFromA = OttaiBleAuth.deriveSessionKey(bPub.w.affineX, bPub.w.affineY, a.private as ECPrivateKey)
        val keyFromB = OttaiBleAuth.deriveSessionKey(aPub.w.affineX, aPub.w.affineY, b.private as ECPrivateKey)

        assertNotNull(keyFromA)
        assertNotNull(keyFromB)
        assertEquals(keyFromA, keyFromB) // ECDH shared X identical both directions
        assertTrue(keyFromA!!.length >= 32)
    }

    @Test
    fun bytesIntLE_roundTrip() {
        for (v in listOf(0, 1, 255, 256, 0x010203, 0x7FFFFFFF, -1)) {
            assertEquals(v, OttaiBleAuth.bytesToIntLE(OttaiBleAuth.intToBytesLE(v)))
        }
        // explicit little-endian layout
        assertEquals(1, OttaiBleAuth.bytesToIntLE(byteArrayOf(1, 0, 0, 0)))
        assertEquals(0x04030201, OttaiBleAuth.bytesToIntLE(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun appTime3_incrementsAndReorders() {
        // deviceTime LE32 = 1 -> inc = 2 -> low3 = [2,0,0] -> time3 = [b2,b0,b1] = [0,2,0]
        val t3 = OttaiBleAuth.appTime3(byteArrayOf(1, 0, 0, 0))
        assertEquals(3, t3.size)
        assertArrayEq(byteArrayOf(0, 2, 0), t3)

        // deviceTime LE32 = 0x0000FFFF=65535 -> inc=65536=0x010000 -> low3=[00,00,01] -> [b2,b0,b1]=[01,00,00]
        val t3b = OttaiBleAuth.appTime3(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0, 0))
        assertArrayEq(byteArrayOf(1, 0, 0), t3b)
    }

    @Test
    fun appAuthParameter_layoutAndLength() {
        val time3 = byteArrayOf(1, 2, 3)
        val x = ByteArray(32) { 0x11 }
        val y = ByteArray(32) { 0x22 }
        val p = OttaiBleAuth.appAuthParameter(5, time3, x, y)
        assertEquals(1 + 3 + 32 + 32, p.size)
        assertEquals(5.toByte(), p[0])
        assertArrayEq(time3, p.copyOfRange(1, 4))
        assertArrayEq(x, p.copyOfRange(4, 36))
        assertArrayEq(y, p.copyOfRange(36, 68))
    }

    @Test
    fun authSign_matchesIndependentSha256_andVerifies() {
        val authKeyHex = "00112233445566778899aabbccddeeff"
        val macHex = "00be44708301"
        val x = ByteArray(32) { (it + 1).toByte() }
        val y = ByteArray(32) { (it + 2).toByte() }
        val t3 = byteArrayOf(0x0a, 0x0b, 0x0c)

        val sign = OttaiBleAuth.authSignHex(authKeyHex, macHex, x, y, t3)
        // independent recomputation
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(OttaiCrypto.hexToBytes(authKeyHex))
        md.update(OttaiCrypto.hexToBytes(macHex))
        md.update(x); md.update(y); md.update(t3)
        assertArrayEq(md.digest(), sign)

        // verifyDeviceSign treats the same inputs as the device side
        assertTrue(OttaiBleAuth.verifyDeviceSign(sign, authKeyHex, macHex, x, y, t3))
        val tampered = sign.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFalse(OttaiBleAuth.verifyDeviceSign(tampered, authKeyHex, macHex, x, y, t3))
    }

    @Test
    fun payload_encryptDecrypt_roundTripsWithTrailingZeroBlockTrim() {
        val sessionKey = "0123456789abcdef0123456789abcdef" // 32 hex = 16-byte AES key
        // 8-byte payload -> zero-padded to 16 (8 data + 8 zero) -> on decrypt the
        // trailing 8x00 block is dropped, recovering the original 8 bytes.
        val plain = byteArrayOf(0x40, 0x05, 0x00, 0x6D, 0x01, 0x32, 0x11, 0x22)
        val cipher = OttaiCrypto.encryptPayload(plain, sessionKey)
        assertNotNull(cipher)
        assertEquals(0, cipher!!.size % 16)
        val back = OttaiCrypto.decryptPayload(cipher, sessionKey)
        assertNotNull(back)
        assertArrayEq(plain, back!!)
    }

    @Test
    fun activateCmd_encryptsTo16Bytes_matchingZeroPadEncrypt() {
        val sessionKey = "0123456789abcdef0123456789abcdef"
        val enc = OttaiCrypto.encryptActivateCmd(byteArrayOf(0x03), sessionKey)
        assertNotNull(enc)
        assertEquals(16, enc!!.size) // {0x03} zero-padded to one AES block
        // encryptActivateCmd is exactly p.U(pad16(cmd), key) == encryptPayload here
        assertArrayEq(OttaiCrypto.encryptPayload(byteArrayOf(0x03), sessionKey)!!, enc)
    }

    private fun assertArrayEq(expected: ByteArray, actual: ByteArray) {
        assertEquals(OttaiCrypto.bytesToHex(expected), OttaiCrypto.bytesToHex(actual))
    }
}
