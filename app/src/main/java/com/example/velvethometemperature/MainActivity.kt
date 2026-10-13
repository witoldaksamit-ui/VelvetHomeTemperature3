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
import androidx.compose.ui.graphics.toArgb
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

import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import java.time.Instant


// 1. Definicja interfejsu sieciowego dla Retrofit (Pobieranie z Raw GitHub)
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


// Splash screen + przejście do głównego ekranu
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
        delay(2000) // czas wyświetlania splasha w ms
        showSplash = false
    }


    if (showSplash) SplashScreen() else TemperatureScreen()
}


// 3. Główny ekran aplikacji zarządzający stanem
@Composable
fun TemperatureScreen() {
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var temperatureList by remember { mutableStateOf<List<TemperatureEntry>>(emptyList()) }


    LaunchedEffect(Unit) {
        try {
            val retrofit = Retrofit.Builder()
                .baseUrl("https://raw.githubusercontent.com/")
                .addConverterFactory(ScalarsConverterFactory.create())
                .build()


            val api = retrofit.create(TemperatureApi::class.java)


            // Pobieranie danych w tle
            val rawText = withContext(Dispatchers.IO) {
                api.getRawData()
            }


            // Parsowanie i filtrowanie ostatnich 24 godzin
            temperatureList = parseCsvData(rawText)
            isLoading = false
        } catch (e: Exception) {
            errorMessage = "Błąd pobierania danych: ${e.localizedMessage}"
            isLoading = false
        }
    }


    // Wygląd interfejsu użytkownika (UI)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Temperatura z ostatnich 24h",
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
            temperatureList.isEmpty() -> {
                Text(text = "Brak danych z ostatnich 24 godzin.", fontSize = 16.sp)
            }
            else -> {
                // Wyświetlanie aktualnych (ostatnich) temperatur nad wykresami
                val latest = temperatureList.last()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
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


                // Data i godzina ostatniej aktualizacji danych
                val lastUpdate = latest.timestamp
                    .withZoneSameInstant(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
                Text(
                    text = "Ostatnia aktualizacja: $lastUpdate",
                    fontSize = 14.sp,
                    color = Color.Gray
                )


                Spacer(modifier = Modifier.height(16.dp))


                // Wykres 1: temperatura w domu
                TemperatureChart(
                    title = "Dom",
                    entries = temperatureList,
                    selector = { it.tempIn },
                    lineColor = Color(0xFF4CAF50)
                )


                Spacer(modifier = Modifier.height(24.dp))


                // Wykres 2: temperatura na dworze
                TemperatureChart(
                    title = "Dwór",
                    entries = temperatureList,
                    selector = { it.tempOut },
                    lineColor = Color(0xFF2196F3)
                )
            }
        }
    }
}


// 4. Komponent wykresu z WYŁĄCZONĄ siatką w wersji Vico 2.x
@Composable
fun TemperatureChart(
    title: String,
    entries: List<TemperatureEntry>,
    selector: (TemperatureEntry) -> Float,
    lineColor: Color
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = Color.Gray)

    val values = remember(entries) { entries.map(selector) }
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
            val left = 44.dp.toPx()
            val right = 16.dp.toPx()
            val top = 8.dp.toPx()
            val bottom = 24.dp.toPx()
            val w = size.width - left - right
            val h = size.height - top - bottom

            val t0 = entries.first().timestamp.toEpochSecond()
            val span = (entries.last().timestamp.toEpochSecond() - t0).coerceAtLeast(1L)

            fun xOf(e: TemperatureEntry) =
                left + (e.timestamp.toEpochSecond() - t0).toFloat() / span * w

            fun yOf(v: Float) =
                top + (1f - (v - yMin) / (yMax - yMin)) * h

            // Etykiety osi Y (5 wartości)
            for (i in 0..4) {
                val v = yMin + (yMax - yMin) * i / 4f
                val layout = textMeasurer.measure("%.1f".format(v), labelStyle)
                drawText(
                    layout,
                    topLeft = Offset(
                        left - layout.size.width - 6.dp.toPx(),
                        yOf(v) - layout.size.height / 2f
                    )
                )
            }

            // Etykiety osi X (początek, środek, koniec)
            val fmt = DateTimeFormatter.ofPattern("HH:mm")
            for (i in 0..2) {
                val ts = t0 + span * i / 2
                val text = Instant.ofEpochSecond(ts)
                    .atZone(ZoneId.systemDefault())
                    .format(fmt)
                val layout = textMeasurer.measure(text, labelStyle)
                val cx = left + w * i / 2f
                drawText(
                    layout,
                    topLeft = Offset(
                        (cx - layout.size.width / 2f)
                            .coerceIn(0f, size.width - layout.size.width),
                        size.height - bottom + 4.dp.toPx()
                    )
                )
            }

            // Linia wykresu
            val path = Path()
            entries.forEachIndexed { i, e ->
                val px = xOf(e)
                val py = yOf(selector(e))
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(
                    width = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}


// 5. Funkcja parsująca pobrany tekst CSV/TXT (Dokończona)
fun parseCsvData(rawText: String): List<TemperatureEntry> {
    val now = ZonedDateTime.now()
    val twentyFourHoursAgo = now.minusHours(24)
    val entries = mutableListOf<TemperatureEntry>()


    rawText.lineSequence().forEach { line ->
        if (line.isNotBlank()) {
            try {
                val columns = line.split(",")
                if (columns.size >= 3) {
                    val timestamp = ZonedDateTime.parse(columns[0].trim())
                    val tempIn = columns[1].trim().toFloat()
                    val tempOut = columns[2].trim().toFloat()


                    // Filtrowanie wpisów tylko z ostatnich 24 godzin
                    if (timestamp.isAfter(twentyFourHoursAgo)) {
                        entries.add(TemperatureEntry(timestamp, tempIn, tempOut))
                    }
                }
            } catch (e: Exception) {
                // Pomiń niepoprawnie sformatowane linie danych
            }
        }
    }
    return entries
}
