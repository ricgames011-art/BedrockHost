#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
pkg update -y
pkg install -y python curl wget unzip tar
ROOT="$HOME/bedrock-server"
mkdir -p "$ROOT/engine" "$ROOT/backups" "$ROOT/bin"
# PMMP needs its custom PHP extensions; generic Termux PHP is not sufficient.
curl -fL https://github.com/ItzxDwi/AndroidPHP/releases/latest/download/php -o "$ROOT/bin/php"
chmod 700 "$ROOT/bin/php"
"$ROOT/bin/php" -v >/dev/null
cp "$(dirname "$0")/bridge.py" "$ROOT/bridge.py"
cat > "$ROOT/update-engine.sh" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
ROOT="$HOME/bedrock-server"; ENGINE="$ROOT/engine"
mkdir -p "$ENGINE"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
API="https://api.github.com/repos/axolotl-pm/PocketMine-MP/releases/latest"
URL="$(curl -fsSL "$API" | python -c 'import json,sys; d=json.load(sys.stdin); a=[x["browser_download_url"] for x in d.get("assets",[]) if x["name"].lower().endswith(".phar")]; print(a[0] if a else "")')"
TAG="$(curl -fsSL "$API" | python -c 'import json,sys; print(json.load(sys.stdin).get("tag_name","unknown"))')"
[ -n "$URL" ] || { echo "No se encontró el PHAR en la release"; exit 1; }
curl -fL "$URL" -o "$TMP/PocketMine-MP.phar"
"$ROOT/bin/php" -v >/dev/null
mkdir -p "$TMP/new"
cp "$TMP/PocketMine-MP.phar" "$TMP/new/"
if [ -f "$ENGINE/PocketMine-MP.phar" ]; then
  cp "$ENGINE/PocketMine-MP.phar" "$ROOT/PocketMine-MP.previous.phar"
fi
# Reemplaza solo el motor, nunca mundos, plugins ni server.properties
cp "$TMP/PocketMine-MP.phar" "$ENGINE/PocketMine-MP.phar"
printf '%s
' "$TAG" > "$ENGINE/ENGINE_VERSION"
echo "Motor instalado: $TAG"
EOF
chmod +x "$ROOT/update-engine.sh"
"$ROOT/update-engine.sh"
echo
echo "Instalación completada. Inicia el panel API con:"
echo "python ~/bedrock-server/bridge.py"
