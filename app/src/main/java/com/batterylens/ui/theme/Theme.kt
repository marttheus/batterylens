package com.batterylens.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.batterylens.data.ThemeMode

val ChargeGreen = Color(0xFF2FBF71)
val DrainAmber = Color(0xFFE8A33D)
val HotRed = Color(0xFFE5484D)
val CoolBlue = Color(0xFF3E8ED0)

private val Light = lightColorScheme(
    primary = Color(0xFF0F7A52),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB9F2D6),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF4D6357),
    background = Color(0xFFF7F9F7),
    surface = Color(0xFFF7F9F7),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF6FDDA6),
    onPrimary = Color(0xFF003921),
    primaryContainer = Color(0xFF005233),
    onPrimaryContainer = Color(0xFFB9F2D6),
    secondary = Color(0xFFB4CCBD),
    background = Color(0xFF101411),
    surface = Color(0xFF101411),
)

@Composable
fun BatteryLensTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    val context = LocalContext.current
    val dynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    var scheme = when {
        dynamic && dark -> dynamicDarkColorScheme(context)
        dynamic -> dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    if (mode == ThemeMode.AMOLED) {
        scheme = scheme.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF0A0A0A),
            surfaceContainer = Color(0xFF0E0E0E),
            surfaceContainerHigh = Color(0xFF141414),
            surfaceContainerHighest = Color(0xFF1A1A1A),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
