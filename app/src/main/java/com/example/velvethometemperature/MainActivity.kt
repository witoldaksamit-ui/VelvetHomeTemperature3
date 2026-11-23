package com.example.velvethometemperature

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

// Miękka krzywa łagodzenia (odpowiednik EaseInOutSine)
val SoftEasing = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)

// Wspólne tło aksamitne (używane na wszystkich ekranach)
val VelvetBackground = Brush.verticalGradient(listOf(Color(0xFF4A1233), Color(0xFF1A0613)))

// 1. Definicje interfejsów sieciowych dla Retrofit
interface TemperatureApi {
    // Plik pomieszczenia z repozytorium velvethome.github.io, np. "temperatures_salon.txt"
    @GET("witoldaksamit-ui/velvethome.github.io/main/{file}")
    // t: znacznik czasu w adresie, żeby nie dostać starej kopii pliku z pamięci podręcznej
    suspend fun getRoomFile(@retrofit2.http.Path("file") file: String, @retrofit2.http.Query("t") t: Long): String
}

interface GasApi {
    // Surowy plik z odczytami licznika gazu (format: "2026-10-30 16:07:24 | 106.973")
    @GET("witoldaksamit-ui/licznik_gazu/main/odczyty24h.txt")
    suspend fun getReadings(@retrofit2.http.Query("t") t: Long): String
}

// 2. Model danych dla jednego pomiaru w pomieszczeniu (temperatura i wilgotność)
data class RoomEntry(
    val timestamp: ZonedDateTime,
    val temperature: Float,
    val humidity: Float?,       // null, gdy w pliku nie ma wilgotności
    val battery: String? = null // stan baterii z 4. kolumny: procent albo low / middle / high
)

// Pomieszczenie: nazwa na ekranie, plik z danymi i kolor
data class Room(val name: String, val file: String, val color: Color)

// Pomieszczenia w kolejności na ekranie: najpierw wnętrze, na końcu czujnik za oknem
val ROOMS = listOf(
    Room("Salon", "temperatures_salon.txt", Color(0xFFEF5350)),          // czerwony
    Room("Sypialnia", "temperatures_sypialnia.txt", Color(0xFFAB47BC)),  // fioletowy
    Room("Pralnia", "temperatures_pralnia.txt", Color(0xFF42A5F5)),      // niebieski
    Room("Dwór", "temperatures_dwor.txt", Color(0xFF66BB6A))             // zielony
)

// Model danych dla jednego odczytu licznika gazu
data class GasReading(
    val timestamp: LocalDateTime,
    val value: Double
)

// Definicja dostępnych zakresów czasowych dla osi X
enum class TimeRange(val hours: Long, val label: String) {
    LAST_24H(24L, "Ostatnie 24h"),
    LAST_12H(12L, "Ostatnie 12h"),
    LAST_6H(6L, "Ostatnie 6h")
}

// Reprezentacja stanów ekranu dla modułu nawigacji
enum class AppScreen {
    SPLASH, HOME
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
fun SplashScreen() {
    val appear = remember { Animatable(0f) }   // przezroczystość całości
    val scale = remember { Animatable(0.85f) } // sprężyste osadzenie
    val glow = remember { Animatable(0f) }     // rozjaśnianie okna

    LaunchedEffect(Unit) {
        launch { appear.animateTo(1f, tween(1800, easing = SoftEasing)) }
        launch {
            scale.animateTo(
                1f,
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        }
        launch {
            glow.animateTo(
                1f,
                tween(durationMillis = 1200, delayMillis = 2000, easing = FastOutSlowInEasing)
            )
        }
    }

    val gold = Color(0xFFFFC96B)
    val cream = Color(0xFFF7E9D7)
    val windowOff = Color(0xFF2A0B1C) // wygaszone okno

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VelvetBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                alpha = appear.value
                scaleX = scale.value
                scaleY = scale.value
            }
        ) {
            Canvas(modifier = Modifier.size(160.dp)) {
                val w = size.width
                val h = size.height
                val g = glow.value

                val velvet = Brush.verticalGradient(
                    listOf(Color(0xFF9C2A5E), Color(0xFF5E1239))
                )

                // Korpus domu
                drawRoundRect(
                    brush = velvet,
                    topLeft = Offset(w * 0.17f, h * 0.46f),
                    size = Size(w * 0.66f, h * 0.46f),
                    cornerRadius = CornerRadius(12.dp.toPx())
                )

                // Dach
                val roof = Path().apply {
                    moveTo(w * 0.5f, h * 0.06f)
                    lineTo(w * 0.96f, h * 0.50f)
                    lineTo(w * 0.04f, h * 0.50f)
                    close()
                }
                drawPath(path = roof, brush = velvet)
                drawPath(
                    path = roof,
                    color = gold,
                    style = Stroke(
                        width = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )

                // Poświata ciepłego światła - narasta razem z oknem
                val glowCenter = Offset(w * 0.5f, h * 0.68f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(gold.copy(alpha = 0.45f * g), Color.Transparent),
                        center = glowCenter,
                        radius = w * 0.34f
                    ),
                    radius = w * 0.34f,
                    center = glowCenter
                )

                // Okno: płynne przejście od ciemnego do złotego
                drawRoundRect(
                    color = lerp(windowOff, gold, g),
                    topLeft = Offset(w * 0.40f, h * 0.58f),
                    size = Size(w * 0.20f, h * 0.20f),
                    cornerRadius = CornerRadius(6.dp.toPx())
                )
                // Szprosy okna
                val bar = 2.dp.toPx()
                drawLine(
                    color = Color(0xFF5E1239),
                    start = Offset(w * 0.5f, h * 0.58f),
                    end = Offset(w * 0.5f, h * 0.78f),
                    strokeWidth = bar
                )
                drawLine(
                    color = Color(0xFF5E1239),
                    start = Offset(w * 0.40f, h * 0.68f),
                    end = Offset(w * 0.60f, h * 0.68f),
                    strokeWidth = bar
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "VelvetHome",
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = cream
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "temperatura w Twoim domu",
                fontSize = 14.sp,
                letterSpacing = 1.sp,
                color = gold.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
fun AppRoot() {
    var currentScreen by rememberSaveable { mutableStateOf(AppScreen.SPLASH) }

    // Po splashu ekran główny z zakładkami
    if (currentScreen == AppScreen.SPLASH) {
        LaunchedEffect(Unit) {
            delay(4000) // czas wyświetlania splasha
            currentScreen = AppScreen.HOME
        }
    }

    Crossfade(
        targetState = currentScreen,
        animationSpec = tween(durationMillis = 1000, easing = SoftEasing),
        label = "splash_to_home"
    ) { screen ->
        when (screen) {
            AppScreen.SPLASH -> SplashScreen()
            AppScreen.HOME -> HomeScreen()
        }
    }
}

// Parsowanie pliku pomieszczenia. Format linii: "2026-11-19T19:45:33+01:00,21.2,50"
// (wilgotność może być pusta, np. "2026-11-19T19:45:33+01:00,21.2,")
fun parseRoomData(rawText: String): List<RoomEntry> {
    val formatter = DateTimeFormatter.ISO_DATE_TIME
    return rawText.lines().mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
        try {
            val tokens = trimmed.split(",")
            if (tokens.size < 2) return@mapNotNull null
            RoomEntry(
                timestamp = ZonedDateTime.parse(tokens[0].trim(), formatter),
                temperature = tokens[1].trim().toFloat(),
                humidity = tokens.getOrNull(2)?.trim()?.toFloatOrNull(),
                battery = tokens.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }
            )
        } catch (e: Exception) {
            android.util.Log.e("ROOM_PARSE", "Błąd w linii: $trimmed", e)
            null
        }
    }.sortedBy { it.timestamp }
}

// Parsowanie odczytów gazu. Format linii: "2026-10-30 16:07:24 | 106.973"
fun parseGasReadings(rawText: String): List<GasReading> {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    return rawText.lines().mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@mapNotNull null
        try {
            val parts = trimmed.split("|")
            if (parts.size < 2) return@mapNotNull null
            GasReading(
                timestamp = LocalDateTime.parse(parts[0].trim(), formatter),
                value = parts[1].trim().toDouble()
            )
        } catch (e: Exception) {
            android.util.Log.e("GAS_PARSE", "Błąd w linii: $trimmed", e)
            null
        }
    }.sortedBy { it.timestamp }
}

// Średnia krocząca - usuwa schodki wynikające z rozdzielczości czujnika
fun smoothValues(values: List<Float>, window: Int): List<Float> {
    if (window <= 1 || values.size < 3) return values
    val half = window / 2
    return values.indices.map { i ->
        val from = (i - half).coerceAtLeast(0)
        val to = (i + half).coerceAtMost(values.lastIndex)
        var sum = 0f
        for (j in from..to) sum += values[j]
        sum / (to - from + 1)
    }
}

// ---------- Ekran główny: pasek zakładek u góry (jak strona na telefonie) ----------

// Zakładki ekranu głównego
enum class HomeTab(val label: String) {
    TEMPERATURE("Temperatura"),
    HUMIDITY("Wilgotność"),
    GAS("Licznik gazu")
}

// Dane pomieszczeń pobrane z GitHuba (nazwa pliku -> pomiary)
data class RoomsData(
    val data: Map<String, List<RoomEntry>> = emptyMap(),
    val failed: Set<String> = emptySet(),
    val loading: Boolean = true,
    val error: String? = null
)

// Odczyty licznika gazu
data class GasData(
    val readings: List<GasReading> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null
)

private val Cream = Color(0xFFF7E9D7)
private val Gold = Color(0xFFFFC96B)
private val Panel = Color(0xFF2A0B1C)
private val Plum = Color(0xFF9C2A5E)
private val ErrorRed = Color(0xFFFF8A80)
private val GoodGreen = Color(0xFF7BD88F)
private const val STALE_MINUTES = 60L
private const val REFRESH_MS = 60_000L   // automatyczne odświeżanie danych co minutę   // odczyt starszy niż godzina jest oznaczany jako nieaktualny

private fun rawRetrofit(): Retrofit = Retrofit.Builder()
    .baseUrl("https://raw.githubusercontent.com/")
    .addConverterFactory(ScalarsConverterFactory.create())
    .build()

// Pobiera pliki wszystkich pomieszczeń równolegle; błąd jednego nie blokuje pozostałych
suspend fun loadRooms(): RoomsData {
    val api = rawRetrofit().create(TemperatureApi::class.java)
    val results = withContext(Dispatchers.IO) {
        ROOMS.map { room ->
            async { room.file to runCatching { parseRoomData(api.getRoomFile(room.file, System.currentTimeMillis())) } }
        }.awaitAll()
    }
    val data = results.mapNotNull { (file, result) -> result.getOrNull()?.let { file to it } }.toMap()
    val failed = results.filter { it.second.isFailure }.map { it.first }.toSet()
    val error = if (data.isEmpty()) {
        "Błąd pobierania danych: ${results.firstNotNullOfOrNull { it.second.exceptionOrNull() }?.localizedMessage}"
    } else null
    return RoomsData(data, failed, loading = false, error = error)
}

// Łączy odświeżone dane z poprzednimi: pomieszczenie, którego nie udało się pobrać,
// zachowuje ostatnie dane (kafelek pokaże wtedy godzinę pomiaru i ewentualnie „nieaktualne”)
fun mergeRooms(old: RoomsData, new: RoomsData): RoomsData {
    if (old.data.isEmpty()) return new
    val data = old.data + new.data
    return RoomsData(
        data = data,
        failed = new.failed.filter { it !in data }.toSet(),
        loading = false,
        error = null
    )
}

// Stan osuszacza w pralni (plik osuszacz.txt zapisywany przez Termometr co 5 min)
data class DehumEntry(
    val timestamp: ZonedDateTime,
    val on: Boolean,
    val mode: Int,          // 0 = wilgotność docelowa (DRY / COMFORT), 1 = ciągłe (CONT albo TURBO)
    val target: Int,        // wilgotność docelowa w %
    val fan: Int,           // 0 = niski, 2 = wysoki
    val humidity: Float,    // wilgotność zmierzona przez osuszacz w %
    val fault: Int          // bity 0-5 = P1, E3, E5, EH, E1, E2; bit 6 (64) = pełny zbiornik
)

// Format linii: "<czas>,<włączony 1/0>,<tryb>,<cel %>,<wentylator>,<wilgotność %>,<błędy>"
fun parseDehumData(rawText: String): List<DehumEntry> = rawText.lines().mapNotNull { line ->
    val t = line.trim()
    if (t.isEmpty() || t.startsWith("#")) return@mapNotNull null
    try {
        val p = t.split(",").map { it.trim() }
        DehumEntry(
            timestamp = ZonedDateTime.parse(p[0], DateTimeFormatter.ISO_DATE_TIME),
            on = p[1] == "1",
            mode = p[2].toInt(),
            target = p[3].toInt(),
            fan = p[4].toInt(),
            humidity = p[5].toFloat(),
            fault = p.getOrNull(6)?.toIntOrNull() ?: 0
        )
    } catch (e: Exception) {
        null
    }
}.sortedBy { it.timestamp }

// null przy błędzie pobierania (zostają wtedy poprzednie dane)
suspend fun loadDehum(): List<DehumEntry>? = try {
    withContext(Dispatchers.IO) {
        parseDehumData(rawRetrofit().create(TemperatureApi::class.java).getRoomFile("osuszacz.txt", System.currentTimeMillis()))
    }
} catch (e: Exception) {
    null
}

suspend fun loadGas(): GasData = try {
    val raw = withContext(Dispatchers.IO) { rawRetrofit().create(GasApi::class.java).getReadings(System.currentTimeMillis()) }
    val readings = parseGasReadings(raw)
    GasData(readings, loading = false, error = if (readings.isEmpty()) "Brak odczytów w pliku" else null)
} catch (e: Exception) {
    GasData(loading = false, error = "Błąd pobierania danych: ${e.localizedMessage}")
}

@Composable
fun HomeScreen() {
    var tab by rememberSaveable { mutableStateOf(HomeTab.TEMPERATURE) }
    // Zakres wykresów wspólny dla temperatury i wilgotności (jak na stronie)
    var selectedRange by rememberSaveable { mutableStateOf(TimeRange.LAST_24H) }
    var rooms by remember { mutableStateOf(RoomsData()) }
    var gas by remember { mutableStateOf(GasData()) }
    var dehum by remember { mutableStateOf<List<DehumEntry>>(emptyList()) }

    // Dane odświeżane co minutę, ale tylko gdy aplikacja jest na ekranie (w tle nic nie pobiera).
    // Po powrocie do aplikacji odświeżenie następuje od razu.
    val context = LocalContext.current
    val lifecycle = (context as ComponentActivity).lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var widgetRefreshed = false
            while (true) {
                coroutineScope {
                    launch {
                        rooms = mergeRooms(rooms, loadRooms())
                        // Po otwarciu aplikacji widget na ekranie głównym też dostaje świeże dane
                        if (!widgetRefreshed && rooms.data.isNotEmpty()) {
                            widgetRefreshed = true
                            runCatching { VelvetWidget().updateAll(context) }
                        }
                    }
                    launch { loadDehum()?.let { dehum = it } }
                    launch {
                        val fresh = loadGas()
                        // przy chwilowym błędzie zostają poprzednie odczyty
                        gas = if (fresh.error != null && gas.readings.isNotEmpty()) gas else fresh
                    }
                }
                delay(REFRESH_MS)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VelvetBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Text(
            text = "VelvetHome",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = Cream,
            letterSpacing = 1.5.sp,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 16.dp, bottom = 12.dp)
        )

        // Pasek zakładek zostaje na górze, przewija się tylko treść pod nim
        TabBar(selected = tab, onSelect = { tab = it }, modifier = Modifier.padding(horizontal = 16.dp))

        // Każda zakładka ma własną pozycję przewijania
        key(tab) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (tab) {
                    HomeTab.TEMPERATURE -> TemperatureContent(rooms, selectedRange) { selectedRange = it }
                    HomeTab.HUMIDITY -> HumidityContent(rooms, dehum, selectedRange) { selectedRange = it }
                    HomeTab.GAS -> GasContent(gas)
                }
            }
        }
    }
}

// Pasek trzech zakładek z ikonami; wybrana na śliwkowym tle ze złotym paskiem z lewej
@Composable
fun TabBar(selected: HomeTab, onSelect: (HomeTab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Panel, RoundedCornerShape(16.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        HomeTab.values().forEach { t ->
            val active = t == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) Plum else Color.Transparent)
                    .drawBehind {
                        if (active) drawRect(Gold, size = Size(3.dp.toPx(), size.height))
                    }
                    .clickable { onSelect(t) }
                    .padding(vertical = 8.dp)
            ) {
                TabIcon(t, Cream)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = t.label,
                    fontSize = 13.sp,
                    color = Cream,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

// Proste ikony rysowane linią: termometr, kropla, płomień
@Composable
fun TabIcon(tab: HomeTab, color: Color) {
    Canvas(modifier = Modifier.size(22.dp)) {
        val w = size.width
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path()
        when (tab) {
            HomeTab.TEMPERATURE -> {
                path.moveTo(w * 0.58f, w * 0.62f)
                path.lineTo(w * 0.58f, w * 0.21f)
                path.cubicTo(w * 0.58f, w * 0.12f, w * 0.42f, w * 0.12f, w * 0.42f, w * 0.21f)
                path.lineTo(w * 0.42f, w * 0.62f)
                drawPath(path, color, style = stroke)
                drawCircle(color, radius = w * 0.17f, center = Offset(w * 0.5f, w * 0.75f), style = stroke)
                drawLine(color, Offset(w * 0.5f, w * 0.46f), Offset(w * 0.5f, w * 0.7f), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
            }
            HomeTab.HUMIDITY -> {
                path.moveTo(w * 0.5f, w * 0.12f)
                path.cubicTo(w * 0.5f, w * 0.12f, w * 0.75f, w * 0.42f, w * 0.75f, w * 0.6f)
                path.cubicTo(w * 0.75f, w * 0.75f, w * 0.64f, w * 0.86f, w * 0.5f, w * 0.86f)
                path.cubicTo(w * 0.36f, w * 0.86f, w * 0.25f, w * 0.75f, w * 0.25f, w * 0.6f)
                path.cubicTo(w * 0.25f, w * 0.42f, w * 0.5f, w * 0.12f, w * 0.5f, w * 0.12f)
                path.close()
                drawPath(path, color, style = stroke)
            }
            HomeTab.GAS -> {
                path.moveTo(w * 0.5f, w * 0.12f)
                path.cubicTo(w * 0.54f, w * 0.27f, w * 0.71f, w * 0.35f, w * 0.71f, w * 0.55f)
                path.cubicTo(w * 0.71f, w * 0.74f, w * 0.62f, w * 0.88f, w * 0.5f, w * 0.88f)
                path.cubicTo(w * 0.38f, w * 0.88f, w * 0.29f, w * 0.74f, w * 0.29f, w * 0.6f)
                path.cubicTo(w * 0.29f, w * 0.5f, w * 0.34f, w * 0.43f, w * 0.38f, w * 0.38f)
                path.cubicTo(w * 0.4f, w * 0.46f, w * 0.44f, w * 0.5f, w * 0.48f, w * 0.5f)
                path.cubicTo(w * 0.46f, w * 0.33f, w * 0.5f, w * 0.25f, w * 0.5f, w * 0.12f)
                path.close()
                drawPath(path, color, style = stroke)
            }
        }
    }
}

// Kafelek jak na stronie: nazwa, duża wartość w kolorze pomieszczenia, godzina i opcjonalnie bateria
@Composable
fun InfoTile(
    label: String,
    value: String,
    valueColor: Color,
    time: String,
    stale: Boolean,
    modifier: Modifier = Modifier,
    battery: String? = null,
    batteryLow: Boolean = false
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .background(Panel, RoundedCornerShape(16.dp))
            .padding(vertical = 16.dp, horizontal = 8.dp)
    ) {
        Text(
            text = label.uppercase(),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            color = Gold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = value, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = valueColor)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (stale) "$time · nieaktualne" else time,
            fontSize = 11.sp,
            color = if (stale) ErrorRed else Cream.copy(alpha = 0.7f)
        )
        if (battery != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "bateria $battery",
                fontSize = 11.sp,
                color = if (batteryLow) ErrorRed else Cream.copy(alpha = 0.8f)
            )
        }
    }
}

// Kafelki pomieszczeń w siatce 2 x 2
@Composable
fun RoomTiles(rooms: RoomsData, tile: @Composable (Room, Modifier) -> Unit) {
    ROOMS.chunked(2).forEach { pair ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            pair.forEach { room -> tile(room, Modifier.weight(1f)) }
            if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
        }
    }
}

// Godzina pomiaru i czy jest nieaktualny
private fun timeInfo(entry: RoomEntry?, failed: Boolean): Pair<String, Boolean> {
    if (entry == null) return (if (failed) "błąd pobierania" else "brak danych") to false
    val local = entry.timestamp.withZoneSameInstant(ZoneId.systemDefault())
    val stale = entry.timestamp.isBefore(ZonedDateTime.now().minusMinutes(STALE_MINUTES))
    return local.format(DateTimeFormatter.ofPattern("HH:mm")) to stale
}

// Stan baterii: procent albo stan z Tuya (low / middle / high) -> opis i czy niski
private fun batteryInfo(raw: String?): Pair<String, Boolean>? {
    if (raw.isNullOrBlank()) return null
    raw.toFloatOrNull()?.let { return "${it.roundToInt()}%" to (it <= 20f) }
    return when (raw.lowercase()) {
        "high" -> "pełna" to false
        "middle" -> "średnia" to false
        "low" -> "niska" to true
        else -> raw to false
    }
}

// Wybór zakresu 24 / 12 / 6 h
@Composable
fun RangeChips(selected: TimeRange, onSelect: (TimeRange) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        TimeRange.values().forEach { range ->
            FilterChip(
                selected = selected == range,
                onClick = { onSelect(range) },
                label = { Text(range.label, fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color.Transparent,
                    labelColor = Cream,
                    selectedContainerColor = Plum,
                    selectedLabelColor = Cream
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selected == range,
                    borderColor = Cream.copy(alpha = 0.5f),
                    selectedBorderColor = Gold
                )
            )
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        color = Cream,
        modifier = Modifier.padding(bottom = 16.dp)
    )
}

// Ładowanie albo błąd; zwraca true, gdy można pokazać dane
@Composable
private fun LoadingOrError(loading: Boolean, error: String?): Boolean {
    when {
        loading -> CircularProgressIndicator(color = Gold)
        error != null -> Text(text = error, color = ErrorRed, fontSize = 16.sp)
        else -> return true
    }
    return false
}

// Pomiary z wybranego zakresu, liczonego od najnowszego pomiaru ze wszystkich pomieszczeń
private fun filterRange(rooms: RoomsData, range: TimeRange): Map<String, List<RoomEntry>> {
    val latest = rooms.data.values.flatten().maxOfOrNull { it.timestamp } ?: return rooms.data
    val cutoff = latest.minusHours(range.hours)
    return rooms.data.mapValues { (_, entries) -> entries.filter { !it.timestamp.isBefore(cutoff) } }
}

// Zakładka Temperatura: kafelki z temperaturą i baterią, zakres, wykres każdego pomieszczenia
@Composable
fun TemperatureContent(rooms: RoomsData, range: TimeRange, onRange: (TimeRange) -> Unit) {
    SectionTitle("Temperatura")
    if (!LoadingOrError(rooms.loading, rooms.error)) return

    RoomTiles(rooms) { room, mod ->
        val entries = rooms.data[room.file].orEmpty()
        val last = entries.lastOrNull()
        val (time, stale) = timeInfo(last, room.file in rooms.failed)
        val bat = batteryInfo(entries.lastOrNull { !it.battery.isNullOrBlank() }?.battery)
        InfoTile(
            label = room.name,
            value = last?.let { String.format("%.1f°", it.temperature) } ?: "–",
            valueColor = room.color,
            time = time,
            stale = stale,
            modifier = mod,
            battery = bat?.first,
            batteryLow = bat?.second ?: false
        )
    }
    RangeChips(range, onRange)
    Spacer(modifier = Modifier.height(8.dp))

    val filtered = remember(rooms, range) { filterRange(rooms, range) }
    ROOMS.forEach { room ->
        val entries = filtered[room.file].orEmpty()
        if (entries.size >= 2) {
            TemperatureChart(title = room.name, entries = entries, lineColor = room.color)
        } else {
            ChartPlaceholder(
                title = room.name,
                message = if (room.file in rooms.failed) "Nie udało się pobrać danych."
                          else "Brak danych dla wybranego zakresu."
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

// Zakładka Wilgotność: kafelki z wilgotnością, zakres, wspólny wykres
@Composable
fun HumidityContent(rooms: RoomsData, dehum: List<DehumEntry>, range: TimeRange, onRange: (TimeRange) -> Unit) {
    SectionTitle("Wilgotność")
    if (!LoadingOrError(rooms.loading, rooms.error)) return

    RoomTiles(rooms) { room, mod ->
        val last = rooms.data[room.file].orEmpty().lastOrNull { it.humidity != null }
        val (time, stale) = timeInfo(last, room.file in rooms.failed)
        InfoTile(
            label = room.name,
            value = last?.humidity?.let { String.format("%.0f%%", it) } ?: "–",
            valueColor = room.color,
            time = time,
            stale = stale,
            modifier = mod
        )
    }
    DehumidifierCard(dehum)
    RangeChips(range, onRange)
    Spacer(modifier = Modifier.height(8.dp))

    val filtered = remember(rooms, range) { filterRange(rooms, range) }
    val roomSeries = ROOMS.mapNotNull { room ->
        val points = filtered[room.file].orEmpty().filter { it.humidity != null }
        if (points.size < 2) null else ChartSeries(
            label = room.name,
            times = points.map { it.timestamp.toEpochSecond() },
            values = points.map { it.humidity!! },
            color = room.color
        )
    }
    // Wilgotność zmierzona przez osuszacz jako dodatkowa (złota) linia, w tym samym zakresie czasu
    val dehumSeries = remember(dehum, rooms, range) {
        val latest = rooms.data.values.flatten().maxOfOrNull { it.timestamp } ?: dehum.lastOrNull()?.timestamp
        val points = if (latest == null) emptyList()
                     else dehum.filter { !it.timestamp.isBefore(latest.minusHours(range.hours)) }
        if (points.size < 2) null else ChartSeries(
            label = "Osuszacz",
            times = points.map { it.timestamp.toEpochSecond() },
            values = points.map { it.humidity },
            color = Gold
        )
    }
    val humiditySeries = roomSeries + listOfNotNull(dehumSeries)
    if (humiditySeries.isEmpty()) {
        ChartPlaceholder(title = "Wilgotność", message = "Brak danych dla wybranego zakresu.")
    } else {
        MultiLineChart(
            title = "Wilgotność",
            series = humiditySeries,
            axisFormat = "%.0f%%",
            valueFormat = "%.0f%%",
            leftMargin = 44.dp,
            minPad = 2f
        )
    }
    Spacer(modifier = Modifier.height(16.dp))
}

// Kafelek osuszacza: stan, wilgotność zmierzona i docelowa, tryb, wentylator, alarmy
@Composable
fun DehumidifierCard(entries: List<DehumEntry>) {
    val last = entries.lastOrNull() ?: return   // bez danych kafelka nie pokazujemy
    val reached = last.mode == 0 && last.humidity <= last.target
    val state = when {
        !last.on -> "Wyłączony"
        last.mode == 1 -> "Osusza ciągle"
        reached -> "Cel osiągnięty"
        else -> "Osusza"
    }
    val stale = last.timestamp.isBefore(ZonedDateTime.now().minusMinutes(STALE_MINUTES))
    val time = last.timestamp.withZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
    val modeText = when {
        last.mode == 1 -> "tryb CONT / TURBO"
        last.target == 60 -> "tryb DRY / COMFORT"
        else -> "tryb DRY"
    }
    val info = listOf(modeText, "wentylator " + if (last.fan >= 2) "wysoki" else "niski",
        if (stale) "$time · nieaktualne" else time).joinToString(" · ")
    val alerts = buildList {
        if ((last.fault and 64) != 0) add("Pełny zbiornik – opróżnij osuszacz")
        listOf("P1", "E3", "E5", "EH", "E1", "E2").forEachIndexed { bit, code ->
            if ((last.fault and (1 shl bit)) != 0) add("Błąd $code")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .background(Panel, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("OSUSZACZ · PRALNIA", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = Gold)
            Text(state, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (last.on) GoodGreen else Cream.copy(alpha = 0.6f))
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${last.humidity.roundToInt()}%", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Gold)
                Text("wilgotność zmierzona", fontSize = 11.sp, color = Cream.copy(alpha = 0.7f))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (last.mode == 1) "ciągłe" else "${last.target}%", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Cream)
                Text("wilgotność docelowa", fontSize = 11.sp, color = Cream.copy(alpha = 0.7f))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = info,
            fontSize = 12.sp,
            color = if (stale) ErrorRed else Cream.copy(alpha = 0.75f),
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        if (alerts.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ErrorRed.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(alerts.joinToString(" · "), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ErrorRed)
            }
        }
    }
}

// Zakładka Licznik gazu: bieżący odczyt, zużycie z 24 h i wykresy
@Composable
fun GasContent(gas: GasData) {
    SectionTitle("Licznik gazu")
    if (!LoadingOrError(gas.loading, gas.error)) return

    val readings = gas.readings
    val latest = readings.last()
    val zone = ZoneId.systemDefault()
    val cutoff = latest.timestamp.minusHours(24)
    val level = remember(readings) { readings.filter { !it.timestamp.isBefore(cutoff) } }
    // Chwilowe zużycie = różnica między kolejnymi odczytami (Δ m³)
    val deltas = remember(readings) {
        readings.zipWithNext().filter { (_, cur) -> !cur.timestamp.isBefore(cutoff) }
    }
    val usage24 = deltas.sumOf { (prev, cur) -> (cur.value - prev.value).coerceAtLeast(0.0) }

    InfoTile(
        label = "Bieżący odczyt",
        value = String.format("%.3f m³", latest.value),
        valueColor = Cream,
        time = latest.timestamp.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")),
        stale = false,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(10.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Panel, RoundedCornerShape(16.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "Ostatnie 24h:", fontSize = 14.sp, color = Cream.copy(alpha = 0.7f))
        Text(text = String.format("%.3f m³", usage24), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Gold)
    }
    Spacer(modifier = Modifier.height(16.dp))

    LineChart(
        title = "Zmiana stanu licznika – ostatnie 24h (m³)",
        times = level.map { it.timestamp.atZone(zone).toEpochSecond() },
        rawValues = level.map { it.value.toFloat() },
        lineColor = Color(0xFFEF6C00),
        axisFormat = "%.3f",
        valueFormat = "%.3f m³",
        leftMargin = 52.dp,
        minPad = 0.005f,
        smoothingWindow = 1,
        showMinMax = false
    )
    Spacer(modifier = Modifier.height(16.dp))
    LineChart(
        title = "Chwilowe zużycie gazu – ostatnie 24h (Δ m³)",
        times = deltas.map { (_, cur) -> cur.timestamp.atZone(zone).toEpochSecond() },
        rawValues = deltas.map { (prev, cur) -> (cur.value - prev.value).toFloat() },
        lineColor = Color(0xFF8E24AA),
        axisFormat = "%.3f",
        valueFormat = "%.3f m³",
        leftMargin = 52.dp,
        minPad = 0.001f,
        smoothingWindow = 1,
        showMinMax = false
    )
    Spacer(modifier = Modifier.height(16.dp))
}

// Wykres temperatury jednego pomieszczenia (opakowanie ogólnego wykresu liniowego)
@Composable
fun TemperatureChart(
    title: String,
    entries: List<RoomEntry>,
    lineColor: Color,
    smoothingWindow: Int = 7
) {
    LineChart(
        title = title,
        times = entries.map { it.timestamp.toEpochSecond() },
        rawValues = entries.map { it.temperature },
        lineColor = lineColor,
        axisFormat = "%.1f°C",
        valueFormat = "%.1f°C",
        leftMargin = 44.dp,
        minPad = 0.5f,
        smoothingWindow = smoothingWindow
    )
}

// Biała karta w miejscu wykresu, gdy nie ma czego narysować
@Composable
fun ChartPlaceholder(title: String, message: String) {
    val darkText = Color(0xFF3A3A3A)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = darkText,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(text = message, fontSize = 14.sp, color = darkText.copy(alpha = 0.7f))
    }
}

// Jedna seria danych na wykresie (np. wilgotność w jednym pomieszczeniu)
data class ChartSeries(
    val label: String,
    val times: List<Long>,      // czas w sekundach epoki, rosnąco
    val values: List<Float>,
    val color: Color
)

// Ogólny wykres liniowy z jedną serią (temperatura, gaz)
@Composable
fun LineChart(
    title: String,
    times: List<Long>,          // czas w sekundach epoki
    rawValues: List<Float>,
    lineColor: Color,
    axisFormat: String,         // format etykiet osi Y
    valueFormat: String,        // format wartości min/max
    leftMargin: Dp = 44.dp,
    minPad: Float = 0.5f,
    smoothingWindow: Int = 7,
    showMinMax: Boolean = true
) {
    MultiLineChart(
        title = title,
        series = listOf(ChartSeries(title, times, rawValues, lineColor)),
        axisFormat = axisFormat,
        valueFormat = valueFormat,
        leftMargin = leftMargin,
        minPad = minPad,
        smoothingWindow = smoothingWindow,
        showMinMax = showMinMax
    )
}

// Wykres liniowy na białym tle z jedną lub kilkoma seriami: wygładzone dane, krzywe Béziera,
// oś X z czasu, siatka. Jedna seria: min/max pod wykresem. Kilka serii: legenda.
@Composable
fun MultiLineChart(
    title: String,
    series: List<ChartSeries>,
    axisFormat: String,         // format etykiet osi Y
    valueFormat: String,        // format wartości min/max
    leftMargin: Dp = 44.dp,
    minPad: Float = 0.5f,
    smoothingWindow: Int = 7,
    showMinMax: Boolean = true
) {
    val darkText = Color(0xFF3A3A3A)
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = darkText.copy(alpha = 0.8f))
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    val gridColor = Color.Black.copy(alpha = 0.12f)

    // Serie z danymi (surowe wartości do okienka) i ich wygładzone wersje do rysowania - te same indeksy
    val rawSeries = remember(series) {
        series.filter { it.values.isNotEmpty() && it.times.size == it.values.size }
    }
    val smoothed = remember(rawSeries, smoothingWindow) {
        rawSeries.map { it.copy(values = smoothValues(it.values, smoothingWindow)) }
    }
    if (smoothed.isEmpty()) return

    val allValues = smoothed.flatMap { it.values }
    val minV = allValues.min()
    val maxV = allValues.max()
    val pad = ((maxV - minV) * 0.1f).coerceAtLeast(minPad)
    val yMin = minV - pad
    val yMax = maxV + pad
    val t0 = smoothed.minOf { it.times.first() }
    val t1 = smoothed.maxOf { it.times.last() }
    val multi = smoothed.size > 1

    // Okienko z wartością po dotknięciu wykresu: (indeks serii, indeks punktu) albo null
    // Zostaje po odświeżeniu danych co minutę; znika przy zmianie zakresu (inna długość osi czasu)
    var selected by remember(title, (t1 - t0) / 3600) { mutableStateOf<Pair<Int, Int>?>(null) }
    val density = LocalDensity.current
    val leftPx = with(density) { leftMargin.toPx() }
    val rightPx = with(density) { 16.dp.toPx() }
    val topPx = with(density) { 8.dp.toPx() }
    val bottomPx = with(density) { 24.dp.toPx() }
    val spanAll = (t1 - t0).coerceAtLeast(1L).toFloat()

    // Położenie punktu na płótnie o rozmiarze w x h
    fun pointPos(si: Int, i: Int, w: Float, h: Float): Offset {
        val s = smoothed[si]
        val x = leftPx + (s.times[i] - t0).toFloat() / spanAll * (w - leftPx - rightPx)
        val y = topPx + (1f - (s.values[i] - yMin) / (yMax - yMin)) * (h - topPx - bottomPx)
        return Offset(x, y)
    }

    // Jedna seria: najbliższy punkt w poziomie. Kilka serii: najbliższy punkt na płaszczyźnie,
    // czyli wybierana jest linia, której dotyka palec.
    fun pick(touch: Offset, w: Float, h: Float): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var bestDist = Float.MAX_VALUE
        smoothed.forEachIndexed { si, s ->
            if (s.times.size < 2) return@forEachIndexed
            for (i in s.times.indices) {
                val q = pointPos(si, i, w, h)
                val d = if (multi) (q - touch).getDistanceSquared() else kotlin.math.abs(q.x - touch.x)
                if (d < bestDist) { bestDist = d; best = si to i }
            }
        }
        return best
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = darkText,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                // Dotknięcie pokazuje okienko, przesuwanie palcem w poziomie przesuwa je po wykresie;
                // przewijanie ekranu w pionie działa dalej
                .pointerInput(smoothed) {
                    detectTapGestures(onPress = { p ->
                        selected = pick(p, size.width.toFloat(), size.height.toFloat())
                    })
                }
                .pointerInput(smoothed) {
                    detectHorizontalDragGestures(
                        onDragStart = { p -> selected = pick(p, size.width.toFloat(), size.height.toFloat()) },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            selected = pick(change.position, size.width.toFloat(), size.height.toFloat())
                        }
                    )
                }
        ) {
            val left = leftMargin.toPx()
            val right = 16.dp.toPx()
            val top = 8.dp.toPx()
            val bottom = 24.dp.toPx()
            val chartWidth = size.width - left - right
            val chartHeight = size.height - top - bottom
            if (smoothed.all { it.times.size < 2 }) return@Canvas

            val span = (t1 - t0).coerceAtLeast(1L).toFloat()
            val gridStroke = 1.dp.toPx()

            // Siatka + opisy osi Y
            val yLabelsCount = 4
            for (i in 0 until yLabelsCount) {
                val fraction = i.toFloat() / (yLabelsCount - 1)
                val value = yMax - fraction * (yMax - yMin)
                val y = top + fraction * chartHeight

                drawLine(
                    color = gridColor,
                    start = Offset(left, y),
                    end = Offset(left + chartWidth, y),
                    strokeWidth = gridStroke
                )
                drawText(
                    textMeasurer = textMeasurer,
                    text = String.format(axisFormat, value),
                    style = labelStyle,
                    topLeft = Offset(4.dp.toPx(), y - 8.dp.toPx())
                )
            }

            // Siatka + opisy osi X (godziny)
            val xLabelsCount = 4
            for (i in 0 until xLabelsCount) {
                val fraction = i.toFloat() / (xLabelsCount - 1)
                val x = left + fraction * chartWidth
                val timeStr = Instant.ofEpochSecond(t0 + (span * fraction).toLong())
                    .atZone(ZoneId.systemDefault())
                    .format(timeFormatter)

                drawLine(
                    color = gridColor,
                    start = Offset(x, top),
                    end = Offset(x, top + chartHeight),
                    strokeWidth = gridStroke
                )
                // Etykieta wyśrodkowana pod linią, ale nie wychodzi poza wykres (ostatnia nie łamie się w dwóch wierszach)
                val timeLayout = textMeasurer.measure(text = timeStr, style = labelStyle)
                val labelX = (x - timeLayout.size.width / 2f)
                    .coerceIn(0f, (size.width - timeLayout.size.width).coerceAtLeast(0f))
                drawText(
                    textLayoutResult = timeLayout,
                    topLeft = Offset(labelX, size.height - 20.dp.toPx())
                )
            }

            val yLow = top
            val yHigh = top + chartHeight
            smoothed.forEachIndexed { si, s ->
                if (s.times.size < 2) return@forEachIndexed
                // Kilka serii z wybraną linią: pozostałe są przygaszone
                val lineColor = if (multi && selected != null && selected?.first != si) s.color.copy(alpha = 0.25f) else s.color

                // Punkty serii
                val points = s.times.indices.map { i ->
                    val xFraction = (s.times[i] - t0).toFloat() / span
                    val yFraction = (s.values[i] - yMin) / (yMax - yMin)
                    Offset(
                        left + xFraction * chartWidth,
                        top + (1f - yFraction) * chartHeight
                    )
                }

                // Gładka krzywa
                val path = Path()
                path.moveTo(points[0].x, points[0].y)
                for (i in 1 until points.size) {
                    val p0 = points[(i - 2).coerceAtLeast(0)]
                    val p1 = points[i - 1]
                    val p2 = points[i]
                    val p3 = points[(i + 1).coerceAtMost(points.lastIndex)]

                    val dx = p2.x - p1.x
                    val dx1 = p2.x - p0.x
                    val dx2 = p3.x - p1.x
                    val m1 = if (dx1 > 0f) (p2.y - p0.y) / dx1 else 0f
                    val m2 = if (dx2 > 0f) (p3.y - p1.y) / dx2 else 0f

                    val c1x = p1.x + dx / 3f
                    val c1y = (p1.y + m1 * dx / 3f).coerceIn(yLow, yHigh)
                    val c2x = p2.x - dx / 3f
                    val c2y = (p2.y - m2 * dx / 3f).coerceIn(yLow, yHigh)

                    path.cubicTo(c1x, c1y, c2x, c2y, p2.x, p2.y)
                }

                drawPath(
                    path = path,
                    color = lineColor,
                    style = Stroke(
                        width = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }

            // Okienko z wybranym pomiarem: pionowa linia, punkt i ramka z godziną i wartością
            selected?.let { (si, i) ->
                if (si >= smoothed.size || i >= smoothed[si].times.size) return@let
                val s = smoothed[si]
                val pt = pointPos(si, i, size.width, size.height)
                drawLine(
                    color = darkText.copy(alpha = 0.35f),
                    start = Offset(pt.x, top),
                    end = Offset(pt.x, top + chartHeight),
                    strokeWidth = 1.dp.toPx()
                )
                drawCircle(color = Color.White, radius = 6.dp.toPx(), center = pt)
                drawCircle(color = s.color, radius = 4.dp.toPx(), center = pt)

                val time = Instant.ofEpochSecond(s.times[i]).atZone(ZoneId.systemDefault()).format(timeFormatter)
                val value = String.format(valueFormat, rawSeries[si].values[i])
                val text = if (multi) "$time  ${s.label}: $value" else "$time  $value"
                val layout = textMeasurer.measure(
                    text = text,
                    style = TextStyle(fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                )
                val padX = 8.dp.toPx()
                val padY = 5.dp.toPx()
                val boxW = layout.size.width + 2 * padX
                val boxH = layout.size.height + 2 * padY
                val boxX = (pt.x - boxW / 2).coerceIn(0f, (size.width - boxW).coerceAtLeast(0f))
                val above = pt.y - boxH - 10.dp.toPx()
                val boxY = if (above >= 0f) above else pt.y + 10.dp.toPx()
                drawRoundRect(
                    color = Color(0xE62A0B1C),
                    topLeft = Offset(boxX, boxY),
                    size = Size(boxW, boxH),
                    cornerRadius = CornerRadius(6.dp.toPx())
                )
                drawText(textLayoutResult = layout, topLeft = Offset(boxX + padX, boxY + padY))
            }
        }

        if (smoothed.size > 1) {
            Spacer(modifier = Modifier.height(8.dp))

            // Legenda: kolor linii i nazwa serii
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                smoothed.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(width = 12.dp, height = 3.dp)
                                .background(s.color, RoundedCornerShape(2.dp))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = s.label, fontSize = 12.sp, color = darkText.copy(alpha = 0.8f))
                    }
                }
            }
        } else if (showMinMax) {
            Spacer(modifier = Modifier.height(8.dp))

            // Min/max z wybranego zakresu czasu (z danych przed wygładzeniem)
            MinMaxRow(
                values = series.first().values,
                valueFormat = valueFormat,
                lineColor = series.first().color
            )
        }
    }
}

// Wiersz z minimalną i maksymalną wartością z aktualnie wybranego zakresu
@Composable
fun MinMaxRow(
    values: List<Float>,
    valueFormat: String,
    lineColor: Color
) {
    val labelColor = Color(0xFF3A3A3A).copy(alpha = 0.7f)
    if (values.isEmpty()) return

    val minValue = values.min()
    val maxValue = values.max()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Minimum", fontSize = 12.sp, color = labelColor)
            Text(
                text = String.format(valueFormat, minValue),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = lineColor
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Maksimum", fontSize = 12.sp, color = labelColor)
            Text(
                text = String.format(valueFormat, maxValue),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = lineColor
            )
        }
    }
}