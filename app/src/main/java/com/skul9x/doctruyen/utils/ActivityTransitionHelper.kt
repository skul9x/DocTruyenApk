package com.skul9x.doctruyen.utils

import android.app.Activity
import android.os.Build

/**
 * Helper utility for applying Activity transitions across different Android versions.
 * Uses modern [Activity.overrideActivityTransition] on Android 14+ (API 34+)
 * and falls back to deprecated [Activity.overridePendingTransition] on older API levels.
 */
object ActivityTransitionHelper {

    /**
     * Apply open transition when starting a new activity.
     */
    fun applyOpenTransition(
        activity: Activity,
        enterAnim: Int = android.R.anim.fade_in,
        exitAnim: Int = android.R.anim.fade_out
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_OPEN,
                enterAnim,
                exitAnim
            )
        } else {
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(enterAnim, exitAnim)
        }
    }

    /**
     * Apply close transition when finishing an activity.
     */
    fun applyCloseTransition(
        activity: Activity,
        enterAnim: Int = android.R.anim.fade_in,
        exitAnim: Int = android.R.anim.fade_out
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_CLOSE,
                enterAnim,
                exitAnim
            )
        } else {
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(enterAnim, exitAnim)
        }
    }
}
