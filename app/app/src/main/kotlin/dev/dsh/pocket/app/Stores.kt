package dev.dsh.pocket.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device tokens, AES-256-GCM encrypted with a non-exportable Android Keystore key. */
class SecureTokenStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("tokens", Context.MODE_PRIVATE)
    private val alias = "dsh-pocket-tokens"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }

    fun put(profileId: String, token: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val blob = c.iv + c.doFinal(token.toByteArray())
        prefs.edit().putString(profileId, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    fun get(profileId: String): String? = runCatching {
        val blob = Base64.decode(prefs.getString(profileId, null) ?: return null, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12)) }
        String(c.doFinal(blob, 12, blob.size - 12))
    }.getOrNull()

    fun remove(profileId: String) = prefs.edit().remove(profileId).apply()
}

@Serializable
data class Profile(val id: String, val name: String, val host: String, val port: Int, val deviceId: String? = null) {
    val url get() = "ws://${if (':' in host) "[$host]" else host}:$port"
    /** Tailscale CGNAT 100.64/10 or MagicDNS (*.ts.net); everything else is LAN. */
    val kind: String get() {
        if (host.endsWith(".ts.net")) return "Tailscale"
        val p = host.split('.').mapNotNull { it.toIntOrNull() }
        return if (p.size == 4 && p[0] == 100 && p[1] in 64..127) "Tailscale" else "LAN"
    }
}

enum class BusySend(val label: String, val mode: String) { Queue("排队发送", "queue"), Steer("立即引导", "steer") }

class AppPrefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    var profiles: List<Profile>
        get() = p.getString("profiles", null)?.let { runCatching { json.decodeFromString(ListSerializer(Profile.serializer()), it) }.getOrNull() } ?: emptyList()
        set(v) = p.edit().putString("profiles", json.encodeToString(ListSerializer(Profile.serializer()), v)).apply()
    var activeProfile: String?
        get() = p.getString("active", null)
        set(v) = p.edit().putString("active", v).apply()
    var busySend: BusySend
        get() = runCatching { BusySend.valueOf(p.getString("busySend", null) ?: "Queue") }.getOrDefault(BusySend.Queue)
        set(v) = p.edit().putString("busySend", v.name).apply()
    var defaultPermission: String
        get() = p.getString("defaultPermission", null) ?: "workspace-write"
        set(v) = p.edit().putString("defaultPermission", v).apply()
    var approvalNotify: Boolean
        get() = p.getBoolean("approvalNotify", true)
        set(v) = p.edit().putBoolean("approvalNotify", v).apply()
}
