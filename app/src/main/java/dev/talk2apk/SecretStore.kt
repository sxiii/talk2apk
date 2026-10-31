package dev.talk2apk

import android.content.Context

/** AES/GCM encryption backed by a non-exportable Android Keystore key. */
internal object SecretStore {
    private const val PREFS = "talk2apk_secure"
    private const val KEY_ALIAS = "talk2apk_aes_key"
    private var appContext: Context? = null

    fun initialize(context: Context) { appContext = context.applicationContext }

    private fun key(): java.security.Key {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? java.security.Key)?.let { return it }
        val gen = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(KEY_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build()
        gen.init(spec)
        return gen.generateKey()
    }

    fun put(name: String, value: String) {
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        if (value.isEmpty()) { prefs.edit().remove(name).apply(); return }
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key())
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, android.util.Base64.encodeToString(payload, android.util.Base64.NO_WRAP)).apply()
    }

    fun get(name: String): String {
        val encoded = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(name, null) ?: return ""
        return try {
            val payload = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
            require(payload.size > 12)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, payload.copyOfRange(0, 12)))
            String(cipher.doFinal(payload.copyOfRange(12, payload.size)), Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }

    fun remove(name: String) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.remove(name)?.apply()
    }
}
