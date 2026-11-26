package com.ric.bedrockhost

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

class Srv(val name: String) {
    val log = mutableStateListOf<String>()
    var proc: java.lang.Process? = null
    var alive by mutableStateOf(false)
    fun start() {
        if (alive) return
        val d = Host.dir(name); File(d, "plugins").mkdirs()
        val p = ProcessBuilder(Host.php!!.path, Host.phar.path, "--no-wizard", "--disable-ansi",
            "--data=" + d.path, "--plugins=" + d.path + "/plugins")
            .directory(d).redirectErrorStream(true).start()
        proc = p; alive = true
        thread {
            p.inputStream.bufferedReader().forEachLine { log.add(it); if (log.size > 500) log.removeAt(0) }            
            alive = false
        }
    }

    fun send(c: String) {
        try {
            proc?.getOutputStream()?.apply {
                write((c + "\n").toByteArray())
                flush()
            }
        } catch (_: Exception) {}
    }
}


object Host {
    lateinit var root: File  lateinit var ext: File
    val running = HashMap<String, Srv>()
    fun srv(n: String) = running.getOrPut(n) { Srv(n) }
    var status by mutableStateOf("")
    var phpUrl by mutableStateOf("https://github.com/pmmp/PHP-Binaries/releases/download/pm5-php-8.3-latest/PHP-8.3-Android-arm64-PM5.tar.gz")
    var pharUrl by mutableStateOf("https://github.com/pmmp/PocketMine-MP/releases/latest/download/PocketMine-MP.phar")
    val engine get() = File(root, "engine")
    val php: File? get() = engine.walkTopDown().firstOrNull { it.isFile && it.name == "php" }
    val phar get() = File(engine, "PocketMine-MP.phar")
    val ready get() = php != null && phar.exists()
    fun dir(n: String) = File(root, "servers/$n")
    fun names() = File(root, "servers").apply { mkdirs() }.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
    fun ips() = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
        .filter { !it.isLoopbackAddress && it is Inet4Address }.map { it.hostAddress ?: "" }
    fun dl(u: String, f: File) { URL(u).openStream().use { i -> f.outputStream().use { o -> i.copyTo(o) } } }
    fun install() = thread {
        try {
            engine.deleteRecursively(); engine.mkdirs()
            status = "Descargando PHP…"; dl(phpUrl, File(engine, "php.tgz"))
            status = "Extrayendo…"; ProcessBuilder("tar", "-xzf", "php.tgz").directory(engine).start().waitFor()
            engine.walkTopDown().forEach { it.setExecutable(true, false) }
            status = "Descargando PocketMine…"; dl(pharUrl, phar)
            status = if (ready) "listo" else "No se encontró el binario php"
        } catch (e: Exception) { status = "Error: " + e.message }
    }
    fun readProps(n: String): MutableMap<String, String> {
        val m = linkedMapOf<String, String>()
        File(dir(n), "server.properties").takeIf { it.exists() }?.readLines()?.forEach {
            val i = it.indexOf('='); if (i > 0 && !it.startsWith("#")) m[it.substring(0, i)] = it.substring(i + 1)
        }
        return m
    }
    fun writeProps(n: String, m: Map<String, String>) {
        dir(n).mkdirs()
        File(dir(n), "server.properties").writeText(m.filter { it.value.isNotBlank() }.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }
    fun create(n: String, port: String) = writeProps(n, linkedMapOf("motd" to n, "server-port" to port,
        "gamemode" to "survival", "difficulty" to "normal", "max-players" to "10", "white-list" to "on", "xbox-auth" to "on"))
    fun backup(n: String): File {
        val d = dir(n)
        val o = File(File(ext, "backups").apply { mkdirs() }, "$n-${System.currentTimeMillis() / 1000}.zip")
        ZipOutputStream(o.outputStream()).use { z ->
            d.walkTopDown().filter { it.isFile }.forEach { f ->
                z.putNextEntry(ZipEntry(f.relativeTo(d).path)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
        return o
    }
}

class Svc : Service() {
    private var wl: PowerManager.WakeLock? = null
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("h", "Servidores", NotificationManager.IMPORTANCE_LOW))
        val nb = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "h") else Notification.Builder(this)
        startForeground(1, nb.setContentTitle("BedrockHost").setContentText("Servidor en ejecución")
            .setSmallIcon(android.R.drawable.ic_dialog_info).build())
        if (wl == null) { wl = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bh:srv"); wl?.acquire() }
        return START_STICKY
    }
    override fun onDestroy() { wl?.release(); super.onDestroy() }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b); Host.root = filesDir; Host.ext = getExternalFilesDir(null) ?: filesDir
        setContent { App() }
    }
}

@Composable fun App() {
    var sel by remember { mutableStateOf<String?>(null) }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) { if (sel == null) Home { sel = it } else Detail(sel!!) { sel = null } }
    }
}

@Composable fun Home(open: (String) -> Unit) {
    val st = Host.status; val ready = Host.ready
    var names by remember { mutableStateOf(Host.names()) }
    var nn by remember { mutableStateOf("") }; var np by remember { mutableStateOf("19132") }
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("BedrockHost", fontSize = 24.sp)
        Text("Motor: " + (if (ready) "listo (PocketMine-MP)" else "no instalado") + if (st.isNotEmpty()) " · $st" else "")
        OutlinedTextField(Host.phpUrl, { Host.phpUrl = it }, label = { Text("URL PHP Android arm64 (.tar.gz)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(Host.pharUrl, { Host.pharUrl = it }, label = { Text("URL PocketMine-MP.phar") }, modifier = Modifier.fillMaxWidth())
        Button({ Host.install() }) { Text(if (ready) "Reinstalar motor" else "Instalar motor") }
        Text("IP en tu Wi-Fi: " + Host.ips().joinToString())
        HorizontalDivider()
        names.forEach { n ->
            val r = Host.running[n]?.alive == true
            Card(Modifier.fillMaxWidth().clickable { open(n) }) {
                Text("$n  ·  puerto ${Host.readProps(n)["server-port"] ?: "?"}  ·  " + if (r) "🟢 encendido" else "⚪ apagado", Modifier.padding(16.dp))
            }
        }
        HorizontalDivider()
        OutlinedTextField(nn, { nn = it.filter { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }, label = { Text("Nombre del servidor nuevo") }, singleLine = true)
        OutlinedTextField(np, { np = it.filter { c -> c.isDigit() } }, label = { Text("Puerto UDP") }, singleLine = true)
        Button({ if (nn.isNotBlank()) { Host.create(nn, np.ifBlank { "19132" }); names = Host.names(); nn = "" } }) { Text("Crear servidor") }
    }
}

@Composable fun Detail(n: String, back: () -> Unit) {
    val ctx = LocalContext.current; val s = Host.srv(n); var tab by remember { mutableStateOf(0) }
    Column {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(back) { Text("←") }
            Text(n, fontSize = 20.sp, modifier = Modifier.weight(1f))
            if (s.alive) Button({ s.send("stop") }) { Text("Detener") }
            else Button({
                if (Host.ready) { ContextCompat.startForegroundService(ctx, Intent(ctx, Svc::class.java)); s.start() }
                else s.log.add("Instala el motor primero (pantalla principal).")
            }) { Text("Iniciar") }
        }
        TabRow(tab) { listOf("Consola", "Ajustes", "Jugadores", "Copias").forEachIndexed { i, t -> Tab(tab == i, { tab = i }) { Text(t, Modifier.padding(12.dp)) } } }
        when (tab) { 0 -> Console(s); 1 -> Settings(n); 2 -> Players(s); else -> Backups(n) }
    }
}

@Composable fun Console(s: Srv) {
    var cmd by remember { mutableStateOf("") }; val ls = rememberLazyListState()
    LaunchedEffect(s.log.size) { if (s.log.isNotEmpty()) ls.scrollToItem(s.log.lastIndex) }
    Column(Modifier.padding(8.dp)) {
        LazyColumn(Modifier.weight(1f), state = ls) { items(s.log.toList()) { Text(it, fontSize = 11.sp, lineHeight = 13.sp) } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(cmd, { cmd = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("comando") })
            Button({ s.send(cmd); cmd = "" }) { Text("▶") }
        }
    }
}

@Composable fun Settings(n: String) {
    var m by remember { mutableStateOf(Host.readProps(n).toMap()) }; var saved by remember { mutableStateOf(false) }
    Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
        listOf("motd", "server-port", "gamemode", "difficulty", "max-players", "view-distance", "level-seed").forEach { k ->
            OutlinedTextField(m[k] ?: "", { m = m + (k to it) }, label = { Text(k) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        listOf("white-list", "xbox-auth").forEach { k ->
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(m[k] == "on", { m = m + (k to if (it) "on" else "off") }); Text("  $k") }
        }
        Text("gamemode: survival/creative/adventure/spectator · difficulty: peaceful/easy/normal/hard", fontSize = 11.sp)
        Button({ Host.writeProps(n, m); saved = true }) { Text(if (saved) "Guardado (reinicia el servidor)" else "Guardar") }
    }
}

@Composable fun Players(s: Srv) {
    var p by remember { mutableStateOf("") }
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(p, { p = it }, label = { Text("Jugador") }, singleLine = true)
        listOf("op" to "OP", "deop" to "Quitar OP", "whitelist add" to "Permitir", "whitelist remove" to "Quitar permiso",
            "kick" to "Expulsar", "ban" to "Banear", "pardon" to "Desbanear").chunked(2).forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { r.forEach { (c, t) -> Button({ s.send("$c $p") }) { Text(t) } } }
        }
        Button({ s.send("list") }) { Text("Ver conectados") }
        Text("Las respuestas salen en Consola", fontSize = 11.sp)
    }
}

@Composable fun Backups(n: String) {
    var msg by remember { mutableStateOf("") }
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ msg = "Creando…"; thread { msg = "Guardada: " + Host.backup(n).path } }) { Text("Crear copia (ZIP)") }
        Text(msg)
        Text("Carpeta del servidor (mundos, plugins):\n" + Host.dir(n).path, fontSize = 12.sp)
        Text("Plugins: pon los .phar en la subcarpeta plugins y reinicia.", fontSize = 12.sp)
    }
}
