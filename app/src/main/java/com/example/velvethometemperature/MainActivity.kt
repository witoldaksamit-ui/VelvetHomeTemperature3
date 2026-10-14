package com.example.velvethometemperature

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.geometry.Offset

import androidx.compose.foundation.Canvas
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle



// 1. Definicja interfejsu sieciowego dla Retrofit
interface TemperatureApi {
    @GET("witoldaksamit-ui/velvethome.github.io/main/temperatures.txt")
    suspend fun getRawData(): String
}

// 2. Model danych dla jednego pomiaru temperatury
data class TemperatureEntry(
    val timestamp: ZonedDateTime,
    val tempIn: Float,
    val tempOut: Float
)

// Definicja dostępnych zakresów czasowych
enum class TimeRange(val hours: Long, val label: String) {
    LAST_24H(24L, "Ostatnie 24h"),
    LAST_12H(12L, "Ostatnie 12h"),
    LAST_6H(6L, "Ostatnie 6h")
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
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "VelvetHome",
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun AppRoot() {
    var showSplash by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(2000)
        showSplash = false
    }

    if (showSplash) SplashScreen() else TemperatureScreen()
}

// Dummy funkcja parsowania (zamień na swoją właściwą implementację)
fun parseCsvData(rawText: String): List<TemperatureEntry> {
    val entries = mutableListOf<TemperatureEntry>()

    // Formater dopasowany do Twojego formatu: 2026-10-13T03:50:34+02:00
    val formatter = DateTimeFormatter.ISO_DATE_TIME

    val lines = rawText.lines()

    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue

        try {
            val tokens = trimmed.split(",")

            if (tokens.size >= 3) {
                // tokens[0] = "2026-10-13T03:50:34+02:00"
                // tokens[1] = "19.125" (Dom)
                // tokens[2] = "8.19" (Dwór)

                val timestamp = ZonedDateTime.parse(tokens[0].trim(), formatter)
                val tempIn = tokens[1].trim().toFloat()
                val tempOut = tokens[2].trim().toFloat()

                entries.add(TemperatureEntry(timestamp, tempIn, tempOut))
            }
        } catch (e: Exception) {
            // Logowanie błędu parsowania konkretnej linii w celu łatwiejszego debugowania
            android.util.Log.e("CSV_PARSE", "Błąd w linii: $trimmed", e)
        }
    }

    return entries.sortedBy { it.timestamp }
}

// 3. Główny ekran aplikacji zarządzający stanem
@Composable
fun TemperatureScreen() {
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Przechowujemy pełną listę pobranych wpisów
    var allTemperatures by remember { mutableStateOf<List<TemperatureEntry>>(emptyList()) }

    // Stan wybranego zakresu czasu (domyślnie 24h)
    var selectedRange by remember { mutableStateOf(TimeRange.LAST_24H) }

    LaunchedEffect(Unit) {
        try {
            val retrofit = Retrofit.Builder()
                .baseUrl("https://raw.githubusercontent.com/")
                .addConverterFactory(ScalarsConverterFactory.create())
                .build()

            val api = retrofit.create(TemperatureApi::class.java)

            val rawText = withContext(Dispatchers.IO) {
                api.getRawData()
            }

            allTemperatures = parseCsvData(rawText)
            isLoading = false
        } catch (e: Exception) {
            errorMessage = "Błąd pobierania danych: ${e.localizedMessage}"
            isLoading = false
        }
    }

    // Dynamicznie przefiltrowana lista na podstawie zaznaczonego przycisku zakresu
    val filteredTemperatures = remember(allTemperatures, selectedRange) {
        if (allTemperatures.isEmpty()) return@remember emptyList()

        // Wyznaczamy granicę czasową na podstawie najnowszego pomiaru w pliku
        val latestTime = allTemperatures.maxByOrNull { it.timestamp }?.timestamp ?: ZonedDateTime.now()
        val cutoffTime = latestTime.minusHours(selectedRange.hours)

        allTemperatures.filter { it.timestamp.isAfter(cutoffTime) || it.timestamp.isEqual(cutoffTime) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- PRZYCISKI ZAKRESU OSI X ---
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
                    label = { Text(range.label, fontSize = 12.sp) }
                )
            }
        }

        Text(
            text = "Temperatura – ${selectedRange.label}",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        when {
            isLoading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            errorMessage != null -> {
                Text(text = errorMessage!!, color = Color.Red, fontSize = 16.sp)
            }
            allTemperatures.isEmpty() -> {
                Text(text = "Brak danych pomiarowych.", fontSize = 16.sp)
            }
            filteredTemperatures.isEmpty() -> {
                Text(text = "Brak danych dla wybranego zakresu.", fontSize = 16.sp)
            }
            else -> {
                val latest = allTemperatures.last()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Dom", fontSize = 14.sp, color = Color.Gray)
                        Text("${latest.tempIn}°C", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Dwór", fontSize = 14.sp, color = Color.Gray)
                        Text("${latest.tempOut}°C", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2196F3))
                    }
                }

                val lastUpdate = latest.timestamp
                    .withZoneSameInstant(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
                Text(
                    text = "Ostatnia aktualizacja: $lastUpdate",
                    fontSize = 14.sp,
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Wykres 1: temperatura w domu (zasilany przefiltrowanymi danymi)
                TemperatureChart(
                    title = "Dom",
                    entries = filteredTemperatures,
                    selector = { it.tempIn },
                    lineColor = Color(0xFF4CAF50)
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Wykres 2: temperatura na dworze (zasilany przefiltrowanymi danymi)
                TemperatureChart(
                    title = "Dwór",
                    entries = filteredTemperatures,
                    selector = { it.tempOut },
                    lineColor = Color(0xFF2196F3)
                )
            }
        }
    }
}

// 4. Komponent wykresu
@Composable
fun TemperatureChart(
    title: String,
    entries: List<TemperatureEntry>,
    selector: (TemperatureEntry) -> Float,
    lineColor: Color
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = Color.Gray)
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    val values = remember(entries) { entries.map(selector) }
    if (values.isEmpty()) return

    // Wyznaczanie zakresu osi Y z małym marginesem
    val minV = values.min()
    val maxV = values.max()
    val pad = ((maxV - minV) * 0.1f).coerceAtLeast(0.5f)
    val yMin = minV - pad
    val yMax = maxV + pad

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
        ) {
            // Marginesy wykresu (w pikselach)
            val left = 44.dp.toPx()
            val right = 16.dp.toPx()
            val top = 8.dp.toPx()
            val bottom = 24.dp.toPx()

            val chartWidth = size.width - left - right
            val chartHeight = size.height - top - bottom

            if (entries.size < 2) return@Canvas

            // --- RYSOWANIE OSI Y (Wartości temperatur) ---
            val yLabelsCount = 4
            for (i in 0 until yLabelsCount) {
                val fraction = i.toFloat() / (yLabelsCount - 1)
                val value = yMax - fraction * (yMax - yMin)
                val y = top + fraction * chartHeight

                drawText(
                    textMeasurer = textMeasurer,
                    text = String.format("%.1f°C", value),
                    style = labelStyle,
                    topLeft = Offset(4.dp.toPx(), y - 8.dp.toPx())
                )
            }

            // --- RYSOWANIE OSI X (Godziny) ---
            val xLabelsCount = 4
            for (i in 0 until xLabelsCount) {
                val fraction = i.toFloat() / (xLabelsCount - 1)
                val index = (fraction * (entries.size - 1)).toInt()
                val entry = entries[index]
                val x = left + fraction * chartWidth

                val timeStr = entry.timestamp.format(timeFormatter)
                drawText(
                    textMeasurer = textMeasurer,
                    text = timeStr,
                    style = labelStyle,
                    topLeft = Offset(x - 12.dp.toPx(), size.height - 20.dp.toPx())
                )
            }

            // --- OBLICZANIE I RYSOWANIE LINII WYKRESU ---
            val path = Path()
            for (i in entries.indices) {
                val entry = entries[i]
                val value = selector(entry)

                // Skalowanie osi X (pozycja punktu w czasie)
                val xFraction = i.toFloat() / (entries.size - 1)
                val x = left + xFraction * chartWidth

                // Skalowanie osi Y (wysokość punktu dla temperatury)
                val yFraction = (value - yMin) / (yMax - yMin)
                val y = top + (1f - yFraction) * chartHeight

                if (i == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }

            // Nakładamy linię na Canvas
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
    }
}