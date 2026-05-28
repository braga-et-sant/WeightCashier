package com.example.weightcashier.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = WCPrimary,
    secondary = WCSecondary,
    tertiary = WCPrimaryDark,
    background = Color(0xFF0B1220),
    surface = Color(0xFF0F172A),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFFF1F5F9),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1E293B),
    outline = Color(0xFF334155),
    error = WCError
)

private val LightColorScheme = lightColorScheme(
    primary = WCPrimary,
    secondary = WCSecondary,
    tertiary = WCPrimaryDark,
    background = WCBackground,
    surface = WCSurface,
    surfaceVariant = WCSurfaceVariant,
    outline = WCOutline,
    error = WCError,
    onPrimary = Color.White,
    onSecondary = WCInk,
    onTertiary = Color.White,
    onBackground = WCInk,
    onSurface = WCInk
)

@Composable
fun WeightCashierTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}