#!/usr/bin/env bash
# ローカルで、デモサイト・ワーカー・Web層をまとめて起動する（Ctrl-C で全部止まる）。
# 前提: .env（ORCAROUTER_API_KEY など）を作成済み、`pip install -r requirements.txt` 済み、Java 21+ と Maven。
# 使い方: scripts/start-local.sh [--no-demo]
set -euo pipefail
cd "$(dirname "$0")/.."

PY="${PYTHON:-python3}"
[ -x .venv/bin/python ] && PY=".venv/bin/python"
[ -f .env ] || { echo ".env がありません。.env.example をコピーして設定してください。" >&2; exit 1; }

# .env はPython側(agent/config.pyのload_dotenv())は自動で読むが、Java層(mvn spring-boot:run)は
# 読まない。ALLOWED_HOSTS・CREDENTIAL_ENCRYPTION_KEY等をJava側にも渡すため、ここでシェルの
# 環境変数として読み込む(コメント行・空行は無視。値に空白を含む場合は考慮していない簡易実装)。
set -a
# shellcheck disable=SC1091
source .env
set +a

# Web層とワーカーの共有の秘密は、起動のたびに作る（保存しない）。
export WORKER_SHARED_SECRET="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 40)"
export WORKER_BASE_URL="${WORKER_BASE_URL:-http://127.0.0.1:8770}"

pids=()
cleanup() { for p in "${pids[@]:-}"; do kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT INT TERM

if [ "${1:-}" != "--no-demo" ]; then
  TEST_MODE=1 "$PY" demo-site/server.py --port 8765 & pids+=($!)
  TEST_MODE=1 "$PY" demo-site2/server.py --port 8766 & pids+=($!)
  echo "デモサイト: http://127.0.0.1:8765/  http://127.0.0.1:8766/"
fi

"$PY" -m agent.server & pids+=($!)
echo "ワーカー: $WORKER_BASE_URL"

if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 21+ 2>/dev/null || true)"; export JAVA_HOME
fi
echo "MiruQA: http://localhost:8080/  （Ctrl-C で終了）"
( cd web && mvn -q spring-boot:run ) & pids+=($!)
wait
