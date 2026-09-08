/*
 * Repo-committed secret storage.
 *
 * The real secrets live in `secrets.enc` -- AES-256-CBC, committed to the repo. The
 * passphrase that opens it never is: it comes from `~/.gradle/gradle.properties`
 * (miaSecretsPassphrase) or the MIA_SECRETS_PASSPHRASE env var, so it is set once per
 * machine and survives every clone. Values that are public by design (Supabase URL and
 * publishable key) sit in plain `secrets.public.properties` instead.
 *
 * Resolution order for a key, highest first:
 *   1. local.properties      -- per-developer override, gitignored
 *   2. environment variable  -- CI
 *   3. secrets.enc           -- shared encrypted defaults
 *   4. secrets.public.properties
 *   5. "" (empty; the build still succeeds, the app degrades gracefully)
 *
 * Tasks: ./gradlew secretsDecrypt | secretsEncrypt | secretsStatus
 */

import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// OpenSSL-compatible envelope, so `openssl enc -d -aes-256-cbc -pbkdf2 -iter 100000
// -md sha256 -a -in secrets.enc` decrypts these files too -- no lock-in on this build.
val SALT_MAGIC = "Salted__".toByteArray(Charsets.US_ASCII)
val PBKDF2_ITERATIONS = 100_000
val ENCRYPTED_KEYS = listOf(
    "GITHUB_TOKEN",
    "GEMINI_API_KEY",
    "OPENROUTER_API_KEY",
    "OPENROUTER_FALLBACK_API_KEY",
    "MINIMAX_API_KEY",
)

val encFile = rootProject.file("secrets.enc")
val publicFile = rootProject.file("secrets.public.properties")
val plainFile = rootProject.file("secrets.local.properties")
val localPropsFile = rootProject.file("local.properties")

fun deriveKeyAndIv(passphrase: String, salt: ByteArray): Pair<SecretKeySpec, IvParameterSpec> {
    val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, (32 + 16) * 8)
    val material = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    return SecretKeySpec(material.copyOfRange(0, 32), "AES") to
        IvParameterSpec(material.copyOfRange(32, 48))
}

fun encryptToArmor(plaintext: String, passphrase: String): String {
    val salt = ByteArray(8).also { java.security.SecureRandom().nextBytes(it) }
    val (key, iv) = deriveKeyAndIv(passphrase, salt)
    val body = Cipher.getInstance("AES/CBC/PKCS5Padding")
        .apply { init(Cipher.ENCRYPT_MODE, key, iv) }
        .doFinal(plaintext.toByteArray(Charsets.UTF_8))
    val armored = java.util.Base64.getMimeEncoder(64, "\n".toByteArray())
        .encodeToString(SALT_MAGIC + salt + body)
    return armored + "\n"
}

fun decryptFromArmor(armor: String, passphrase: String): String {
    val raw = java.util.Base64.getMimeDecoder().decode(armor)
    require(raw.size > 16 && raw.copyOfRange(0, 8).contentEquals(SALT_MAGIC)) {
        "secrets.enc is not a salted OpenSSL envelope"
    }
    val (key, iv) = deriveKeyAndIv(passphrase, raw.copyOfRange(8, 16))
    return String(
        Cipher.getInstance("AES/CBC/PKCS5Padding")
            .apply { init(Cipher.DECRYPT_MODE, key, iv) }
            .doFinal(raw.copyOfRange(16, raw.size)),
        Charsets.UTF_8,
    )
}

fun readProps(file: File): Properties = Properties().apply {
    if (file.exists()) file.inputStream().use { load(it) }
}

fun passphraseOrNull(): String? =
    (rootProject.findProperty("miaSecretsPassphrase") as String?)
        ?.takeIf { it.isNotBlank() }
        ?: System.getenv("MIA_SECRETS_PASSPHRASE")?.takeIf { it.isNotBlank() }

/** Decrypted contents of secrets.enc, or empty when no passphrase / no file. */
fun encryptedProps(): Properties {
    if (!encFile.exists()) return Properties()
    val passphrase = passphraseOrNull() ?: run {
        logger.warn(
            "MIA: secrets.enc found but no passphrase. Set miaSecretsPassphrase in " +
                "~/.gradle/gradle.properties (or MIA_SECRETS_PASSPHRASE) to use the shared keys."
        )
        return Properties()
    }
    return try {
        Properties().apply { load(decryptFromArmor(encFile.readText(), passphrase).reader()) }
    } catch (e: Exception) {
        logger.warn("MIA: could not decrypt secrets.enc (wrong passphrase?): ${e.message}")
        Properties()
    }
}

// Resolved once per configuration, then shared with :app via extra.
val resolvedLocal = readProps(localPropsFile)
val resolvedPublic = readProps(publicFile)
val resolvedEncrypted = encryptedProps()

val miaSecret: (String) -> String = { key ->
    resolvedLocal.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: System.getenv(key)?.takeIf { it.isNotBlank() }
        ?: resolvedEncrypted.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: resolvedPublic.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: ""
}
rootProject.extra["miaSecret"] = miaSecret

fun requirePassphrase(): String = passphraseOrNull() ?: error(
    """
    No passphrase configured. Set it once per machine:

        echo 'miaSecretsPassphrase=<the passphrase>' >> ~/.gradle/gradle.properties

    or export MIA_SECRETS_PASSPHRASE in the environment (this is what CI does).
    """.trimIndent()
)

tasks.register("secretsDecrypt") {
    group = "mia secrets"
    description = "Decrypt secrets.enc into secrets.local.properties for editing."
    doLast {
        check(encFile.exists()) { "No secrets.enc yet -- create secrets.local.properties and run secretsEncrypt." }
        plainFile.writeText(decryptFromArmor(encFile.readText(), requirePassphrase()))
        logger.lifecycle("Wrote ${plainFile.name} (gitignored). Edit it, then run ./gradlew secretsEncrypt.")
    }
}

tasks.register("secretsEncrypt") {
    group = "mia secrets"
    description = "Encrypt secrets.local.properties into the committed secrets.enc."
    doLast {
        // Seed from local.properties the first time, so nothing has to be retyped.
        val source: String = when {
            plainFile.exists() -> plainFile.readText()
            else -> ENCRYPTED_KEYS
                .joinToString("\n") { "$it=${resolvedLocal.getProperty(it).orEmpty()}" } + "\n"
        }
        encFile.writeText(encryptToArmor(source, requirePassphrase()))
        logger.lifecycle("Wrote ${encFile.name} -- safe to commit.")
    }
}

tasks.register("secretsStatus") {
    group = "mia secrets"
    description = "Show where each build-time key resolves from, with values masked."
    doLast {
        logger.lifecycle("passphrase: ${if (passphraseOrNull() != null) "configured" else "MISSING"}")
        (ENCRYPTED_KEYS + resolvedPublic.stringPropertyNames()).distinct().sorted().forEach { key ->
            val origin = when {
                resolvedLocal.getProperty(key)?.isNotBlank() == true -> "local.properties"
                System.getenv(key)?.isNotBlank() == true -> "env"
                resolvedEncrypted.getProperty(key)?.isNotBlank() == true -> "secrets.enc"
                resolvedPublic.getProperty(key)?.isNotBlank() == true -> "secrets.public.properties"
                else -> "unset"
            }
            val value = miaSecret(key)
            val masked = when {
                value.isEmpty() -> "-"
                value.length <= 8 -> "*".repeat(value.length)
                else -> value.take(4) + "*".repeat(8) + value.takeLast(2)
            }
            logger.lifecycle(String.format("  %-28s %-26s %s", key, origin, masked))
        }
    }
}
