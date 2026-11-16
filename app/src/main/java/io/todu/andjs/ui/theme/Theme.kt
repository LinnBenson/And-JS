package io.todu.andjs.ui.theme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

internal val Light = lightColorScheme(
    primary = Color(0xFF283E50),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF344B5E),
    onPrimaryContainer = Color(0xFFF1F3F5),
    secondary = Color(0xFF3C4853),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF46525E),
    onSecondaryContainer = Color(0xFFF1F3F5),
    tertiary = Color(0xFF4C5042),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF555A49),
    onTertiaryContainer = Color(0xFFF3F3EC),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF202428),
    surface = Color(0xFFF7F8FA),
    onSurface = Color(0xFF202428),
    surfaceVariant = Color(0xFFE3E7EB),
    onSurfaceVariant = Color(0xFF414C56),
    outline = Color(0xFF626F7B),
    outlineVariant = Color(0xFFBDC6CE),
    surfaceTint = Color(0xFF283E50),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F3F5),
    surfaceContainer = Color(0xFFECEFF2),
    surfaceContainerHigh = Color(0xFFE3E7EB),
    surfaceContainerHighest = Color(0xFFDCE2E7),
    inverseSurface = Color(0xFF2D3237),
    inverseOnSurface = Color(0xFFEFF1F3),
    inversePrimary = Color(0xFFB0C3D3),
    error = Color(0xFF9B4545),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF2DEDD),
    onErrorContainer = Color(0xFF652727),
    scrim = Color(0xFF000000),
)
internal val Dark = darkColorScheme(
    primary = Color(0xFF8FAABE),
    onPrimary = Color(0xFF1E3343),
    primaryContainer = Color(0xFF2B4052),
    onPrimaryContainer = Color(0xFFDCE4EB),
    secondary = Color(0xFFA4AFBA),
    onSecondary = Color(0xFF29333C),
    secondaryContainer = Color(0xFF3D4852),
    onSecondaryContainer = Color(0xFFE1E5E9),
    tertiary = Color(0xFFB0B29C),
    onTertiary = Color(0xFF303227),
    tertiaryContainer = Color(0xFF47493D),
    onTertiaryContainer = Color(0xFFE5E5DB),
    background = Color(0xFF0C1014),
    onBackground = Color(0xFFE2E5E8),
    surface = Color(0xFF151B21),
    onSurface = Color(0xFFE2E5E8),
    surfaceVariant = Color(0xFF30373D),
    onSurfaceVariant = Color(0xFFB9C1C8),
    outline = Color(0xFF89939C),
    outlineVariant = Color(0xFF424B53),
    surfaceTint = Color(0xFF8FAABE),
    surfaceContainerLowest = Color(0xFF0E1114),
    surfaceContainerLow = Color(0xFF151B21),
    surfaceContainer = Color(0xFF1C252D),
    surfaceContainerHigh = Color(0xFF292F35),
    surfaceContainerHighest = Color(0xFF343B42),
    inverseSurface = Color(0xFFE2E5E8),
    inverseOnSurface = Color(0xFF2D3237),
    inversePrimary = Color(0xFF465F73),
    error = Color(0xFFE4AEAA),
    onError = Color(0xFF542625),
    errorContainer = Color(0xFF713B39),
    onErrorContainer = Color(0xFFF2DEDD),
    scrim = Color(0xFF000000),
)
object Dimensions { val page = 20.dp; val gap = 12.dp; val cardRadius = 16.dp }
enum class Appearance(val title:String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }
class AppearanceSettings(initial:Appearance,private val save:(Appearance)->Unit) {
    var mode by mutableStateOf(initial)
        private set
    fun select(value:Appearance) {mode=value;save(value)}
}
val LocalAppearance=staticCompositionLocalOf<AppearanceSettings> {error("缺少主题设置")}
@Composable fun AndJsTheme(content: @Composable () -> Unit) {
    val context=LocalContext.current
    val settings=remember {
        val prefs=context.getSharedPreferences("appearance",android.content.Context.MODE_PRIVATE)
        AppearanceSettings(Appearance.entries.firstOrNull {it.name==prefs.getString("mode",null)} ?: Appearance.SYSTEM) {
            prefs.edit().putString("mode",it.name).apply()
        }
    }
    val dark=when(settings.mode) {Appearance.SYSTEM->isSystemInDarkTheme();Appearance.LIGHT->false;Appearance.DARK->true}
    CompositionLocalProvider(LocalAppearance provides settings) {
    MaterialTheme(colorScheme = if (dark) Dark else Light,
        shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp)), content = content)
    }
}
