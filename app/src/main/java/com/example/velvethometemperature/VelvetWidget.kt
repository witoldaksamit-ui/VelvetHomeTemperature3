package com.example.velvethometemperature

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

// ---------- Widget 2 x 1: temperatura w salonie i na dworze ----------

// Ostatni pomiar jednego pomieszczenia na widgecie (null, gdy nie udało się pobrać)
data class WidgetReading(val temperature: Float, val time: ZonedDateTime)

data class WidgetData(val salon: WidgetReading?, val dwor: WidgetReading?)

private val SALON = ROOMS.first { it.file == "temperatures_salon.txt" }
private val DWOR = ROOMS.first { it.file == "temperatures_dwor.txt" }
private const val WIDGET_STALE_MINUTES = 60L

// Pobiera ostatni pomiar salonu i dworu (oba pliki równolegle)
suspend fun loadWidgetData(): WidgetData = withContext(Dispatchers.IO) {
    val api = Retrofit.Builder()
        .baseUrl("https://raw.githubusercontent.com/")
        .addConverterFactory(ScalarsConverterFactory.create())
        .build()
        .create(TemperatureApi::class.java)
    suspend fun last(room: Room): WidgetReading? = runCatching {
        parseRoomData(api.getRoomFile(room.file, System.currentTimeMillis())).lastOrNull()
            ?.let { WidgetReading(it.temperature, it.timestamp) }
    }.getOrNull()
    val salon = async { last(SALON) }
    val dwor = async { last(DWOR) }
    WidgetData(salon.await(), dwor.await())
}

class VelvetWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadWidgetData()
        provideContent { WidgetContent(data) }
    }
}

@Composable
private fun WidgetContent(data: WidgetData) {
    val cream = Color(0xFFF7E9D7)
    val newest = listOfNotNull(data.salon?.time, data.dwor?.time).maxOrNull()
    val stale = newest == null || newest.isBefore(ZonedDateTime.now().minusMinutes(WIDGET_STALE_MINUTES))
    val timeText = newest?.withZoneSameInstant(ZoneId.systemDefault())
        ?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "brak danych"

    // Dotknięcie widgetu otwiera aplikację
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Color(0xFF2A0B1C))
            .cornerRadius(16.dp)
            .clickable(actionStartActivity<MainActivity>())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(modifier = GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RoomValue("SALON", data.salon, SALON.color, GlanceModifier.defaultWeight())
                Spacer(modifier = GlanceModifier.width(8.dp))
                RoomValue("DWÓR", data.dwor, DWOR.color, GlanceModifier.defaultWeight())
            }
            Spacer(modifier = GlanceModifier.height(2.dp))
            // Godzina pomiaru i przycisk odświeżenia (pobiera dane od razu)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (stale && newest != null) "$timeText · nieaktualne" else timeText,
                    style = TextStyle(
                        color = ColorProvider(if (stale) Color(0xFFFF8A80) else cream.copy(alpha = 0.7f)),
                        fontSize = 10.sp
                    )
                )
                Spacer(modifier = GlanceModifier.width(8.dp))
                Text(
                    text = "↻",
                    style = TextStyle(color = ColorProvider(Color(0xFFFFC96B)), fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    modifier = GlanceModifier.clickable(actionRunCallback<RefreshWidgetAction>()).padding(horizontal = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun RoomValue(label: String, reading: WidgetReading?, color: Color, modifier: GlanceModifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = TextStyle(color = ColorProvider(Color(0xFFFFC96B)), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        )
        Text(
            text = reading?.let { String.format("%.1f°", it.temperature) } ?: "–",
            style = TextStyle(color = ColorProvider(color), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        )
    }
}

// Równy podział szerokości między kolumny w wierszu
private fun GlanceModifier.defaultWeight(): GlanceModifier = this.then(GlanceModifier.fillMaxWidth())

// Przycisk ↻ na widgecie: pobiera dane od razu
class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        VelvetWidget().update(context, glanceId)
    }
}

class VelvetWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = VelvetWidget()

    override fun onUpdate(context: Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetRefreshWorker.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetRefreshWorker.cancel(context)   // ostatni widget usunięty z ekranu
    }
}

// Odświeżanie widgetu w tle co 15 minut (najczęściej, na ile pozwala Android), tylko z dostępem do sieci
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        VelvetWidget().updateAll(applicationContext)
        return Result.success()
    }

    companion object {
        private const val NAME = "velvet_widget_refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
