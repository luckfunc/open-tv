package app.opentv.launcher.setup.adb

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import app.opentv.launcher.setup.AdbFailure
import app.opentv.launcher.setup.AdbFailureReason
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher

internal class AdbIdentity(private val key: PrivateKey) {
    fun sign(token: ByteArray): ByteArray {
        require(token.size == 20)
        // AUTH supplies a SHA-1 digest, not a message to hash again.
        val digestInfo = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
        return Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.ENCRYPT_MODE, key)
            doFinal(digestInfo + token)
        }
    }

    fun publicWire(): ByteArray {
        val rsa = key as RSAPrivateCrtKey
        val n = rsa.modulus
        val word = BigInteger.ONE.shiftLeft(32)
        val n0inv = word.subtract(n.mod(word).modInverse(word)).mod(word)
        return ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(64).putInt(n0inv.toInt())
            .put(littleEndian(n)).put(littleEndian(BigInteger.ONE.shiftLeft(4096).mod(n)))
            .putInt(rsa.publicExponent.toInt()).array()
    }

    fun publicPayload(): ByteArray =
        (Base64.encodeToString(publicWire(), Base64.NO_WRAP) + " OpenTV\u0000").toByteArray(Charsets.US_ASCII)

    private fun littleEndian(value: BigInteger): ByteArray {
        val bytes = value.toByteArray().reversedArray()
        return ByteArray(256) { bytes.getOrElse(it) { 0 } }
    }

    companion object {
        private val storageLock = Any()

        fun load(context: Context): AdbIdentity = synchronized(storageLock) {
            try {
                val file = java.io.File(context.noBackupFilesDir, "opentv-adb-identity.pk8")
                val atomic = AtomicFile(file)
                val encoded = if (file.exists() || java.io.File(file.path + ".bak").exists()) {
                    atomic.openRead().use { input ->
                        val bytes = ByteArray(8193)
                        var count = 0
                        while (count < bytes.size) {
                            val n = input.read(bytes, count, bytes.size - count)
                            if (n < 0) break
                            count += n
                        }
                        require(count in 1..8192)
                        bytes.copyOf(count)
                    }
                } else {
                    val generated = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private.encoded
                    val output = atomic.startWrite()
                    try {
                        android.system.Os.fchmod(output.fd, 384)
                        output.write(generated)
                        atomic.finishWrite(output)
                    } catch (failure: Exception) {
                        atomic.failWrite(output)
                        throw failure
                    }
                    generated
                }
                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(encoded)) as RSAPrivateCrtKey
                require(key.modulus.bitLength() == 2048 && key.publicExponent == BigInteger.valueOf(65537))
                AdbIdentity(key)
            } catch (failure: Exception) {
                throw AdbFailure(AdbFailureReason.IdentityUnavailable)
            }
        }
    }
}
