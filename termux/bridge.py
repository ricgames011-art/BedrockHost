#!/data/data/com.termux/files/usr/bin/python
import json, subprocess, threading, time, zipfile, re
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

ROOT = Path.home() / "bedrock-server"
ENGINE = ROOT / "engine"
LOG = ROOT / "server.log"
BACKUPS = ROOT / "backups"
AUTO_FILE = ROOT / "auto-update.json"
PROC = None
LOG_HANDLE = None
LOCK = threading.RLock()

def auto_enabled():
    try:
        return bool(json.loads(AUTO_FILE.read_text()).get("enabled", False))
    except Exception:
        return False

def read_props():
    p = ENGINE / "server.properties"
    data = {}
    if p.exists():
        for line in p.read_text(errors="ignore").splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.split("=", 1)
                data[k.strip()] = v.strip()
    return data

def write_props(values):
    p = ENGINE / "server.properties"
    old = p.read_text(errors="ignore").splitlines() if p.exists() else []
    done, out = set(), []
    for line in old:
        if "=" in line and not line.lstrip().startswith("#"):
            k = line.split("=", 1)[0].strip()
            if k in values:
                out.append(k + "=" + str(values[k]))
                done.add(k)
                continue
        out.append(line)
    for k, v in values.items():
        if k not in done:
            out.append(k + "=" + str(v))
    p.write_text("\n".join(out) + "\n")

def start():
    global PROC, LOG_HANDLE
    with LOCK:
        if PROC and PROC.poll() is None:
            return False
        php = ROOT / "bin" / "php"
        phar = ENGINE / "PocketMine-MP.phar"
        if not php.exists():
            raise RuntimeError("No está instalado el PHP ARM64 de PocketMine. Ejecuta termux/install.sh")
        if not phar.exists():
            raise RuntimeError("No existe PocketMine-MP.phar. Ejecuta termux/install.sh")
        LOG_HANDLE = open(LOG, "a", buffering=1)
        PROC = subprocess.Popen([str(php), str(phar), "--no-wizard"], cwd=str(ENGINE),
                                stdin=subprocess.PIPE, stdout=LOG_HANDLE,
                                stderr=subprocess.STDOUT, text=True)
        return True

def stop():
    with LOCK:
        if PROC and PROC.poll() is None:
            try:
                PROC.stdin.write("stop\n")
                PROC.stdin.flush()
            except Exception:
                PROC.terminate()
            return True
        return False

def send_command(cmd):
    if not re.fullmatch(r"[A-Za-z0-9_ .:-]{1,160}", cmd):
        raise ValueError("Comando no válido")
    if not PROC or PROC.poll() is not None:
        raise RuntimeError("Servidor detenido")
    PROC.stdin.write(cmd + "\n")
    PROC.stdin.flush()

def status():
    running = bool(PROC and PROC.poll() is None)
    mem = 0
    cpu_seconds = 0
    if running:
        try:
            stat = Path(f"/proc/{PROC.pid}/status").read_text()
            m = re.search(r"VmRSS:\s+(\d+)", stat)
            mem = round(int(m.group(1)) / 1024, 1) if m else 0
            ticks = re.search(r"utime\s+(\d+)\s+stime\s+(\d+)", Path(f"/proc/{PROC.pid}/stat").read_text())
            if ticks:
                cpu_seconds = round((int(ticks.group(1)) + int(ticks.group(2))) / 100, 1)
        except Exception:
            pass
    return {"running": running, "memory_mb": mem, "cpu_seconds": cpu_seconds,
            "auto_update": auto_enabled(), "players": "Consulta la consola: list",
            "message": "Servicio conectado"}

def logs():
    lines = LOG.read_text(errors="replace").splitlines()[-250:] if LOG.exists() else []
    return {"lines": lines, "message": "Registros cargados"}

def backups():
    BACKUPS.mkdir(parents=True, exist_ok=True)
    return {"items": sorted([x.name for x in BACKUPS.glob("*.zip")], reverse=True),
            "message": "Copias listadas"}

def backup():
    if PROC and PROC.poll() is None:
        raise RuntimeError("Detén el servidor antes de crear una copia consistente")
    BACKUPS.mkdir(parents=True, exist_ok=True)
    name = time.strftime("backup-%Y%m%d-%H%M%S.zip")
    dest = BACKUPS / name
    with zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED) as z:
        for f in ENGINE.rglob("*"):
            if f.is_file():
                z.write(f, f.relative_to(ENGINE))
    return {"message": "Copia creada: " + name, "items": [name]}

def whitelist():
    f = ENGINE / "whitelist.json"
    try:
        data = json.loads(f.read_text())
    except Exception:
        data = []
    if isinstance(data, dict):
        names = list(data.keys())
    else:
        names = [x.get("name", "") if isinstance(x, dict) else str(x) for x in data]
    return {"whitelist": names, "message": "Lista blanca cargada"}

def auto_worker():
    while True:
        time.sleep(3600)
        try:
            if not auto_enabled() or (PROC and PROC.poll() is None):
                continue
            import urllib.request
            req = urllib.request.Request(
                "https://api.github.com/repos/axolotl-pm/PocketMine-MP/releases/latest",
                headers={"User-Agent": "BedrockHost"})
            with urllib.request.urlopen(req, timeout=15) as r:
                latest = json.loads(r.read()).get("tag_name", "")
            current = (ENGINE / "ENGINE_VERSION").read_text().strip() if (ENGINE / "ENGINE_VERSION").exists() else ""
            if latest and latest != current:
                subprocess.run([str(ROOT / "update-engine.sh")], check=True)
        except Exception as e:
            with open(ROOT / "auto-update.log", "a") as out:
                out.write(str(e) + "\n")

class Handler(BaseHTTPRequestHandler):
    def send_json(self, obj, code=200):
        b = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        try:
            if self.path == "/status":
                self.send_json(status())
            elif self.path == "/logs":
                self.send_json(logs())
            elif self.path == "/backups":
                self.send_json(backups())
            elif self.path == "/settings":
                self.send_json({"settings": read_props(), "message": "Ajustes cargados"})
            elif self.path == "/whitelist":
                self.send_json(whitelist())
            else:
                self.send_json({"message": "Ruta no encontrada"}, 404)
        except Exception as e:
            self.send_json({"message": str(e)}, 500)

    def do_POST(self):
        try:
            n = int(self.headers.get("Content-Length", "0"))
            d = json.loads(self.rfile.read(n) or b"{}")
            if self.path == "/start":
                start()
                out = {"message": "Solicitud de inicio enviada"}
            elif self.path == "/stop":
                stop()
                out = {"message": "Solicitud de apagado enviada"}
            elif self.path == "/command":
                send_command(d.get("command", ""))
                out = {"message": "Comando enviado"}
            elif self.path == "/backup":
                out = backup()
            elif self.path == "/settings":
                mapping = {"motd": "motd", "port": "server-port",
                           "max_players": "max-players", "level_name": "level-name"}
                vals = {mapping[k]: str(v) for k, v in d.items() if k in mapping}
                if "server-port" in vals and not vals["server-port"].isdigit():
                    raise ValueError("Puerto inválido")
                write_props(vals)
                out = {"message": "Ajustes guardados. Reinicia para aplicar cambios"}
            elif self.path == "/auto-update":
                AUTO_FILE.write_text(json.dumps({"enabled": bool(d.get("enabled", False))}))
                out = {"message": "Actualización automática " + ("activada" if auto_enabled() else "desactivada")}
            elif self.path == "/whitelist/add":
                name = str(d.get("name", "")).strip()
                if not name or len(name) > 32:
                    raise ValueError("Nombre no válido")
                send_command("whitelist on")
                send_command("whitelist add " + name)
                out = {"message": "Jugador añadido (comprueba la consola)"}
            elif self.path == "/whitelist/remove":
                name = str(d.get("name", "")).strip()
                if not name or len(name) > 32:
                    raise ValueError("Nombre no válido")
                send_command("whitelist remove " + name)
                out = {"message": "Solicitud enviada"}
            elif self.path == "/update":
                if PROC and PROC.poll() is None:
                    raise RuntimeError("Detén el servidor antes de actualizar")
                import subprocess
                subprocess.run([str(ROOT / "update-engine.sh")], check=True)
                out = {"message": "Motor actualizado. Los mundos y plugins se conservaron"}
            else:
                self.send_json({"message": "Ruta no encontrada"}, 404)
                return
            self.send_json(out)
        except Exception as e:
            self.send_json({"message": str(e)}, 400)

    def log_message(self, *args):
        pass

if __name__ == "__main__":
    ROOT.mkdir(parents=True, exist_ok=True)
    BACKUPS.mkdir(parents=True, exist_ok=True)
    threading.Thread(target=auto_worker, daemon=True).start()
    print("BedrockHost API escuchando en 127.0.0.1:8765")
    HTTPServer(("127.0.0.1", 8765), Handler).serve_forever()
