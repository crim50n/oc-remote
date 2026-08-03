package dev.minios.ocremote.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

enum class HapticStrength(val value: String) {
    LIGHT("light"),
    MEDIUM("medium"),
    STRONG("strong");

    companion object {
        fun from(value: String): HapticStrength = entries.firstOrNull { it.value == value } ?: MEDIUM
    }
}

data class AppHapticConfig(
    val enabled: Boolean = true,
    val durationMillis: Int = 30,
    val amplitude: Int = 160,
)

object AppHaptics {
    @Suppress("DEPRECATION")
    fun perform(view: View, config: AppHapticConfig) {
        if (!config.enabled) return
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            view.context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        val durationMillis = config.durationMillis.coerceIn(5, 100).toLong()
        val amplitude = config.amplitude.coerceIn(1, 255)
        val vibrated = runCatching {
            if (vibrator?.hasVibrator() == true) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMillis, amplitude))
                true
            } else {
                false
            }
        }.getOrDefault(false)
        if (!vibrated) {
            view.performHapticFeedback(
                when {
                    amplitude < 96 -> HapticFeedbackConstants.CLOCK_TICK
                    amplitude < 208 -> HapticFeedbackConstants.CONTEXT_CLICK
                    else -> HapticFeedbackConstants.CONFIRM
                },
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
            )
        }
    }
}
