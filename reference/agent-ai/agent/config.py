import os
from pathlib import Path

from dotenv import load_dotenv

load_dotenv()

BASE_DIR = Path(__file__).resolve().parent.parent
WORKSPACE_DIR = BASE_DIR / "workspace"
LOGS_DIR = BASE_DIR / "logs"

WORKSPACE_DIR.mkdir(exist_ok=True)
LOGS_DIR.mkdir(exist_ok=True)

MAX_TOKENS = int(os.environ.get("AGENT_MAX_TOKENS", "16000"))
EFFORT = os.environ.get("AGENT_EFFORT", "high")

# "anthropic" = 直接 Anthropic API を叩く(既定)。
# "orcarouter" = OrcaRouter (https://docs.orcarouter.ai) のAnthropicネイティブ互換
# エンドポイント経由でClaudeモデルを叩く。tool_runnerや通常のtool useは動くが、
# adaptive thinking / effort / サーバー側web_search・code_executionは
# OrcaRouter側での対応が未確認のため既定では無効化している。
PROVIDER = os.environ.get("AGENT_PROVIDER", "anthropic")

if PROVIDER == "orcarouter":
    ORCAROUTER_BASE_URL = "https://api.orcarouter.ai"
    ORCAROUTER_API_KEY = os.environ["ORCAROUTER_API_KEY"]
    MODEL = os.environ.get("AGENT_MODEL", "anthropic/claude-opus-5")
else:
    MODEL = os.environ.get("AGENT_MODEL", "claude-opus-5")
