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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

// Miękka krzywa łagodzenia (odpowiednik EaseInOutSine)
val SoftEasing = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)

// Wspólne tło aksamitne (używane na wszystkich ekranach)
val VelvetBackground = Brush.verticalGradient(listOf(Color(0xFF4A1233), Color(0xFF1A0613)))

// 1. Definicje interfejsów sieciowych dla Retrofit
interface TemperatureApi {
    // Plik pomieszczenia z repozytorium velvethome.github.io, np. "temperatures_salon.txt"
    @GET("witoldaksamit-ui/velvethome.github.io/main/{file}")
    suspend fun getRoomFile(@Path("file") file: String): String
}

interface GasApi {
    // Surowy plik z odczytami licznika gazu (format: "2026-10-30 16:07:24 | 106.973")
    @GET("witoldaksamit-ui/licznik_gazu/main/odczyty24h.txt")
    suspend fun getReadings(): String
}

// 2. Model danych dla jednego pomiaru w pomieszczeniu (temperatura i wilgotność)
data class RoomEntry(
    val timestamp: ZonedDateTime,
    val temperature: Float,
    val humidity: Float?        // null, gdy w pliku nie ma wilgotności
)

// Pomieszczenie: nazwa na ekranie, plik z danymi i kolor
data class Room(val name: String, val file: String, val color: Color)

// Czujnik "sypialnia" wisi za oknem, dlatego na ekranie nazywa się "Na zewnątrz"
val ROOMS = listOf(
    Room("Salon", "temperatures_salon.txt", Color(0xFF66BB6A)),
    Room("Na zewnątrz", "temperatures_sypialnia.txt", Color(0xFF42A5F5)),
    Room("Pralnia", "temperatures_pralnia.txt", Color(0xFF26A69A))
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
    SPLASH, MENU, TEMPERATURE, GAS
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

    // Logika przejścia ze Splash Screena do Menu głównego
    if (currentScreen == AppScreen.SPLASH) {
        LaunchedEffect(Unit) {
            delay(4000) // czas wyświetlania splasha
            currentScreen = AppScreen.MENU
        }
    }

    when (currentScreen) {
        // Płynne przejście wyłącznie między splashem a pierwszą kartą (menu)
        AppScreen.SPLASH, AppScreen.MENU -> {
            Crossfade(
                targetState = currentScreen,
                animationSpec = tween(durationMillis = 1000, easing = SoftEasing),
                label = "splash_to_menu"
            ) { screen ->
                when (screen) {
                    AppScreen.SPLASH -> SplashScreen()
                    AppScreen.MENU -> MenuScreen(
                        onNavigateToTemperature = { currentScreen = AppScreen.TEMPERATURE },
                        onNavigateToGas = { currentScreen = AppScreen.GAS }
                    )
                    else -> Unit
                }
            }
        }
        // Pozostałe przejścia są twarde (bez animacji)
        AppScreen.TEMPERATURE -> TemperatureScreen(
            onBackToMenu = { currentScreen = AppScreen.MENU }
        )
        AppScreen.GAS -> GasScreen(
            onBackToMenu = { currentScreen = AppScreen.MENU }
        )
    }
}

// Menu wyboru po ekranie powitalnym
@Composable
fun MenuScreen(
    onNavigateToTemperature: () -> Unit,
    onNavigateToGas: () -> Unit
) {
    val cream = Color(0xFFF7E9D7)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VelvetBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = "VelvetHome",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = cream,
                modifier = Modifier.padding(bottom = 48.dp)
            )

            Button(
                onClick = onNavigateToTemperature,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C2A5E)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(text = "Temperatura", fontSize = 18.sp, color = cream, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onNavigateToGas,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E1239)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(text = "Licznik Gazu", fontSize = 18.sp, color = cream, fontWeight = FontWeight.SemiBold)
            }
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
                humidity = tokens.getOrNull(2)?.trim()?.toFloatOrNull()
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

// Ekran temperatury i wilgotności: salon, na zewnątrz i pralnia
@Composable
fun TemperatureScreen(onBackToMenu: () -> Unit) {
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // nazwa pliku -> pomiary (tylko pliki pobrane bez błędu)
    var roomData by remember { mutableStateOf<Map<String, List<RoomEntry>>>(emptyMap()) }
    var failedRooms by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedRange by remember { mutableStateOf(TimeRange.LAST_24H) }
    val cream = Color(0xFFF7E9D7)
    val gold = Color(0xFFFFC96B)
    val mutedText = cream.copy(alpha = 0.7f)

    LaunchedEffect(Unit) {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://raw.githubusercontent.com/")
            .addConverterFactory(ScalarsConverterFactory.create())
            .build()
        val api = retrofit.create(TemperatureApi::class.java)

        // Pliki pobierane równolegle; błąd jednego nie blokuje pozostałych
        val results = withContext(Dispatchers.IO) {
            ROOMS.map { room ->
                async { room.file to runCatching { parseRoomData(api.getRoomFile(room.file)) } }
            }.awaitAll()
        }

        roomData = results
            .mapNotNull { (file, result) -> result.getOrNull()?.let { file to it } }
            .toMap()
        failedRooms = results.filter { it.second.isFailure }.map { it.first }.toSet()
        if (roomData.isEmpty()) {
            val cause = results.firstNotNullOfOrNull { it.second.exceptionOrNull() }
            errorMessage = "Błąd pobierania danych: ${cause?.localizedMessage}"
        }
        isLoading = false
    }

    // Najnowszy pomiar ze wszystkich pomieszczeń - od niego liczymy wybrany zakres
    val latestTime = remember(roomData) {
        roomData.values.flatten().maxOfOrNull { it.timestamp }
    }
    val filteredData = remember(roomData, selectedRange) {
        val cutoff = latestTime?.minusHours(selectedRange.hours)
        roomData.mapValues { (_, entries) ->
            if (cutoff == null) entries else entries.filter { !it.timestamp.isBefore(cutoff) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VelvetBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Pasek nawigacyjny z przyciskiem powrotu do Menu
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            TextButton(onClick = onBackToMenu) {
                Text(text = "← Powrót", color = cream, fontSize = 16.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            TimeRange.values().forEach { range ->
                FilterChip(
                    selected = selectedRange == range,
                    onClick = { selectedRange = range },
                    label = { Text(range.label, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color.Transparent,
                        labelColor = cream,
                        selectedContainerColor = Color(0xFF9C2A5E),
                        selectedLabelColor = cream
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selectedRange == range,
                        borderColor = cream.copy(alpha = 0.5f),
                        selectedBorderColor = gold
                    )
                )
            }
        }

        if (latestTime != null) {
            val lastUpdate = latestTime
                .withZoneSameInstant(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
            Text(
                text = "Ostatnia aktualizacja: $lastUpdate",
                fontSize = 14.sp,
                color = mutedText
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        Text(
            text = "Temperatura – ${selectedRange.label}",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = cream,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        when {
            isLoading -> {
                CircularProgressIndicator(
                    color = gold,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
            errorMessage != null -> {
                Text(text = errorMessage!!, color = Color(0xFFFF8A80), fontSize = 16.sp)
            }
            else -> {
                // Bieżące odczyty: temperatura i wilgotność w każdym pomieszczeniu
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    ROOMS.forEach { room ->
                        val last = roomData[room.file]?.lastOrNull()
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(room.name, fontSize = 14.sp, color = mutedText)
                            Text(
                                text = last?.let { String.format("%.1f°C", it.temperature) } ?: "–",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = room.color
                            )
                            Text(
                                text = last?.humidity?.let { String.format("%.0f%%", it) } ?: "",
                                fontSize = 14.sp,
                                color = cream
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Wykres temperatury dla każdego pomieszczenia
                ROOMS.forEach { room ->
                    val entries = filteredData[room.file].orEmpty()
                    if (entries.size >= 2) {
                        TemperatureChart(title = room.name, entries = entries, lineColor = room.color)
                    } else {
                        ChartPlaceholder(
                            title = room.name,
                            message = if (room.file in failedRooms) "Nie udało się pobrać danych."
                                      else "Brak danych dla wybranego zakresu."
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }

                // Wilgotność: wszystkie pomieszczenia na jednym wykresie
                val humiditySeries = ROOMS.mapNotNull { room ->
                    val points = filteredData[room.file].orEmpty().filter { it.humidity != null }
                    if (points.size < 2) null else ChartSeries(
                        label = room.name,
                        times = points.map { it.timestamp.toEpochSecond() },
                        values = points.map { it.humidity!! },
                        color = room.color
                    )
                }
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
        }
    }
}

// Ekran licznika gazu: aktualny stan + wykresy z ostatnich 24h
@Composable
fun GasScreen(onBackToMenu: () -> Unit) {
    val cream = Color(0xFFF7E9D7)
    var readings by remember { mutableStateOf<List<GasReading>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try {
            val retrofit = Retrofit.Builder()
                .baseUrl("https://raw.githubusercontent.com/")
                .addConverterFactory(ScalarsConverterFactory.create())
                .build()

            val api = retrofit.create(GasApi::class.java)
            val rawText = withContext(Dispatchers.IO) { api.getReadings() }

            readings = parseGasReadings(rawText)
            if (readings.isEmpty()) errorMessage = "Brak odczytów w pliku"
        } catch (e: Exception) {
            errorMessage = "Błąd pobierania danych: ${e.localizedMessage}"
        } finally {
            isLoading = false
        }
    }

    val latestReading = readings.lastOrNull()
    val zone = ZoneId.systemDefault()

    // Dane do wykresów z ostatnich 24h (względem ostatniego odczytu)
    val cutoff = latestReading?.timestamp?.minusHours(24)
    val levelReadings = remember(readings) {
        if (cutoff == null) emptyList() else readings.filter { !it.timestamp.isBefore(cutoff) }
    }
    // Chwilowe zużycie = różnica między kolejnymi odczytami (Δ m³)
    val deltaTimes = remember(readings) {
        if (cutoff == null) emptyList() else
            readings.drop(1).filter { !it.timestamp.isBefore(cutoff) }
                .map { it.timestamp.atZone(zone).toEpochSecond() }
    }
    val deltaValues = remember(readings) {
        if (cutoff == null) emptyList() else
            readings.zipWithNext()
                .filter { (_, cur) -> !cur.timestamp.isBefore(cutoff) }
                .map { (prev, cur) -> (cur.value - prev.value).toFloat() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VelvetBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            TextButton(onClick = onBackToMenu) {
                Text(text = "← Powrót", color = cream, fontSize = 16.sp)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        latestReading?.let {
            Text(
                text = "Ostatnia aktualizacja: " +
                        it.timestamp.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")),
                fontSize = 14.sp,
                color = cream.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        Text(text = "Stan Licznika Gazu", fontSize = 24.sp, color = cream, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(32.dp))

        when {
            isLoading -> CircularProgressIndicator(color = Color(0xFFFFC96B))
            errorMessage != null -> Text(
                text = errorMessage!!,
                color = Color(0xFFFF8A80),
                fontSize = 16.sp
            )
            else -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A0B1C)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "BIEŻĄCY ODCZYT",
                            fontSize = 12.sp,
                            color = Color(0xFFFFC96B),
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = String.format("%.3f m³", latestReading!!.value),
                            fontSize = 42.sp,
                            fontWeight = FontWeight.Bold,
                            color = cream,
                            letterSpacing = 1.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Wykres 1: skumulowany stan licznika z ostatnich 24h
                LineChart(
                    title = "Zmiana stanu licznika – ostatnie 24h (m³)",
                    times = levelReadings.map { it.timestamp.atZone(zone).toEpochSecond() },
                    rawValues = levelReadings.map { it.value.toFloat() },
                    lineColor = Color(0xFFEF6C00),
                    axisFormat = "%.3f",
                    valueFormat = "%.3f m³",
                    leftMargin = 52.dp,
                    minPad = 0.005f,
                    smoothingWindow = 1,
                    showMinMax = false
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Wykres 2: chwilowe zużycie (Δ m³ między odczytami)
                LineChart(
                    title = "Chwilowe zużycie gazu – ostatnie 24h (Δ m³)",
                    times = deltaTimes,
                    rawValues = deltaValues,
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
        }
    }
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

    // Wygładzone serie; puste i niespójne są pomijane
    val smoothed = remember(series, smoothingWindow) {
        series
            .filter { it.values.isNotEmpty() && it.times.size == it.values.size }
            .map { it.copy(values = smoothValues(it.values, smoothingWindow)) }
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
                drawText(
                    textMeasurer = textMeasurer,
                    text = timeStr,
                    style = labelStyle,
                    topLeft = Offset(x - 12.dp.toPx(), size.height - 20.dp.toPx())
                )
            }

            val yLow = top
            val yHigh = top + chartHeight
            for (s in smoothed) {
                if (s.times.size < 2) continue

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
                    color = s.color,
                    style = Stroke(
                        width = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
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