package io.todu.andjs.ui.theme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

val Lime = Color(0xFFC3F449)
private val Light = lightColorScheme(surfaceTint = Color(0xFF285020), surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF2F5ED), surfaceContainer = Color(0xFFEEF1EA), surfaceContainerHigh = Color(0xFFE8EDE3), surfaceContainerHighest = Color(0xFFE2E8DC), secondary = Color(0xFF426538), onSecondary = Color.White, secondaryContainer = Color(0xFFDFF8D2), onSecondaryContainer = Color(0xFF183D10), tertiary = Color(0xFF426538), tertiaryContainer = Color(0xFFDFF8D2), primary = Color(0xFF285020), onPrimary = Color.White,
    primaryContainer = Color(0xFFDFF8D2), onPrimaryContainer = Color(0xFF183D10),
    background = Color(0xFFF7F8F4), onBackground = Color(0xFF181C17),
    surface = Color.White, onSurface = Color(0xFF181C17), surfaceVariant = Color(0xFFEEF1EA),
    onSurfaceVariant = Color(0xFF676E63), outlineVariant = Color(0xFFDCE2D6))
private val Dark = darkColorScheme(surfaceTint = Color(0xFFB9D7AA), surfaceContainerLowest = Color(0xFF0D100B), surfaceContainerLow = Color(0xFF181D15), surfaceContainer = Color(0xFF1C2119), surfaceContainerHigh = Color(0xFF262D22), surfaceContainerHighest = Color(0xFF30382B), secondary = Color(0xFFB9D7AA), onSecondary = Color(0xFF183D10), secondaryContainer = Color(0xFF293D21), onSecondaryContainer = Color(0xFFDFF8D2), tertiary = Color(0xFFB9D7AA), tertiaryContainer = Color(0xFF293D21), primary = Color(0xFFC3F449), onPrimary = Color(0xFF183D10),
    primaryContainer = Color(0xFF293D21), onPrimaryContainer = Color(0xFFDFF8D2),
    background = Color(0xFF121510), onBackground = Color(0xFFE8EDE3),
    surface = Color(0xFF1C2119), onSurface = Color(0xFFE8EDE3), surfaceVariant = Color(0xFF262D22),
    onSurfaceVariant = Color(0xFFB5BEAE), outlineVariant = Color(0xFF374030))
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
