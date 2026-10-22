package dev.usenetstream.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.usenetstream.engine.newznab.AudioLayout
import dev.usenetstream.engine.newznab.Codec
import dev.usenetstream.engine.newznab.IndexerConfig
import dev.usenetstream.engine.newznab.PlayPrefs
import dev.usenetstream.engine.newznab.Quality
import dev.usenetstream.engine.newznab.ReleaseFilter
import dev.usenetstream.ui.ThemeMode
import dev.usenetstream.engine.nntp.NntpConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Server + indexer settings in SharedPreferences; password and API key are AES-GCM encrypted with a key held in Android Keystore. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("nntp", Context.MODE_PRIVATE)

    fun load(): NntpConfig? {
        val host = prefs.getString("host", null) ?: return null
        val password = prefs.getString("password", null)?.let(::decrypt) ?: return null
        return NntpConfig(
            host = host,
            port = prefs.getInt("port", 563),
            useTls = prefs.getBoolean("tls", true),
            username = prefs.getString("username", "").orEmpty(),
            password = password,
            connections = prefs.getInt("connections", 10),
        )
    }

    fun save(config: NntpConfig) {
        prefs.edit()
            .putString("host", config.host)
            .putInt("port", config.port)
            .putBoolean("tls", config.useTls)
            .putString("username", config.username)
            .putString("password", encrypt(config.password))
            .putInt("connections", config.connections)
            .apply()
    }

    fun loadIndexer(): IndexerConfig? {
        val url = prefs.getString("indexer_url", null) ?: return null
        val key = prefs.getString("indexer_key", null)?.let(::decrypt) ?: return null
        return IndexerConfig(url, key)
    }

    fun saveIndexer(config: IndexerConfig?) {
        val edit = prefs.edit()
        if (config == null) {
            edit.remove("indexer_url").remove("indexer_key")
        } else {
            edit.putString("indexer_url", config.baseUrl).putString("indexer_key", encrypt(config.apiKey))
        }
        edit.apply()
    }

    /** Movie of the Night key for the "what's on" lanes; encrypted like the indexer key. */
    fun loadGuideKey(): String? = prefs.getString("guide_key", null)?.let(::decrypt)

    fun saveGuideKey(key: String?) {
        prefs.edit().apply { if (key.isNullOrBlank()) remove("guide_key") else putString("guide_key", encrypt(key.trim())) }.apply()
    }

    /** Two-letter country whose catalogs the lanes show; null = from the device. */
    var guideCountry: String?
        get() = prefs.getString("guide_country", null)
        set(v) = prefs.edit().apply { if (v.isNullOrBlank()) remove("guide_country") else putString("guide_country", v.trim().lowercase()) }.apply()

    // ---- UI preferences (not secret, plain prefs) ----

    var playPrefs: PlayPrefs
        get() = PlayPrefs(
            quality = Quality.valueOf(prefs.getString("pref_quality", Quality.FHD.name)!!),
            maxBytes = prefs.getLong("pref_max_bytes", 0).takeIf { it > 0 },
        )
        set(v) {
            prefs.edit().putString("pref_quality", v.quality.name).putLong("pref_max_bytes", v.maxBytes ?: 0).apply()
        }

    /** The sticky release filter from the details page. */
    var releaseFilter: ReleaseFilter
        get() = ReleaseFilter(
            qualities = prefs.getStringSet("rf_quality", emptySet())!!.mapNotNull { runCatching { Quality.valueOf(it) }.getOrNull() }.toSet(),
            codecs = prefs.getStringSet("rf_codec", emptySet())!!.mapNotNull { runCatching { Codec.valueOf(it) }.getOrNull() }.toSet(),
            audio = prefs.getStringSet("rf_audio", emptySet())!!.mapNotNull { runCatching { AudioLayout.valueOf(it) }.getOrNull() }.toSet(),
            hdr = if (prefs.contains("rf_hdr")) prefs.getBoolean("rf_hdr", false) else null,
            maxBytes = prefs.getLong("rf_max_bytes", 0).takeIf { it > 0 },
            language = prefs.getString("rf_language", null),
            maxAgeDays = prefs.getLong("rf_max_age", 0).takeIf { it > 0 },
            hideLocked = prefs.getBoolean("rf_hide_locked", true),
            hideUnplayable = prefs.getBoolean("rf_hide_unplayable", false),
        )
        set(f) {
            prefs.edit().apply {
                putStringSet("rf_quality", f.qualities.map { it.name }.toSet())
                putStringSet("rf_codec", f.codecs.map { it.name }.toSet())
                putStringSet("rf_audio", f.audio.map { it.name }.toSet())
                if (f.hdr == null) remove("rf_hdr") else putBoolean("rf_hdr", f.hdr!!)
                putLong("rf_max_bytes", f.maxBytes ?: 0)
                if (f.language == null) remove("rf_language") else putString("rf_language", f.language)
                putLong("rf_max_age", f.maxAgeDays ?: 0)
                putBoolean("rf_hide_locked", f.hideLocked)
                putBoolean("rf_hide_unplayable", f.hideUnplayable)
            }.apply()
        }

    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(prefs.getString("theme_mode", ThemeMode.SYSTEM.name)!!) }.getOrDefault(ThemeMode.SYSTEM)
        set(v) = prefs.edit().putString("theme_mode", v.name).apply()

    /** Version of the disclaimer the user accepted (0 = never). */
    var disclaimerAccepted: Int
        get() = prefs.getInt("disclaimer_accepted", 0)
        set(v) = prefs.edit().putInt("disclaimer_accepted", v).apply()

    var showStats: Boolean
        get() = prefs.getBoolean("show_stats", false)
        set(v) = prefs.edit().putBoolean("show_stats", v).apply()

    /** Newest first, max 10. Stored as one string; queries never contain a newline. */
    var recentSearches: List<String>
        get() = prefs.getString("recent_searches", "")!!.split('\n').filter { it.isNotBlank() }
        set(v) = prefs.edit().putString("recent_searches", v.take(10).joinToString("\n")).apply()

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = try {
        val sealed = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
        }
        String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES))
    } catch (e: Exception) {
        null // key lost (e.g. backup restored to a new phone): ask again
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "nntp_password"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
