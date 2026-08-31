package com.vibe.app.presentation.ui.supermarket

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val MarketGreen = Color(0xFF0B6B4F)
private val MarketGreenDark = Color(0xFF074B38)
private val MarketMint = Color(0xFFDDF4EA)
private val MarketAmber = Color(0xFFF4A61C)
private val MarketBlue = Color(0xFF2D6CDF)
private val MarketInk = Color(0xFF17211D)
private val MarketMuted = Color(0xFF5F6F67)
private val MarketBackground = Color(0xFFF7FAF8)
private val MarketSurface = Color(0xFFFFFFFF)
private val MarketLine = Color(0xFFDCE6E1)

private val MarketLightScheme = lightColorScheme(
    primary = MarketGreen,
    onPrimary = Color.White,
    primaryContainer = MarketMint,
    onPrimaryContainer = MarketGreenDark,
    secondary = MarketAmber,
    onSecondary = Color(0xFF2B1B00),
    secondaryContainer = Color(0xFFFFE9BF),
    onSecondaryContainer = Color(0xFF503500),
    tertiary = MarketBlue,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCE7FF),
    onTertiaryContainer = Color(0xFF12376F),
    background = MarketBackground,
    onBackground = MarketInk,
    surface = MarketSurface,
    onSurface = MarketInk,
    surfaceVariant = Color(0xFFF0F5F2),
    onSurfaceVariant = MarketMuted,
    outline = Color(0xFF83938B),
    outlineVariant = MarketLine,
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

private val MarketDarkScheme = darkColorScheme(
    primary = Color(0xFF76DDB6),
    onPrimary = Color(0xFF003829),
    primaryContainer = Color(0xFF0B533E),
    onPrimaryContainer = Color(0xFFB5F1D8),
    secondary = Color(0xFFFFC65B),
    onSecondary = Color(0xFF422B00),
    secondaryContainer = Color(0xFF5E4000),
    onSecondaryContainer = Color(0xFFFFDEA1),
    tertiary = Color(0xFFAFC6FF),
    onTertiary = Color(0xFF002E69),
    tertiaryContainer = Color(0xFF174A8F),
    onTertiaryContainer = Color(0xFFD8E2FF),
    background = Color(0xFF0F1512),
    onBackground = Color(0xFFE0E8E3),
    surface = Color(0xFF141C18),
    onSurface = Color(0xFFE0E8E3),
    surfaceVariant = Color(0xFF1D2823),
    onSurfaceVariant = Color(0xFFBAC8C1),
    outline = Color(0xFF87968E),
    outlineVariant = Color(0xFF33423B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val MarketShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun SuperMarketTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) MarketDarkScheme else MarketLightScheme,
        shapes = MarketShapes,
        typography = MaterialTheme.typography,
        content = content
    )
}
