package ir.mahditavakoli.mia.security

import android.util.Base64
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Box
import ir.mahditavakoli.mia.data.repository.Base64Encoder
import ir.mahditavakoli.mia.data.repository.SecretEncryptor

/** Production [Base64Encoder] backed by `android.util.Base64` (no line wrapping). */
val AndroidBase64Encoder = Base64Encoder { bytes -> Base64.encodeToString(bytes, Base64.NO_WRAP) }

/**
 * Production [SecretEncryptor] that seals a value the way the GitHub Actions secrets API
 * expects: a libsodium sealed box (`crypto_box_seal`) against the repo's base64 Curve25519
 * public key, base64-encoded.
 */
object LibsodiumSecretEncryptor : SecretEncryptor {

    private val sodium = LazySodiumAndroid(SodiumAndroid())

    override fun seal(plaintext: String, publicKeyBase64: String): String {
        val publicKey = Base64.decode(publicKeyBase64, Base64.DEFAULT)
        val message = plaintext.toByteArray(Charsets.UTF_8)
        val cipher = ByteArray(message.size + Box.SEALBYTES)
        val sealed = (sodium as Box.Native)
            .cryptoBoxSeal(cipher, message, message.size.toLong(), publicKey)
        check(sealed) { "libsodium sealed-box encryption failed" }
        return Base64.encodeToString(cipher, Base64.NO_WRAP)
    }
}
