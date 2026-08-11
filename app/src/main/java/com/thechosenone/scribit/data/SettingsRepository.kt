package com.thechosenone.scribit.data

import android.content.Context
import com.thechosenone.scribit.security.SecureStore

class SettingsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("scribit_settings", Context.MODE_PRIVATE)
    private val secureStore = SecureStore(appContext)

    fun get(): AppSettings = AppSettings(
        apiBaseUrl = prefs.getString("api_base_url", "") ?: "",
        apiKey = secureStore.getApiKey(),
        model = prefs.getString("model", "") ?: "",
        supportsVision = prefs.getBoolean("supports_vision", true),
        themeMode = prefs.getString("theme_mode", AppSettings.THEME_SYSTEM) ?: AppSettings.THEME_SYSTEM
    )

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString("api_base_url", settings.apiBaseUrl.trim().trimEnd('/'))
            .putString("model", settings.model.trim())
            .putBoolean("supports_vision", settings.supportsVision)
            .putString("theme_mode", settings.themeMode)
            .apply()
        secureStore.putApiKey(settings.apiKey.trim())
    }

    fun restoreNonSecret(
        apiBaseUrl: String,
        model: String,
        supportsVision: Boolean,
        themeMode: String
    ) {
        val safeTheme = themeMode.takeIf {
            it == AppSettings.THEME_SYSTEM || it == AppSettings.THEME_LIGHT || it == AppSettings.THEME_DARK
        } ?: AppSettings.THEME_SYSTEM
        prefs.edit()
            .putString("api_base_url", apiBaseUrl.trim().trimEnd('/'))
            .putString("model", model.trim())
            .putBoolean("supports_vision", supportsVision)
            .putString("theme_mode", safeTheme)
            .apply()
        // The API key is intentionally not restored. Android Keystore keys are device-local,
        // and putting a plaintext API secret inside a portable backup would defeat that protection.
    }

}
