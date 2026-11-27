#!/data/data/com.termux/files/usr/bin/bash
cd "$HOME/bedrock-server"
python - <<'PY'
import urllib.request, json
r=urllib.request.Request("http://127.0.0.1:8765/start",data=b"{}",headers={"Content-Type":"application/json"},method="POST")
print(urllib.request.urlopen(r).read().decode())
PY
