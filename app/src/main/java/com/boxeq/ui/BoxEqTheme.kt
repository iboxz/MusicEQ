package com.boxeq.ui

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

private val Dark = darkColorScheme(
    primary = Color(0xFF7FD6C9), onPrimary = Color(0xFF00201C),
    secondary = Color(0xFFB0CCC7), tertiary = Color(0xFFFFB866),
    background = Color(0xFF101414), surface = Color(0xFF101414),
)
private val Light = lightColorScheme(
    primary = Color(0xFF00695F), onPrimary = Color.White,
    secondary = Color(0xFF4A6460), tertiary = Color(0xFF8A5100),
    background = Color(0xFFF7FAF9), surface = Color(0xFFF7FAF9),
)

@Composable
fun BoxEqTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
