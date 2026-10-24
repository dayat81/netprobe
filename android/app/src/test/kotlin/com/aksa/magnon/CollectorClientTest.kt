package com.aksa.magnon

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CollectorClientTest {
    @Test
    fun hmacMatchesRfc4231() {
        val key = "Jefe".toByteArray(StandardCharsets.UTF_8)
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            CollectorClient.hmacHex(key, "what do ya want for nothing?"),
        )
    }

    @Test
    fun canonicalStringMatchesCollector() {
        val body = "{}".toByteArray(StandardCharsets.UTF_8)
        assertEquals(
            "POST\n/v1/measurements\n100\nnonce\n" + CollectorClient.sha256Hex(body),
            CollectorClient.canonical("/v1/measurements", "100", "nonce", body),
        )
    }

    @Test
    fun envelopeDecryptsWithCollectorAad() {
        val key = ByteArray(32) { it.toByte() }
        val iv = ByteArray(12) { (it + 1).toByte() }
        val plain = """{"snapshots":[],"tests":[]}""".toByteArray(StandardCharsets.UTF_8)
        val envelope = CollectorClient.measurementEnvelope("device1234", "batch12345", 42L, key, iv, plain)
        assertEquals(
            setOf("schema_version", "device_id", "batch_id", "sent_at_ms", "alg", "iv_b64", "ciphertext_b64"),
            envelope.keys().asSequence().toSet(),
        )
        assertEquals("AES-256-GCM", envelope.getString("alg"))

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD("device1234\nbatch12345\n42".toByteArray(StandardCharsets.UTF_8))
        val decrypted = cipher.doFinal(Base64.getDecoder().decode(envelope.getString("ciphertext_b64")))
        assertArrayEquals(plain, decrypted)
        assertArrayEquals(iv, Base64.getDecoder().decode(envelope.getString("iv_b64")))
    }
}
