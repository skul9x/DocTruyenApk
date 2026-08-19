package com.skul9x.doctruyen.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Helper utility to check and request battery optimization exemption status
 * (PowerManager.isIgnoringBatteryOptimizations) to ensure uninterrupted TTS background playback
 * across OEM devices (MIUI, ColorOS, OneUI, etc.).
 */
object BatteryOptimizationHelper {

    private const val TAG = "BatteryOptimizationHelper"

    /**
     * Checks if the app is currently ignoring battery optimizations.
     * Returns true on Android versions prior to Marshmallow (API 23).
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to check battery optimization status: ${e.message}", e)
            false
        }
    }

    /**
     * Requests the user to whitelist the app from battery optimizations.
     * If ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS fails or is unavailable,
     * falls back to the generic battery saver / app settings screen.
     */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }

        if (isIgnoringBatteryOptimizations(context)) {
            Log.d(TAG, "App is already exempt from battery optimizations.")
            return
        }

        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Throwable) {
            Log.w(TAG, "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS failed, opening fallback settings: ${e.message}")
            try {
                val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (ex: Throwable) {
                Log.e(TAG, "Failed to open battery optimization settings: ${ex.message}", ex)
            }
        }
    }
}
