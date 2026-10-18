package com.w1w1.ktop.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.w1w1.ktop.KtopOverlay
import com.w1w1.ktop.KtopSounds
import com.w1w1.ktop.R
import com.w1w1.ktop.WheeltopConfig
import io.hammerhead.karooext.KarooSystemService

/** Ki2-style settings tab (androidx Preference screens, like Ki2). */
class SettingsFragment : PreferenceFragmentCompat() {

    // Used to preview the selected sound on the Karoo speaker.
    private var karooSystem: KarooSystemService? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = WheeltopConfig.PREFS
        setPreferencesFromResource(R.xml.preferences_ktop, rootKey)

        findPreference<SwitchPreferenceCompat>(WheeltopConfig.KEY_OVERLAY_ENABLED)
            ?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue == true && !KtopOverlay.canDraw(requireContext())) requestOverlayPermission()
                true
            }

        findPreference<Preference>(WheeltopConfig.KEY_OVERLAY_TEST)?.setOnPreferenceClickListener {
            if (KtopOverlay.canDraw(requireContext())) {
                KtopOverlay.showTest(requireContext())
            } else {
                requestOverlayPermission()
            }
            true
        }

        listOf(WheeltopConfig.KEY_AUDIO_LOWEST_GEAR, WheeltopConfig.KEY_AUDIO_HIGHEST_GEAR).forEach { key ->
            findPreference<ListPreference>(key)?.setOnPreferenceChangeListener { _, newValue ->
                runCatching { karooSystem?.let { KtopSounds.play(it, newValue as? String) } }
                    .onFailure { Log.w("ktop-ble", "sound preview failed", it) }
                true
            }
        }
    }

    override fun onStart() {
        super.onStart()
        karooSystem = KarooSystemService(requireContext()).also { k -> k.connect { } }
    }

    override fun onStop() {
        karooSystem?.disconnect()
        karooSystem = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Back from the system permission screen: show whether it worked.
        val enabled = findPreference<SwitchPreferenceCompat>(WheeltopConfig.KEY_OVERLAY_ENABLED) ?: return
        enabled.summary = getString(
            if (enabled.isChecked && !KtopOverlay.canDraw(requireContext())) R.string.text_overlay_permission_missing
            else R.string.text_overlay_enabled_summary
        )
    }

    private fun requestOverlayPermission() {
        val ctx = requireContext()
        runCatching {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            Log.w("ktop-ble", "overlay permission screen not available", it)
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
