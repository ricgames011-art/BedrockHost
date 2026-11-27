# Bedrock Host Android — v2

Aplicación Android (Kotlin + Jetpack Compose) para administrar un servidor Bedrock alojado en el mismo teléfono mediante Termux.

## Funciones incluidas
- Panel: estado del proceso, memoria aproximada y control de inicio/parada.
- Consola: envío de comandos y lectura de las últimas 250 líneas.
- Lista blanca: comandos add/remove y consulta del archivo.
- Copias ZIP: copia completa del directorio del motor con el servidor detenido.
- Ajustes: MOTD, puerto UDP, máximo de jugadores y nombre del mundo.
- Actualización manual y automática opcional: consulta la última release de axolotl-pm y reemplaza solo el PHAR, conservando mundos, plugins y `server.properties`. La automática comprueba periódicamente y aplica el cambio solo con el servidor detenido.\n- Runtime PHP ARM64 específico para PocketMine, descargado en Termux; el PHP genérico de Termux no basta.

## Arquitectura real
Android no ejecuta el servidor Bedrock oficial: el binario oficial está dirigido a Linux x86_64 y no es una aplicación Android ARM64. Este proyecto usa Termux + PHP para ejecutar PocketMine-MP (fork axolotl-pm), y la app se comunica con un puente HTTP local en `127.0.0.1:8765`.

No es un clon de BDS. PocketMine tiene diferencias de comportamiento, API y compatibilidad de plugins. La compatibilidad con la versión más nueva de Minecraft depende de la última release disponible del motor.

## Instalación del motor
Desde la raíz del proyecto en Termux:
```bash
bash termux/install.sh
python ~/bedrock-server/bridge.py
```
Mantén la sesión de Termux activa. Después abre la app y pulsa Iniciar. Para detener el servidor, usa Detener desde la app.

## Compilar en Termux
Se necesita JDK 17, Gradle 8.9+ y Android SDK 35 configurado (`ANDROID_HOME`). Si tu Termux no tiene Android SDK completo, compila mediante GitHub Actions:
```bash
gradle assembleDebug
```
El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

## Compilar con GitHub
Sube el contenido de este ZIP a tu repositorio `BedrockHost` en la rama `main`. GitHub Actions compila automáticamente y publica el APK como artefacto `BedrockHost-debug`.

## Límites conocidos de esta primera versión
- El panel API debe iniciarse manualmente en Termux; Android no puede mantenerlo vivo si el sistema cierra Termux.
- CPU es una estimación acumulada del proceso, no un porcentaje instantáneo calibrado.
- El recuento de jugadores no está integrado todavía; se consulta con `list` en Consola.
- El puente implementa operaciones básicas sin autenticación, pero solo escucha en loopback.
- La lista blanca depende de los comandos soportados por la versión instalada de PocketMine.
- La copia exige detener el servidor para evitar un ZIP inconsistente.
