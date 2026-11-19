package dev.bookah.tasks.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.vaultDataStore by preferencesDataStore("vault")

/**
 * Keeps secrets in DataStore, each value encrypted by an Android Keystore key (AES-GCM). The key never leaves the Keystore;
 * if it is lost (a restore onto another phone) every value reads as empty and the owner signs in and adds the feed again.
 * Nothing secret is ever written to a log or a plain file.
 */
class KeystoreVault(private val context: Context) {
    suspend fun get(name: String): String? {
        val stored = context.vaultDataStore.data.first()[stringPreferencesKey(name)] ?: return null
        return runCatching { decrypt(stored) }.getOrNull()
    }

    suspend fun put(name: String, value: String) {
        val enc = encrypt(value)
        context.vaultDataStore.edit { it[stringPreferencesKey(name)] = enc }
    }

    suspend fun remove(name: String) {
        context.vaultDataStore.edit { it.remove(stringPreferencesKey(name)) }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        return String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }

    private companion object { const val ALIAS = "bookahtasks-vault" }
}

/** The signed-in Nextcloud account: three small values in the vault. */
class VaultSessionStore(private val vault: KeystoreVault) : SessionStore {
    override suspend fun load(): Session? {
        val server = vault.get("server") ?: return null
        val user = vault.get("user") ?: return null
        val pw = vault.get("secret") ?: return null
        return Session(server, user, pw)
    }

    override suspend fun save(session: Session) {
        vault.put("server", session.server); vault.put("user", session.user); vault.put("secret", session.appPassword)
    }

    override suspend fun clear() {
        vault.remove("server"); vault.remove("user"); vault.remove("secret")
    }
}
