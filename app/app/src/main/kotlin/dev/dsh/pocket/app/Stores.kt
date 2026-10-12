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
data class Profile(
    val id: String,
    val name: String,
    /** Desktop iroh endpoint id. Blank means a pre-iroh (Tailscale/LAN) profile that must re-pair. */
    val nodeId: String = "",
    val deviceId: String? = null,
    /** Legacy fields retained for prefs deserialization; unused for dial. */
    val host: String = "",
    val port: Int = 0,
) {
    val needsRepair: Boolean get() = nodeId.isBlank()
    val kind: String get() = if (needsRepair) "需重新配对" else "iroh"
    /** One-line subtitle on the connections list. */
    val subtitle: String get() = when {
        needsRepair -> "旧连接已停用，请重新扫码配对"
        nodeId.length > 16 -> nodeId.take(12) + "…"
        else -> nodeId
    }
}

enum class BusySend(val label: String, val mode: String) { Queue("排队发送", "queue"), Steer("立即引导", "steer") }

/** Matches Host `ui-theme.preference`. */
enum class Appearance(val label: String) { Light("浅色"), Dark("深色"), System("跟随系统") }

/** Matches Host `ui-chat.transcriptView`. */
enum class TranscriptView(val label: String) {
    Compact("简洁"), Standard("标准"), Detailed("详细"), Verbose("完全展开")
}

/** Client-only on desktop too (`settings.collapse.*`). */
enum class CollapseTiming(val label: String) {
    Completion("回答结束后"), NextInput("下次有新消息时")
}

/** Matches Host `ui-chat.performanceUsage`. */
enum class PerformanceUsage(val label: String) { Compact("简洁"), Detailed("详细") }

enum class AppLanguage(val label: String) { Zh("中文"), En("English") }

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
    var language: AppLanguage
        get() = runCatching { AppLanguage.valueOf(p.getString("language", null) ?: "Zh") }.getOrDefault(AppLanguage.Zh)
        set(v) = p.edit().putString("language", v.name).apply()
    var appearance: Appearance
        get() = runCatching { Appearance.valueOf(p.getString("appearance", null) ?: "Light") }.getOrDefault(Appearance.Light)
        set(v) = p.edit().putString("appearance", v.name).apply()
    var fontSize: Int
        get() = p.getInt("fontSize", 14).coerceIn(12, 22)
        set(v) = p.edit().putInt("fontSize", v.coerceIn(12, 22)).apply()
    var transcriptView: TranscriptView
        get() = runCatching { TranscriptView.valueOf(p.getString("transcriptView", null) ?: "Detailed") }.getOrDefault(TranscriptView.Detailed)
        set(v) = p.edit().putString("transcriptView", v.name).apply()
    var collapseTiming: CollapseTiming
        get() = runCatching { CollapseTiming.valueOf(p.getString("collapseTiming", null) ?: "Completion") }.getOrDefault(CollapseTiming.Completion)
        set(v) = p.edit().putString("collapseTiming", v.name).apply()
    var performanceUsage: PerformanceUsage
        get() = runCatching { PerformanceUsage.valueOf(p.getString("performanceUsage", null) ?: "Detailed") }.getOrDefault(PerformanceUsage.Detailed)
        set(v) = p.edit().putString("performanceUsage", v.name).apply()
}
