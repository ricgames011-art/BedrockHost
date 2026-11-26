from pathlib import Path

p = Path("app/src/main/java/com/ric/bedrockhost/Main.kt")
s = p.read_text()

start = s.find("@Composable fun App()")
end = s.find("@Composable fun Detail(", start)

if start == -1 or end == -1:
    raise SystemExit("No se encontraron los bloques App() y Detail(). No se modificó Main.kt.")

new = r'''
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
'''

s = s[:start] + new + "\n" + s[end:]
p.write_text(s)
print("Nueva navegación y cinco secciones añadidas correctamente.")
