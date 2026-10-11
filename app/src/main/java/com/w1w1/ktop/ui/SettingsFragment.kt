package com.w1w1.ktop.ui

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import com.w1w1.ktop.R
import com.w1w1.ktop.WheeltopConfig

/** Ki2-style settings tab (androidx Preference screens, like Ki2). */
class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = WheeltopConfig.PREFS
        setPreferencesFromResource(R.xml.preferences_ktop, rootKey)
    }
}
