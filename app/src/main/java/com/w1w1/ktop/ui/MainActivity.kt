package com.w1w1.ktop.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.w1w1.ktop.R

/**
 * Ki2-style main screen (Ki2 activities/main/MainActivity, MIT License):
 * yellow header, DEVICES / SETTINGS tabs, back button bottom-left.
 * Also the one place that asks for the Bluetooth runtime permissions
 * (karoo-ext extensions are headless services and can't).
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        requestBluetoothPermissions()

        findViewById<TextView>(R.id.textview_main_version).text =
            runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""

        val tabs = findViewById<TabLayout>(R.id.tabs)
        val pager = findViewById<ViewPager2>(R.id.viewpager2_main)
        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 2
            override fun createFragment(position: Int): Fragment =
                if (position == 0) DevicesFragment() else SettingsFragment()
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { pager.currentItem = tab.position }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) { tabs.selectTab(tabs.getTabAt(position)) }
        })

        findViewById<ExtendedFloatingActionButton>(R.id.button_back).setOnClickListener { finish() }
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val perms = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        if (perms.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(perms, 1001)
        }
    }
}
