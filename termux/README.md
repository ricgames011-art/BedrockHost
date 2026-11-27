# Motor y puente Termux

La app Android es el panel de control. El proceso de servidor se ejecuta en Termux para usar PHP/ARM64 del dispositivo; no se intenta ejecutar el binario Linux x86_64 oficial de Bedrock Dedicated Server en Android.

1. Instala Termux desde una fuente mantenida y abre la app.
2. Copia esta carpeta `termux` al almacenamiento accesible o clona el repositorio.
3. En Termux ejecuta `bash termux/install.sh`.
4. En una sesión de Termux ejecuta `python ~/bedrock-server/bridge.py`.
5. Abre Bedrock Host y usa Iniciar.

El puente escucha solo en `127.0.0.1`, no en la red Wi-Fi. El puerto del juego es UDP 19132 por defecto. Para que otros jugadores entren desde Internet puede ser necesario configurar el router/NAT; el operador móvil puede bloquear conexiones entrantes.

El motor seleccionado es el fork axolotl-pm de PocketMine-MP porque el repositorio original fue archivado. El instalador consulta su última release de GitHub al ejecutarse. Comprueba siempre en la release qué versión de Bedrock soporta. PocketMine no es el servidor oficial BDS y puede tener diferencias de gameplay/API y compatibilidad con plugins.

**Importante:** PocketMine requiere El instalador descarga un runtime PHP ARM64 con extensiones para PocketMine. La compatibilidad de ese runtime con cada release futura debe comprobarse; el instalador valida que PHP se ejecute, pero no puede garantizar que todas las extensiones requeridas estén presentes. Los mundos no se borran al actualizar; aun así, crea una copia antes de cada actualización.
