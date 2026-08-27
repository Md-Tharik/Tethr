package com.example.tethr.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Tethr always uses its dark palette — pure black monochrome.
private val TethrDarkColorScheme = darkColorScheme(
    primary          = TethrWhite,
    onPrimary        = TethrBlack,
    primaryContainer = TethrCardBg,
    onPrimaryContainer = TethrGray400,
    secondary        = TethrGray400,
    onSecondary      = TethrBlack,
    secondaryContainer = Color(0xFF1A1A1A),
    onSecondaryContainer = TethrCyanLight,
    tertiary         = TethrGreen,
    background       = TethrBlack,
    onBackground     = TethrWhite,
    surface          = TethrCardBg,
    onSurface        = TethrWhite,
    surfaceVariant   = TethrCardBg,
    onSurfaceVariant = TethrGray400,
    outline          = TethrCardBorder,
    error            = TethrRed,
)

@Composable
fun TethrTheme(
    // darkTheme param kept for signature compatibility but Tethr is always dark
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = TethrDarkColorScheme,
        typography  = Typography,
        content     = content,
    )
}
