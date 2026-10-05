package bluebit.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val BlueBitColorScheme = lightColorScheme(
    primary = BluePrimary,
    onPrimary = White,
    secondary = BlueSecondary,
    background = Background,
    surface = Surface,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
)

@Composable
fun BlueBitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BlueBitColorScheme,
        typography = BlueBitTypography,
        content = content,
    )
}
