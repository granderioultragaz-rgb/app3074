
package com.reef.nutrients

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val DATA_KEY = stringPreferencesKey("entries_json")
val ComponentActivity.dataStore by preferencesDataStore(name = "reef_nutrients")

@Serializable
data class Entry(
    val id: String = UUID.randomUUID().toString(),
    val date: String, // yyyy-MM-dd
    val po4: Double? = null,
    val no3: Double? = null,
    val notes: String = ""
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val act = androidx.compose.ui.platform.LocalContext.current as ComponentActivity
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(listOf<Entry>()) }

    // load
    LaunchedEffect(Unit) {
        val prefs = act.dataStore.data.first()
        val raw = prefs[DATA_KEY] ?: "[]"
        entries = try { Json.decodeFromString(raw) } catch (_: Throwable) { emptyList() }
    }

    fun persist(newList: List<Entry>) {
        val sorted = newList.sortedByDescending { it.date }
        entries = sorted
        scope.launch {
            act.dataStore.edit { prefs ->
                prefs[DATA_KEY] = Json.encodeToString(sorted)
            }
        }
    }

    // --- form state
    var date by remember { mutableStateOf(LocalDate.now().format(DateTimeFormatter.ISO_DATE)) }
    var po4Text by remember { mutableStateOf("") }
    var no3Text by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<String?>(null) }

    fun resetForm() {
        date = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
        po4Text = ""
        no3Text = ""
        notes = ""
        editingId = null
    }

    fun upsert() {
        val po4 = po4Text.replace(',', '.').toDoubleOrNull()
        val no3 = no3Text.replace(',', '.').toDoubleOrNull()
        if (po4 == null && no3 == null) return
        val item = Entry(
            id = editingId ?: UUID.randomUUID().toString(),
            date = date,
            po4 = po4,
            no3 = no3,
            notes = notes.trim()
        )
        val list = entries.toMutableList()
        val idx = list.indexOfFirst { it.id == item.id }
        if (idx >= 0) list[idx] = item else list.add(item)
        persist(list)
        resetForm()
    }

    fun onEdit(it: Entry) {
        editingId = it.id
        date = it.date
        po4Text = it.po4?.toString() ?: ""
        no3Text = it.no3?.toString() ?: ""
        notes = it.notes
    }

    fun onDelete(id: String) {
        persist(entries.filterNot { it.id == id })
        if (editingId == id) resetForm()
    }

    val latest = entries.firstOrNull()
    val previous = entries.drop(1).firstOrNull()

    val deltaPo4 = latest?.po4?.let { l -> previous?.po4?.let { l - it } }
    val deltaNo3 = latest?.no3?.let { l -> previous?.no3?.let { l - it } }

    // ratio NO3:PO4
    val currentRatio = latest?.let { if (it.no3 != null && it.po4 != null && it.po4 != 0.0) it.no3 / it.po4 else null }
    val ratios = entries.mapNotNull { e ->
        if (e.no3 != null && e.po4 != null && e.po4 != 0.0) e.no3 / e.po4 else null
    }
    val avgRatio = if (ratios.isNotEmpty()) ratios.average() else null

    // chart-ready sequences (ascending by date)
    val asc = entries.sortedBy { it.date }
    val po4Series = asc.mapIndexedNotNull { idx, e -> e.po4?.let { idx.toFloat() to it.toFloat() } }
    val no3Series = asc.mapIndexedNotNull { idx, e -> e.no3?.let { idx.toFloat() to it.toFloat() } }
    val labels = asc.map { it.date }

    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Reef Nutrients") }) }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {

                // Summary
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SummaryCard(
                        "Última leitura",
                        """
                        Data: ${latest?.date ?: "—"}
                        PO4: ${latest?.po4 ?: "—"} ppm
                        NO3: ${latest?.no3 ?: "—"} ppm
                        """.trimIndent(),
                        Modifier.weight(1f)
                    )
                    SummaryCard(
                        "Δ vs. anterior",
                        """
                        Δ PO4: ${deltaPo4?.let { String.format("%.3f", it) } ?: "—"} ppm
                        Δ NO3: ${deltaNo3?.let { String.format("%.1f", it) } ?: "—"} ppm
                        """.trimIndent(),
                        Modifier.weight(1f)
                    )
                    SummaryCard(
                        "Relação NO3:PO4",
                        """
                        Atual: ${currentRatio?.let { String.format("%.1f", it) } ?: "—"} : 1
                        Média: ${avgRatio?.let { String.format("%.1f", it) } ?: "—"} : 1
                        """.trimIndent(),
                        Modifier.weight(1f)
                    )
                }

                // Charts
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChartCard(
                        title = "Fosfato (ppm)",
                        series = po4Series,
                        labels = labels,
                        yLabel = "ppm",
                        targetMin = 0.02f,
                        targetMax = 0.08f,
                        modifier = Modifier.weight(1f)
                    )
                    ChartCard(
                        title = "Nitrato (ppm)",
                        series = no3Series,
                        labels = labels,
                        yLabel = "ppm",
                        targetMin = 2f,
                        targetMax = 15f,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Form
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (editingId == null) "Adicionar leitura" else "Editar leitura", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = date,
                            onValueChange = { date = it },
                            label = { Text("Data (yyyy-MM-dd)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = po4Text,
                            onValueChange = { po4Text = it },
                            label = { Text("Fosfato (ppm)") },
                            singleLine = true,
                            keyboardOptions = androidx.compose.ui.text.input.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = no3Text,
                            onValueChange = { no3Text = it },
                            label = { Text("Nitrato (ppm)") },
                            singleLine = true,
                            keyboardOptions = androidx.compose.ui.text.input.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = notes,
                            onValueChange = { notes = it },
                            label = { Text("Observações") },
                            singleLine = false,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { upsert() }) { Text(if (editingId == null) "Adicionar" else "Salvar") }
                            if (editingId != null) {
                                OutlinedButton(onClick = { resetForm() }) { Text("Cancelar") }
                            }
                        }
                    }
                }

                // List with ratio column
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("Histórico", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        if (entries.isEmpty()) {
                            Text("Sem leituras ainda.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Data", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text("PO4 (ppm)", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text("NO3 (ppm)", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text("NO3:PO4", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(80.dp)) // actions
                            }
                            Spacer(Modifier.height(6.dp))
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(entries) { e ->
                                    val ratio = if (e.no3 != null && e.po4 != null && e.po4 != 0.0) e.no3 / e.po4 else null
                                    ElevatedCard {
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                Text(e.date, modifier = Modifier.weight(1f))
                                                Text(e.po4?.toString() ?: "—", modifier = Modifier.weight(1f))
                                                Text(e.no3?.toString() ?: "—", modifier = Modifier.weight(1f))
                                                Text(ratio?.let { String.format("%.1f : 1", it) } ?: "—", modifier = Modifier.weight(1f))
                                                Row {
                                                    OutlinedButton(onClick = { onEdit(e) }) { Text("Editar") }
                                                    Spacer(Modifier.width(6.dp))
                                                    OutlinedButton(
                                                        onClick = { onDelete(e.id) },
                                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                                    ) { Text("Excluir") }
                                                }
                                            }
                                            if (e.notes.isNotBlank()) Text("Obs: ${e.notes}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Text(
                    "Dica: padronize seus testes para leituras consistentes (mesma marca/hora/ambiente).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun SummaryCard(title: String, body: String, modifier: Modifier = Modifier) {
    ElevatedCard(modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(body)
        }
    }
}

/** Simple offline line chart (no libs). */
@Composable
fun ChartCard(
    title: String,
    series: List<Pair<Float, Float>>,
    labels: List<String>,
    yLabel: String,
    targetMin: Float? = null,
    targetMax: Float? = null,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (series.isEmpty()) {
                Text("Sem dados para o gráfico.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LineChart(
                    data = series,
                    labels = labels,
                    height = 220.dp,
                    targetMin = targetMin,
                    targetMax = targetMax
                )
                Spacer(Modifier.height(4.dp))
                Text("Unidade: $yLabel", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun LineChart(
    data: List<Pair<Float, Float>>,
    labels: List<String>,
    height: Dp,
    targetMin: Float? = null,
    targetMax: Float? = null
) {
    val padding = 32.dp
    val labelEvery = max(1, (labels.size / 6)) // ~6 labels
    val values = data.map { it.second }
    val minY = min(values.minOrNull() ?: 0f, targetMin ?: Float.MAX_VALUE)
    val maxY = max(values.maxOrNull() ?: 0f, targetMax ?: Float.MIN_VALUE)
    val yMin = if (minY.isFinite()) (minY * 0.9f) else 0f
    val yMax = if (maxY.isFinite()) (maxY * 1.1f) else 1f
    val yRange = (yMax - yMin).let { if (it == 0f) 1f else it }

    Box(Modifier.fillMaxWidth().height(height)) {
        Canvas(modifier = Modifier.fillMaxSize().padding(start = padding, end = 12.dp, top = 8.dp, bottom = padding/2)) {
            val w = size.width
            val h = size.height

            // axes
            val leftX = 0f
            val bottomY = h
            drawLine(Color(0xFFCBD5E1), Offset(leftX, 0f), Offset(leftX, bottomY), strokeWidth = 2f)
            drawLine(Color(0xFFCBD5E1), Offset(leftX, bottomY), Offset(w, bottomY), strokeWidth = 2f)

            // target band
            if (targetMin != null && targetMax != null) {
                val y1 = bottomY - ((targetMin - yMin) / yRange) * h
                val y2 = bottomY - ((targetMax - yMin) / yRange) * h
                val top = min(y1, y2)
                val bottom = max(y1, y2)
                drawRect(
                    color = Color(0xFF22C55E).copy(alpha = 0.15f),
                    topLeft = Offset(leftX, top),
                    size = androidx.compose.ui.geometry.Size(w, bottom - top)
                )
            }

            // path
            val path = Path()
            data.forEachIndexed { i, (xIdx, yVal) ->
                val x = leftX + (xIdx / max(1f, (data.last().first))) * (w - 4f)
                val y = bottomY - ((yVal - yMin) / yRange) * h
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = Color(0xFF0284C7),
                style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // dots
            data.forEach { (xIdx, yVal) ->
                val x = leftX + (xIdx / max(1f, (data.last().first))) * (w - 4f)
                val y = bottomY - ((yVal - yMin) / yRange) * h
                drawCircle(color = Color(0xFF0284C7), radius = 5f, center = Offset(x, y))
            }

            // y ticks (4)
            for (i in 0..4) {
                val y = bottomY - (i/4f) * h
                drawLine(Color(0xFFE2E8F0), Offset(leftX, y), Offset(w, y), strokeWidth = 1f)
            }
        }

        // x labels
        Row(
            Modifier.fillMaxWidth().align(Alignment.BottomStart).padding(start = padding, end = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            labels.forEachIndexed { idx, l ->
                if (idx % labelEvery == 0) {
                    Text(l.substring(5), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
