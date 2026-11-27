package com.ric.bedrockhost

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private object Api {
    private const val BASE = "http://127.0.0.1:8765"
    suspend fun call(path: String, method: String = "GET", body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val c = (URL(BASE + path).openConnection() as HttpURLConnection)
            c.requestMethod = method
            c.connectTimeout = 2500; c.readTimeout = 5000
            c.setRequestProperty("Content-Type", "application/json")
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) } }
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            val result = stream.bufferedReader().use { it.readText() }
            c.disconnect()
            JSONObject(result)
        }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { HostApp() } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostApp() {
    var tab by remember { mutableStateOf("Inicio") }
    var status by remember { mutableStateOf("Sin conexión con el servicio Termux") }
    var server by remember { mutableStateOf("Desconocido") }
    var players by remember { mutableStateOf("—") }
    var memory by remember { mutableStateOf("—") }
    var cpu by remember { mutableStateOf("—") }
    var log by remember { mutableStateOf(listOf("Inicia el puente con termux/start-api.sh")) }
    var command by remember { mutableStateOf("") }
    var whitelist by remember { mutableStateOf("") }
    var playerName by remember { mutableStateOf("") }
    var backupList by remember { mutableStateOf(listOf<String>()) }
    var motd by remember { mutableStateOf("Bedrock Server") }
    var port by remember { mutableStateOf("19132") }
    var maxPlayers by remember { mutableStateOf("10") }
    var levelName by remember { mutableStateOf("world") }
    var busy by remember { mutableStateOf(false) }\n    var autoUpdate by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun run(block: suspend () -> JSONObject) {
        scope.launch {
            busy = true
            try {
                val r = block()
                status = r.optString("message", "Conectado")
                if (r.has("auto_update")) autoUpdate = r.optBoolean("auto_update")\n                if (r.has("running")) server = if (r.optBoolean("running")) "En ejecución" else "Detenido"
                if (r.has("players")) players = r.optString("players")
                if (r.has("memory_mb")) memory = "${r.optString("memory_mb")} MB"
                if (r.has("cpu_seconds")) cpu = "${r.optString("cpu_seconds")} s"
                if (r.has("lines")) {
                    val a = r.optJSONArray("lines") ?: JSONArray()
                    log = (0 until a.length()).map { a.optString(it) }
                }
                if (r.has("items")) {
                    val a = r.optJSONArray("items") ?: JSONArray()
                    backupList = (0 until a.length()).map { a.optString(it) }
                }
                if (r.has("whitelist")) whitelist = r.optJSONArray("whitelist")?.let { a -> (0 until a.length()).joinToString("\n") { a.optString(it) } } ?: ""
            } catch (e: Exception) { status = "Error: ${e.message ?: "no se puede conectar"}" }
            busy = false
        }
    }

    LaunchedEffect(Unit) { run { Api.call("/status") } }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(topBar = { TopAppBar(title = { Text("Bedrock Host") }, actions = { Text("v2.0", modifier = Modifier.padding(end = 16.dp)) }) },
            bottomBar = {
                NavigationBar {
                    listOf("Inicio", "Consola", "Jugadores", "Copias", "Ajustes").forEach { item ->
                        NavigationBarItem(selected = tab == item, onClick = { tab = item },
                            icon = { Text(when(item) { "Inicio" -> "⌂"; "Consola" -> ">_"; "Jugadores" -> "♙"; "Copias" -> "▣"; else -> "⚙" }) },
                            label = { Text(item) })
                    }
                }
            }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).padding(14.dp)) {
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(10.dp))
                when (tab) {
                    "Inicio" -> {
                        Text("Panel del servidor", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(12.dp))
                        Card { Column(Modifier.padding(16.dp)) {
                            Text("Estado: $server"); Text("Jugadores: $players")
                            Text("Memoria del proceso: $memory"); Text("Tiempo de CPU acumulado: $cpu")
                        } }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !busy, onClick = { run { Api.call("/start", "POST", JSONObject()) } }) { Text("Iniciar") }
                            OutlinedButton(enabled = !busy, onClick = { run { Api.call("/stop", "POST", JSONObject()) } }) { Text("Detener") }
                            OutlinedButton(enabled = !busy, onClick = { run { Api.call("/status") } }) { Text("Actualizar") }
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(enabled = !busy, onClick = { run { Api.call("/update", "POST", JSONObject()) } }) { Text("Actualizar motor ahora") }\n                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Actualización automática cuando el servidor esté detenido"); Switch(checked = autoUpdate, onCheckedChange = { v -> autoUpdate = v; run { Api.call("/auto-update", "POST", JSONObject().put("enabled", v)) } }) }
                        Text("El servidor usa el motor PocketMine-MP compatible con Bedrock; no es el software oficial BDS.", modifier = Modifier.padding(top=14.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    "Consola" -> {
                        Text("Consola", style = MaterialTheme.typography.headlineSmall)
                        Row {
                            OutlinedTextField(command, { command = it }, Modifier.weight(1f), label = { Text("Comando") }, singleLine = true)
                            Button(onClick = { val c = command; command = ""; run { Api.call("/command", "POST", JSONObject().put("command", c)) } }) { Text("Enviar") }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { run { Api.call("/logs") } }) { Text("Actualizar registros") }
                        LazyColumn { items(log) { Text(it, style = MaterialTheme.typography.bodySmall) } }
                    }
                    "Jugadores" -> {
                        Text("Lista blanca", style = MaterialTheme.typography.headlineSmall)
                        OutlinedTextField(playerName, { playerName = it }, Modifier.fillMaxWidth(), label = { Text("Nombre exacto del jugador") })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { val n = playerName.trim(); run { Api.call("/whitelist/add", "POST", JSONObject().put("name", n)) } }) { Text("Añadir") }
                            OutlinedButton(onClick = { val n = playerName.trim(); run { Api.call("/whitelist/remove", "POST", JSONObject().put("name", n)) } }) { Text("Quitar") }
                        }
                        OutlinedButton(onClick = { run { Api.call("/whitelist") } }) { Text("Ver lista blanca") }
                        Text(whitelist)
                    }
                    "Copias" -> {
                        Text("Copias de seguridad", style = MaterialTheme.typography.headlineSmall)
                        Button(onClick = { run { Api.call("/backup", "POST", JSONObject()) } }) { Text("Crear copia ahora") }
                        OutlinedButton(onClick = { run { Api.call("/backups") } }) { Text("Actualizar lista") }
                        LazyColumn { items(backupList) { Text("• $it", modifier = Modifier.padding(6.dp)) } }
                        Text("Las copias se guardan en ~/bedrock-server/backups. La restauración se hace desde Termux tras detener el servidor.", style = MaterialTheme.typography.bodySmall)
                    }
                    else -> {
                        Text("Ajustes del servidor", style = MaterialTheme.typography.headlineSmall)
                        OutlinedTextField(motd, { motd = it }, Modifier.fillMaxWidth(), label = { Text("Nombre / MOTD") })
                        OutlinedTextField(port, { port = it }, Modifier.fillMaxWidth(), label = { Text("Puerto UDP") })
                        OutlinedTextField(maxPlayers, { maxPlayers = it }, Modifier.fillMaxWidth(), label = { Text("Máximo de jugadores") })
                        OutlinedTextField(levelName, { levelName = it }, Modifier.fillMaxWidth(), label = { Text("Nombre del mundo") })
                        Button(onClick = { run { Api.call("/settings", "POST", JSONObject().put("motd", motd).put("port", port).put("max_players", maxPlayers).put("level_name", levelName)) } }) { Text("Guardar ajustes") }
                        OutlinedButton(onClick = { run { Api.call("/settings") } }) { Text("Cargar ajustes") }
                        Text("Los cambios de puerto requieren reiniciar el servidor. Mantén Termux activo y exclúyelo del ahorro de batería.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
