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
    if (alive) {
        log.add("El servidor ya está iniciado.")
        return
    }

    thread {
        try {
            val d = Host.dir(name)
            File(d, "plugins").mkdirs()

            val php = Host.php
            val phar = Host.phar

            if (php == null || !php.exists()) {
                log.add("ERROR: No se encuentra el ejecutable PHP.")
                return@thread
            }

            if (!phar.exists()) {
                log.add("ERROR: No se encuentra PocketMine-MP.phar.")
                return@thread
            }

            if (!php.canExecute()) {
                log.add("ERROR: PHP no tiene permisos de ejecución.")
                return@thread
            }

            log.add("Iniciando servidor: $name")
            log.add("PHP: ${php.absolutePath}")
            log.add("PocketMine: ${phar.absolutePath}")
            log.add("Directorio: ${d.absolutePath}")

            val p = ProcessBuilder(
                php.absolutePath,
                phar.absolutePath,
                "--no-wizard",
                "--disable-ansi",
                "--data=${d.absolutePath}",
                "--plugins=${File(d, "plugins").absolutePath}"
            )
                .directory(d)
                .redirectErrorStream(true)
                .start()

            proc = p
            alive = true

            log.add("Proceso iniciado. Esperando salida de consola...")

            p.inputStream.bufferedReader().use { reader ->
                var line: String?

                while (reader.readLine().also { line = it } != null) {
                    log.add(line ?: "")
                    if (log.size > 500) {
                        log.removeAt(0)
                    }
                }
            }

            val exitCode = p.waitFor()

            log.add("El proceso terminó. Código de salida: $exitCode")

        } catch (e: Exception) {
            log.add("ERROR AL INICIAR: ${e.javaClass.simpleName}")
            log.add(e.message ?: "Error sin descripción")
        } finally {
            alive = false
            proc = null
        }
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
    fun deviceStats(): List<String> {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        val max = runtime.maxMemory()

        val activityManager = root.let {
            android.os.Debug.getNativeHeapAllocatedSize()
        }

        val memoryInfo = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(memoryInfo)

        return listOf(
            "Memoria de la app: ${used / 1048576} MB",
            "Límite de memoria de la app: ${max / 1048576} MB",
            "Memoria nativa: ${activityManager / 1048576} MB",
            "Memoria PSS: ${memoryInfo.totalPss / 1024} MB"
        )
    }


    lateinit var root: File
lateinit var ext: File
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
        val stage = File(root, "engine-staging")
        try {
            if (running.values.any { it.alive }) {
                throw IllegalStateException("Detén todos los servidores antes de instalar o actualizar el motor")
            }

            status = "Preparando instalación segura…"
            stage.deleteRecursively()
            stage.mkdirs()

            val archive = File(stage, "php.tgz")
            status = "Descargando PHP…"
            dl(phpUrl, archive)

            status = "Extrayendo PHP…"
            val tar = ProcessBuilder(
                "tar", "-xzf", archive.absolutePath, "-C", stage.absolutePath
            ).redirectErrorStream(true).start()
            val tarOutput = tar.inputStream.bufferedReader().readText()
            val tarCode = tar.waitFor()
            if (tarCode != 0) {
                throw IllegalStateException("Falló la extracción PHP: $tarOutput")
            }

            val phpFile = stage.walkTopDown()
                .firstOrNull { it.isFile && it.name == "php" }
                ?: throw IllegalStateException("No se encontró el binario PHP")

            phpFile.setExecutable(true, false)

            status = "Comprobando PHP…"
            val phpTest = ProcessBuilder(phpFile.absolutePath, "-v")
                .redirectErrorStream(true).start()
            val phpOutput = phpTest.inputStream.bufferedReader().readText()
            if (phpTest.waitFor() != 0) {
                throw IllegalStateException("PHP no puede ejecutarse: $phpOutput")
            }

            status = "Descargando PocketMine…"
            dl(pharUrl, File(stage, "PocketMine-MP.phar"))

            val backup = File(root, "engine-backup-${System.currentTimeMillis()}")
            if (engine.exists() && !engine.renameTo(backup)) {
                throw IllegalStateException("No se pudo proteger el motor anterior")
            }

            if (!stage.renameTo(engine)) {
                if (backup.exists()) backup.renameTo(engine)
                throw IllegalStateException("No se pudo activar el motor nuevo")
            }

            status = "Instalación correcta. Motor anterior conservado en ${backup.name}"
        } catch (e: Exception) {
            stage.deleteRecursively()
            status = "Error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun diagnostics(): List<String> {
        val result = mutableListOf<String>()
        result.add("Directorio de datos: ${root.absolutePath}")
        result.add("Directorio escribible: ${root.canWrite()}")
        result.add("PHP encontrado: ${php != null}")
        result.add("PHP ejecutable: ${php?.canExecute() == true}")
        result.add("PocketMine PHAR encontrado: ${phar.exists()}")
        result.add("Motor PocketMine preparado: $ready")
        result.add("Direcciones IPv4: ${ips().joinToString().ifBlank { "No detectadas" }}")

        names().forEach { n ->
            val port = readProps(n)["server-port"] ?: "19132"
            val runningNow = running[n]?.alive == true
            result.add("Servidor $n: ${if (runningNow) "encendido" else "apagado"} · puerto $port")
        }

        result.add("VanillaMine: pendiente de compilación y verificación ARM64")
        return result
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
    var section by remember { mutableStateOf(0) }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            if (sel != null) {
                Detail(sel!!) { sel = null }
            } else {
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            val labels = listOf(
                                "Inicio",
                                "Motores",
                                "Actualizaciones",
                                "Comprobaciones",
                                "Copias"
                            )
                            labels.forEachIndexed { index, label ->
                                NavigationBarItem(
                                    selected = section == index,
                                    onClick = { section = index },
                                    icon = {},
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                ) { padding ->
                    Box(Modifier.padding(padding).fillMaxSize()) {
                        when (section) {
                            0 -> Home { sel = it }
                            1 -> EnginesScreen()
                            2 -> UpdatesScreen()
                            3 -> ChecksScreen()
                            4 -> BackupsScreen()
                        }
                    }
                }
            }
        }
    }
}

@Composable fun EnginesScreen() {
    val ready = Host.ready

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Motores", fontSize = 26.sp)
        Text("Administra los motores de BedrockHost.")

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("PocketMine-MP", fontSize = 20.sp)
                Text(if (ready) "Estado: instalado y preparado" else "Estado: no instalado")
                Text("Motor PHP para servidores Minecraft Bedrock.")
                Text("PHAR: ${Host.phar.name}")
                Button(
                    onClick = { Host.install() },
                    enabled = Host.running.values.none { it.alive }
                ) {
                    Text(if (ready) "Reinstalar / actualizar motor" else "Instalar PocketMine")
                }
                Text(Host.status, fontSize = 12.sp)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("VanillaMine", fontSize = 20.sp)
                Text("Estado: pendiente de compilación y pruebas ARM64.")
                Text("Todavía no está disponible para instalación desde esta aplicación.")
            }
        }

        Text("Los servidores deben estar detenidos antes de instalar o actualizar el motor.")
    }
}

@Composable fun UpdatesScreen() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Actualizaciones", fontSize = 26.sp)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("PocketMine-MP", fontSize = 20.sp)
                Text("La instalación utiliza la URL configurada en la pantalla de Inicio.")
                Text("La comprobación automática de versiones y el registro de cambios todavía están pendientes.")
                Button(
                    onClick = { Host.install() },
                    enabled = Host.running.values.none { it.alive }
                ) {
                    Text("Descargar e instalar motor")
                }
                Text(Host.status, fontSize = 12.sp)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("VanillaMine", fontSize = 20.sp)
                Text("Las actualizaciones estarán disponibles cuando exista una compilación ARM64 verificada.")
            }
        }
    }
}

@Composable fun ChecksScreen() {
    var refresh by remember { mutableIntStateOf(0) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Comprobaciones", fontSize = 26.sp)
        Text("Diagnóstico del dispositivo y los motores.")

        Button(onClick = { refresh++ }) {
            Text("Volver a comprobar")
        }

        key(refresh) {
            Host.diagnostics().forEach { item ->
                Card(Modifier.fillMaxWidth()) {
                    Text(item, Modifier.padding(14.dp), fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable fun BackupsScreen() {
    var message by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }

    val backupDir = File(Host.ext, "backups")
    val files = backupDir.listFiles()
        ?.filter { it.isFile && it.extension.equals("zip", true) }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Copias de seguridad", fontSize = 26.sp)
        Text("Crea y consulta copias ZIP de tus servidores.")

        Host.names().forEach { name ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(name, fontSize = 18.sp)
                    Button(onClick = {
                        message = "Creando copia de $name..."
                        thread {
                            try {
                                val result = Host.backup(name)
                                message = "Copia creada: ${result.name}"
                                refresh++
                            } catch (e: Exception) {
                                message = "Error: ${e.message}"
                            }
                        }
                    }) {
                        Text("Crear copia")
                    }
                }
            }
        }

        Text(message, fontSize = 13.sp)
        HorizontalDivider()
        Text("Copias existentes", fontSize = 20.sp)

        key(refresh) {
            if (files.isEmpty()) {
                Text("Todavía no hay copias ZIP.")
            } else {
                files.forEach { file ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(file.name)
                            Text("${file.length() / 1024} KB", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Text(
            "La restauración de copias todavía no está implementada. No se modificará ningún mundo desde esta pantalla.",
            fontSize = 12.sp
        )
    }
}


@Composable fun Home(open: (String) -> Unit) {
    val st = Host.status
    val ready = Host.ready

    var names by remember { mutableStateOf(Host.names()) }
    var nn by remember { mutableStateOf("") }
    var np by remember { mutableStateOf("19132") }
    var showChecks by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            refresh++
            names = Host.names()
        }
    }

    val stats = Host.deviceStats()
    val runningCount = Host.running.values.count { it.alive }
    val ips = Host.ips().joinToString().ifBlank { "No disponible" }

    Column(
        Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("BedrockHost", fontSize = 26.sp)
        Text(
            if (ready) "Motor PocketMine-MP preparado"
            else "Motor no instalado"
        )

        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Estado del dispositivo", fontSize = 18.sp)
                stats.forEach {
                    Text(it, fontSize = 14.sp)
                }
                Text("Actualización automática cada 2 segundos", fontSize = 11.sp)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Servidores", fontSize = 18.sp)
                Text("Total: ${names.size}")
                Text("En ejecución: $runningCount")
                Text("IP local: $ips")
            }
        }

        Text(
            "Motor: " + (if (ready) "listo (PocketMine-MP)" else "no instalado") +
                if (st.isNotEmpty()) " · $st" else ""
        )

        OutlinedTextField(
            Host.phpUrl,
            { Host.phpUrl = it },
            label = { Text("URL PHP Android arm64 (.tar.gz)") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            Host.pharUrl,
            { Host.pharUrl = it },
            label = { Text("URL PocketMine-MP.phar") },
            modifier = Modifier.fillMaxWidth()
        )

        Button({ Host.install() }) {
            Text(if (ready) "Reinstalar motor" else "Instalar motor")
        }

        OutlinedButton({ showChecks = true }) {
            Text("Comprobaciones y diagnóstico")
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("VanillaMine", fontSize = 18.sp)
                Text("Instalación ARM64 pendiente de compilación y verificación.")
                Text("No se sustituirá PocketMine automáticamente.")
            }
        }

        if (showChecks) {
            AlertDialog(
                onDismissRequest = { showChecks = false },
                title = { Text("Diagnóstico de BedrockHost") },
                text = {
                    Column(
                        Modifier.heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Host.diagnostics().forEach {
                            Text(
                                it,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 3.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton({ showChecks = false }) {
                        Text("Cerrar")
                    }
                }
            )
        }

        HorizontalDivider()
        Text("Tus servidores", fontSize = 18.sp)

        names.forEach { n ->
            val running = Host.running[n]?.alive == true
            Card(
                Modifier.fillMaxWidth().clickable { open(n) }
            ) {
                Text(
                    "$n · puerto ${Host.readProps(n)["server-port"] ?: "?"} · " +
                        if (running) "🟢 encendido" else "⚪ apagado",
                    Modifier.padding(16.dp)
                )
            }
        }

        HorizontalDivider()
        Text("Crear servidor", fontSize = 18.sp)

        OutlinedTextField(
            nn,
            { nn = it.filter { c -> c.isLetterOrDigit() || c == '-' || c == '_' } },
            label = { Text("Nombre del servidor nuevo") },
            singleLine = true
        )

        OutlinedTextField(
            np,
            { np = it.filter { c -> c.isDigit() } },
            label = { Text("Puerto UDP") },
            singleLine = true
        )

        Button({
            if (nn.isNotBlank()) {
                Host.create(nn, np.ifBlank { "19132" })
                names = Host.names()
                nn = ""
            }
        }) {
            Text("Crear servidor")
        }
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
