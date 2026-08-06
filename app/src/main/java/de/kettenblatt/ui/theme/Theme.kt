package de.kettenblatt.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * A deliberately restrained palette.
 *
 * A map already carries a lot of colour, so the interface contributes one accent
 * and otherwise stays neutral. The accent is the same blue the route is drawn in,
 * which ties the controls to the thing they control.
 *
 * The alert colours are picked for a screen being read in daylight at speed:
 * strong, saturated, and distinguishable from anything OpenStreetMap renders.
 */
object NaviColors {
    val Route = Color(0xFF1D6FF2)
    val RouteDark = Color(0xFF5FA0FF)

    val Travelled = Color(0xFF9AA3B0)

    val OffRoute = Color(0xFFD32F2F)
    val OffRouteDark = Color(0xFFFF6B6B)

    val Caution = Color(0xFFC77700)
    val CautionDark = Color(0xFFFFB74D)

    val Arrived = Color(0xFF17864A)
    val ArrivedDark = Color(0xFF4ADE80)

    val Waypoint = Color(0xFFF59E0B)
    val Start = Color(0xFF17864A)
    val Finish = Color(0xFF1F2937)
}

private val LightScheme = lightColorScheme(
    primary = NaviColors.Route,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF0A2A5E),
    secondary = Color(0xFF4A5568),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6EAF2),
    onSecondaryContainer = Color(0xFF1A202C),
    tertiary = NaviColors.Waypoint,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFECC7),
    onTertiaryContainer = Color(0xFF4A2E00),
    error = NaviColors.OffRoute,
    onError = Color.White,
    errorContainer = Color(0xFFFFE0E0),
    onErrorContainer = Color(0xFF5C0F0F),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF14181F),
    surface = Color.White,
    onSurface = Color(0xFF14181F),
    surfaceVariant = Color(0xFFEEF1F6),
    onSurfaceVariant = Color(0xFF5A6472),
    outline = Color(0xFFC3CAD5),
    outlineVariant = Color(0xFFDFE4EC),
)

private val DarkScheme = darkColorScheme(
    primary = NaviColors.RouteDark,
    onPrimary = Color(0xFF00294F),
    primaryContainer = Color(0xFF1B3A66),
    onPrimaryContainer = Color(0xFFD3E3FF),
    secondary = Color(0xFFB6BFCC),
    onSecondary = Color(0xFF212832),
    secondaryContainer = Color(0xFF2A323D),
    onSecondaryContainer = Color(0xFFDCE3ED),
    tertiary = Color(0xFFFFC773),
    onTertiary = Color(0xFF3D2600),
    tertiaryContainer = Color(0xFF5A3A00),
    onTertiaryContainer = Color(0xFFFFE3B4),
    error = NaviColors.OffRouteDark,
    onError = Color(0xFF4A0B0B),
    errorContainer = Color(0xFF6E1D1D),
    onErrorContainer = Color(0xFFFFDAD6),
    // Not pure black: an OLED-black panel against a bright map is harsh at night.
    background = Color(0xFF10141A),
    onBackground = Color(0xFFE4E8EF),
    surface = Color(0xFF171C24),
    onSurface = Color(0xFFE4E8EF),
    surfaceVariant = Color(0xFF232A34),
    onSurfaceVariant = Color(0xFFA8B2C0),
    outline = Color(0xFF3C4553),
    outlineVariant = Color(0xFF2B323D),
)

/** Alert colours resolved for the current theme. */
data class NaviAccents(
    val offRoute: Color,
    val caution: Color,
    val arrived: Color,
    val onAlert: Color,
)

val MaterialTheme.accents: NaviAccents
    @Composable get() = if (isSystemInDarkTheme()) {
        NaviAccents(
            offRoute = Color(0xFF8E1F1F),
            caution = Color(0xFF7A4A00),
            arrived = Color(0xFF0E5C33),
            onAlert = Color(0xFFFFFFFF),
        )
    } else {
        NaviAccents(
            offRoute = NaviColors.OffRoute,
            caution = NaviColors.Caution,
            arrived = NaviColors.Arrived,
            onAlert = Color.White,
        )
    }

private val NaviShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Numbers are the point of this interface, so the display styles are tuned for
 * glanceability: tight line height, tabular-ish weight, no decorative tracking.
 */
private val NaviTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.Bold),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.6.sp,
        ),
    )
}

@Composable
fun NaviTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        val context = LocalContext.current
        SideEffect {
            (context as? Activity)?.window?.let { window ->
                // Edge-to-edge: the map runs under the system bars, so the icons
                // have to contrast with the map rather than with a solid bar.
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        shapes = NaviShapes,
        typography = NaviTypography,
        content = content,
    )
}
