package com.nodenote.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.nodenote.model.EdgeTypeDef
import com.nodenote.model.NodeTypeDef
import com.nodenote.storage.ProjectStorage

/** Selectable app appearance. */
enum class AppThemeOption(val label: String) {
    Dark("Dark"),
    Night("Night"),
    Light("Light"),
}

/**
 * Every color a theme controls. The per-type node/edge accent colors live on
 * each type definition and are intentionally constant across themes — they're
 * vivid mid-tones that read on any background.
 */
data class AppColors(
    val accent: Color,
    val onAccent: Color,
    val canvasBackground: Color,
    val panelBackground: Color,
    val panelBackgroundRaised: Color,
    val panelBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textFaint: Color,
    val danger: Color,
    val success: Color,
    val warn: Color,
    val gridDot: Color,
    val edgeLine: Color,
    val isLight: Boolean,
)

// Dark — the original near-black "pro tool" palette (Figma/CAD style).
private val DarkPalette = AppColors(
    accent = Color(0xFF5B9CFF),
    onAccent = Color(0xFF0B1018),
    canvasBackground = Color(0xFF15171B),
    panelBackground = Color(0xFF17191D),
    panelBackgroundRaised = Color(0xFF1E2126),
    panelBorder = Color(0xFF2A2E35),
    textPrimary = Color(0xFFE6E9EE),
    textSecondary = Color(0xFF9AA3AF),
    textFaint = Color(0xFF6B7280),
    danger = Color(0xFFE2606B),
    success = Color(0xFF53C27C),
    warn = Color(0xFFE5B25B),
    gridDot = Color(0x12FFFFFF),
    edgeLine = Color(0xFF4A5260),
    isLight = false,
)

// Night — softer slate greys (One Dark / Material-elevated feel); node cards
// are noticeably lighter than the panels so they pop off the canvas.
private val NightPalette = AppColors(
    accent = Color(0xFF6AA5FF),
    onAccent = Color(0xFF0B1018),
    canvasBackground = Color(0xFF232730),
    panelBackground = Color(0xFF2A2F3A),
    panelBackgroundRaised = Color(0xFF3A4150),
    panelBorder = Color(0xFF4A5161),
    textPrimary = Color(0xFFEEF1F6),
    textSecondary = Color(0xFFB4BCC9),
    textFaint = Color(0xFF8893A3),
    danger = Color(0xFFE2606B),
    success = Color(0xFF53C27C),
    warn = Color(0xFFE5B25B),
    gridDot = Color(0x16FFFFFF),
    edgeLine = Color(0xFF6A7385),
    isLight = false,
)

// Light — white canvas (GitHub/Material light); node cards a shade darker than
// the canvas, with dark text, so they contrast.
private val LightPalette = AppColors(
    accent = Color(0xFF2F6FED),
    onAccent = Color(0xFFFFFFFF),
    canvasBackground = Color(0xFFFFFFFF),
    panelBackground = Color(0xFFF4F6F8),
    panelBackgroundRaised = Color(0xFFECEFF3),
    panelBorder = Color(0xFFCDD3DC),
    textPrimary = Color(0xFF1C2128),
    textSecondary = Color(0xFF59636F),
    textFaint = Color(0xFF8B94A0),
    danger = Color(0xFFD64550),
    success = Color(0xFF2E9E5B),
    warn = Color(0xFFB7791F),
    gridDot = Color(0x14000000),
    edgeLine = Color(0xFF9AA6B4),
    isLight = true,
)

/**
 * Reactive holder for the current theme. The color accessors below read
 * [colors], so any composable (or Canvas draw lambda) that uses a color
 * re-skins automatically when [select] changes the theme.
 */
object ThemeState {
    var option by mutableStateOf(AppThemeOption.Dark)
        private set

    val colors: AppColors
        get() = when (option) {
            AppThemeOption.Dark -> DarkPalette
            AppThemeOption.Night -> NightPalette
            AppThemeOption.Light -> LightPalette
        }

    fun select(newOption: AppThemeOption) {
        option = newOption
        runCatching { ProjectStorage.writePrefs(PREFS_FILE, newOption.name) }
    }

    /** Loads the saved theme once at startup (no-op if none saved). */
    fun load() {
        val saved = runCatching { ProjectStorage.readPrefs(PREFS_FILE) }.getOrNull()?.trim() ?: return
        AppThemeOption.entries.firstOrNull { it.name == saved }?.let { option = it }
    }

    private const val PREFS_FILE = "theme.txt"
}

// ---- Dynamic color accessors ----
// These keep their original names, so every existing `PanelBackground`, `Accent`,
// etc. reference re-skins with the theme without any change at the use site.

val Accent: Color get() = ThemeState.colors.accent
val CanvasBackground: Color get() = ThemeState.colors.canvasBackground
val PanelBackground: Color get() = ThemeState.colors.panelBackground
val PanelBackgroundRaised: Color get() = ThemeState.colors.panelBackgroundRaised
val PanelBorder: Color get() = ThemeState.colors.panelBorder
val TextPrimary: Color get() = ThemeState.colors.textPrimary
val TextSecondary: Color get() = ThemeState.colors.textSecondary
val TextFaint: Color get() = ThemeState.colors.textFaint
val DangerRed: Color get() = ThemeState.colors.danger
val SuccessGreen: Color get() = ThemeState.colors.success
val WarnAmber: Color get() = ThemeState.colors.warn

/** The display color of a node/connection type. Per-type colors are constant across app themes. */
val NodeTypeDef.color: Color get() = Color(colorArgb)
val EdgeTypeDef.color: Color get() = Color(colorArgb)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val c = ThemeState.colors
    val scheme = if (c.isLight) {
        lightColorScheme(
            primary = c.accent,
            onPrimary = c.onAccent,
            primaryContainer = Color(0xFFD8E4FF),
            onPrimaryContainer = Color(0xFF12305F),
            secondary = c.textSecondary,
            background = c.canvasBackground,
            onBackground = c.textPrimary,
            surface = c.panelBackground,
            onSurface = c.textPrimary,
            surfaceVariant = c.panelBackgroundRaised,
            onSurfaceVariant = c.textSecondary,
            outline = c.panelBorder,
            outlineVariant = c.panelBorder,
            error = c.danger,
        )
    } else {
        darkColorScheme(
            primary = c.accent,
            onPrimary = c.onAccent,
            primaryContainer = Color(0xFF24364F),
            onPrimaryContainer = Color(0xFFCBDEFF),
            secondary = c.textSecondary,
            background = c.canvasBackground,
            onBackground = c.textPrimary,
            surface = c.panelBackground,
            onSurface = c.textPrimary,
            surfaceVariant = c.panelBackgroundRaised,
            onSurfaceVariant = c.textSecondary,
            outline = c.panelBorder,
            outlineVariant = c.panelBorder,
            error = c.danger,
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
