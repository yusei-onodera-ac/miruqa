"""設定の一元管理。すべて環境変数(.env)から読む。モデルIDはコードに書かない(AC-19)。"""

import os
from pathlib import Path

from dotenv import load_dotenv

load_dotenv()

# v0.7: サービス名。UI(Java層)と同じ環境変数名(PRODUCT_NAME)を見て、設定1箇所にする。
PRODUCT_NAME = os.environ.get("PRODUCT_NAME", "MiruQA")

BASE_DIR = Path(__file__).resolve().parent.parent
RUNS_DIR = BASE_DIR / "runs"
BENCH_RESULTS_DIR = BASE_DIR / "bench" / "results"
SPECS_DIR = RUNS_DIR / "specs"
PLANS_DIR = RUNS_DIR / "plans"
RUNS_DIR.mkdir(exist_ok=True)
SPECS_DIR.mkdir(exist_ok=True)
PLANS_DIR.mkdir(exist_ok=True)

# --- OrcaRouter(絶対条件: すべてのLLM呼び出しはここ経由) ---
ORCAROUTER_BASE_URL = os.environ.get("ORCAROUTER_BASE_URL", "https://api.orcarouter.ai/v1")
ORCAROUTER_API_KEY = os.environ.get("ORCAROUTER_API_KEY", "")

# アプリが知るのは Named Router 名(または orcarouter/auto、orcarouter/free)だけ。
# モデルIDはコードに固定しない(AC-19)。
ORCA_MODEL = os.environ.get("ORCA_MODEL", "orcarouter/auto")

# 429/5xx/無料枠拒否時に extra_body={"models": [...], "route": "fallback"} で試す候補(カンマ区切り、最大5)。
# 空なら未設定(フォールバックしない)。値は設定であり、コードには書かない。
_fallback_raw = os.environ.get("ORCA_FALLBACK_MODELS", "")
ORCA_FALLBACK_MODELS = [m.strip() for m in _fallback_raw.split(",") if m.strip()][:5]

# 無料枠が拒否(err_free_prompt_cap / err_free_access_denied)したときに切り替える有料モデル。
# 空なら「無料→有料の自前切替」はしない(その旨を復旧ログに残す)。
ORCA_PAID_FALLBACK_MODEL = os.environ.get("ORCA_PAID_FALLBACK_MODEL", "")

# Firewall評価API用のキー(使える場合のみ)。空なら自前PolicyGateのみで判定する。
ORCA_FIREWALL_KEY = os.environ.get("ORCA_FIREWALL_KEY", "")
ORCA_FIREWALL_BASE_URL = os.environ.get("ORCA_FIREWALL_BASE_URL", "https://api.orcarouter.ai")

# --- 許可ドメイン(絶対条件: 診断対象は自作デモと自分たちのサイトだけ) ---
_hosts_raw = os.environ.get("ALLOWED_HOSTS", "127.0.0.1:8765")
ALLOWED_HOSTS = {h.strip() for h in _hosts_raw.split(",") if h.strip()}

# --- SSRF対策(agent/security.py)の明示的な例外。ループバック(127.0.0.1等、自作デモサイト)を
# 拒否リストの対象から外す。既定はALLOWED_HOSTSと同じ(未設定ならすべての許可ドメインを例外にする) ---
_sandbox_raw = os.environ.get("SANDBOX_HOSTS", "")
SANDBOX_HOSTS = {h.strip() for h in _sandbox_raw.split(",") if h.strip()} or set(ALLOWED_HOSTS)

# --- v0.7(第1a節、モードC=公開ページ・読み取り専用)。テスト専用のホスト名の別名: 実際は
# ローカル(自作デモサイト)だが、モードCの検証のためだけに「公開ホスト」として扱う
# (開発方針: 実在する第三者のサイトにはアクセスせず、自作デモサイトで検証する)。
# 本番では未設定(空)にする。 ---
_readonly_test_public_raw = os.environ.get("READONLY_TEST_PUBLIC_HOSTS", "")
READONLY_TEST_PUBLIC_HOSTS = {h.strip() for h in _readonly_test_public_raw.split(",") if h.strip()}

# モードCの低速・低負荷の上限(第1a節3)。値は仕様書の記載どおり(未測定の仮値)。
READONLY_MAX_PAGES = int(os.environ.get("READONLY_MAX_PAGES", "12"))
READONLY_MIN_REQUEST_INTERVAL_SEC = float(os.environ.get("READONLY_MIN_REQUEST_INTERVAL_SEC", "1.0"))

# モードCで名乗るUser-Agent(第1a節4)。連絡先URLは、事業側の正式なものが決まるまでの仮の値。
MIRUQA_VERSION = os.environ.get("MIRUQA_VERSION", "0.7")
_readonly_contact = os.environ.get("READONLY_CONTACT_URL", "")
MIRUQA_USER_AGENT = f"MiruQA/{MIRUQA_VERSION}" + (f" (+{_readonly_contact})" if _readonly_contact else " (test-mode)")

# --- 拒否リスト(L-4): 審査員・協賛企業・政府機関等のホスト名は、ALLOWED_HOSTSの設定ミスに
# 関わらず、常に遮断する(二重の防御)。既定値は開発者からの指摘(2026-09-21)に基づく。 ---
_denylist_suffixes_raw = os.environ.get("DENYLIST_HOSTNAME_SUFFIXES", ".go.jp,.lg.jp")
DENYLIST_HOSTNAME_SUFFIXES = [s.strip().lower() for s in _denylist_suffixes_raw.split(",") if s.strip()]

_denylist_hosts_raw = os.environ.get("DENYLIST_HOSTNAMES", "cyberace.co.jp,orcarouter.ai")
DENYLIST_HOSTNAMES = {h.strip().lower() for h in _denylist_hosts_raw.split(",") if h.strip()}


def is_denied_hostname(hostname: str):
    """(denied: bool, reason: str|None)を返す。ホスト名(ポートを含まない)の完全一致・
    サブドメイン一致(協賛企業等)・サフィックス一致(政府・公共機関等)を見る。"""
    host_lower = hostname.lower()
    for suffix in DENYLIST_HOSTNAME_SUFFIXES:
        if host_lower.endswith(suffix):
            return True, f"拒否リスト(政府・公共機関等のドメイン: {suffix})に一致した"
    for denied in DENYLIST_HOSTNAMES:
        if host_lower == denied or host_lower.endswith("." + denied):
            return True, f"拒否リスト(審査員・協賛企業等: {denied})に一致した"
    return False, None

# --- L4(能動テスト) ---
TEST_MODE = os.environ.get("TEST_MODE", "0") == "1"
L4_MAX_COUNT = int(os.environ.get("L4_MAX_COUNT", "10"))
L4_MIN_INTERVAL_MS = int(os.environ.get("L4_MIN_INTERVAL_MS", "100"))

# --- ループ上限 ---
MAX_STEPS = int(os.environ.get("AGENT_MAX_STEPS", "40"))
MAX_DURATION_SEC = int(os.environ.get("AGENT_MAX_DURATION_SEC", "600"))
MAX_COST_USD = float(os.environ.get("AGENT_MAX_COST_USD", "1.0"))
STEP_TIMEOUT_SEC = int(os.environ.get("AGENT_STEP_TIMEOUT_SEC", "30"))
STUCK_THRESHOLD = int(os.environ.get("AGENT_STUCK_THRESHOLD", "3"))

# --- 入力トークン削減の実験(2026-09-21、判断17) ---
# 古いtool結果(画面状態)を要約に置き換え、会話が長くなるほど入力が線形に増える問題を緩和する。
# 既定はOFF(挙動を変えない)。実験・比較のため環境変数で有効化できるようにしている。
COMPACT_TOOL_RESULTS = os.environ.get("AGENT_COMPACT_TOOL_RESULTS", "0") == "1"
COMPACT_KEEP_RECENT_TOOL_RESULTS = int(os.environ.get("AGENT_COMPACT_KEEP_RECENT", "2"))

# macro(rapid_click/navigate_direct)が既に決まっている項目は、LLMのループを使わずコードだけで
# 実行する(判断21)。既定OFF(挙動を変えない)。override_param/fill_abnormalは対象外(対象フィールド・
# 入力内容の判断がページ依存で、コードだけでの安全な自動化が難しいため)。
MACRO_CODE_FASTPATH = os.environ.get("AGENT_MACRO_CODE_FASTPATH", "0") == "1"

# --- LLM呼び出しの再試行 ---
LLM_MAX_RETRIES = int(os.environ.get("LLM_MAX_RETRIES", "4"))
LLM_RETRY_BASE_WAIT_SEC = float(os.environ.get("LLM_RETRY_BASE_WAIT_SEC", "2"))
LLM_MAX_RETRY_AFTER_SEC = float(os.environ.get("LLM_MAX_RETRY_AFTER_SEC", "20"))  # デモが長時間止まらないための上限
LLM_TIMEOUT_SEC = float(os.environ.get("LLM_TIMEOUT_SEC", "30"))

# --- 仕様書取り込み(v0.5) ---
SPEC_MAX_CHUNKS = int(os.environ.get("SPEC_MAX_CHUNKS", "40"))  # 章単位に分割した処理の上限(コスト上限)

# --- 下見・テスト項目書生成(v0.5) ---
RECON_MAX_PAGES = int(os.environ.get("RECON_MAX_PAGES", "12"))  # サイト下見でたどるページ数の上限
TESTCASE_MAX_CASES = int(os.environ.get("TESTCASE_MAX_CASES", "40"))  # 項目書生成の上限件数(コスト上限)
TESTCASE_MAX_STEPS = int(os.environ.get("TESTCASE_MAX_STEPS", "8"))  # 1テスト項目の実行あたりのLLMターン上限
EXPLORATORY_MAX_STEPS = int(os.environ.get("EXPLORATORY_MAX_STEPS", "30"))  # 探索的テストのステップ数上限
# P6中核(探索の成果): 実LLMのサンプルで、探索を始めてすぐ finish_exploration を呼んで
# 成果0件のまま終わる回があった(過去の実行事例はAPI障害が原因だったが、原因を問わず
# 「最低限は実際に操作してから終える」歯止めを入れる)。この件数に満たないうちの
# finish_exploration は無視し、操作を続けさせる(EXPLORATORY_MAX_STEPSの範囲内なので無限ループはしない)。
EXPLORATORY_MIN_STEPS_BEFORE_FINISH = int(os.environ.get("EXPLORATORY_MIN_STEPS_BEFORE_FINISH", "5"))

# --- 自然言語での項目追加(v0.7 3.6a) ---
TESTCASE_ADD_MAX_TEXT_CHARS = int(os.environ.get("TESTCASE_ADD_MAX_TEXT_CHARS", "1000"))  # 入力文の上限字数
TESTCASE_ADD_MAX_PROPOSALS = int(os.environ.get("TESTCASE_ADD_MAX_PROPOSALS", "5"))  # 1回の依頼で作る追加案の上限件数
TESTCASE_ADD_MAX_REQUESTS_PER_PLAN = int(os.environ.get("TESTCASE_ADD_MAX_REQUESTS_PER_PLAN", "10"))  # プランあたりの追加依頼の上限回数(コスト上限)

# --- コスト上限(FR-28の実測に基づく歯止め。2026-09-21、実測コストが記録漏れで説明できない
# 差額になった実測を受けて追加。超えたらLLM呼び出しをその場で止め、部分結果のまま穏やかに終了する) ---
# 「1件あたり」は run(実行)・plan(下見+項目書生成)・spec(仕様書抽出)それぞれを1スコープとして数える
# (agent/ledger.py の check_budget 参照)。値は小さめを既定にし、検証時の想定外コストを防ぐ。
LLM_BUDGET_PER_RUN_USD = float(os.environ.get("LLM_BUDGET_PER_RUN_USD", "0.5"))
LLM_BUDGET_PER_DAY_USD = float(os.environ.get("LLM_BUDGET_PER_DAY_USD", "2.0"))

# --- 障害注入モード(FR-32) ---
FAULT_INJECTION = os.environ.get("FAULT_INJECTION", "0") == "1"
# "429,5xx,timeout" のように、注入する障害の種類をカンマ区切りで指定
FAULT_INJECTION_TYPES = [t.strip() for t in os.environ.get("FAULT_INJECTION_TYPES", "429,5xx,timeout").split(",") if t.strip()]
FAULT_INJECTION_RATE = float(os.environ.get("FAULT_INJECTION_RATE", "0.3"))

# --- ワーカーHTTP API(Java層との境界。docs/contracts.md) ---
WORKER_HOST = os.environ.get("WORKER_HOST", "127.0.0.1")
WORKER_PORT = int(os.environ.get("WORKER_PORT", "8770"))
# v0.6 P1〜P2修正: Java層とワーカー間の共有秘密。ワーカーは127.0.0.1のみで待ち受けるが、
# それに加えた多層防御。フェイルクローズド(開発時のレビューで指摘): 秘密が空のまま起動するのを
# 既定で拒否し、明示的にWORKER_AUTH_DISABLED=1を指定したときだけ無認証起動を許す(警告ログを出す)。
WORKER_SHARED_SECRET = os.environ.get("WORKER_SHARED_SECRET", "")
WORKER_AUTH_DISABLED = os.environ.get("WORKER_AUTH_DISABLED", "0") == "1"

# --- ブラウザ ---
VIEWPORT = {"width": 1280, "height": 800}
HEADLESS = os.environ.get("AGENT_HEADLESS", "1") == "1"


def is_host_allowed(netloc: str, mode: str = None) -> bool:
    """ホスト名(またはhost:port)が診断対象として許可されるか。拒否リスト(L-4)に載っている
    ホストは、常に許可しない(設定ミスに対する二重の防御)。

    v0.7(第1節): mode="local"のときは、固定のALLOWED_HOSTS一覧との完全一致を求めない
    (利用者の手元の任意のローカル・プライベートな対象を診断できるようにするため)。
    その代わり、実際にローカル・プライベートに解決できることを確認する(security参照)。

    v0.7(第1a節): mode="readonly"のときも、ALLOWED_HOSTSとの完全一致を求めない(任意の公開
    ページが対象のため)。その代わり、公開ホストに解決できること(ループバック・プライベート・
    メタデータでないこと)を確認する。それ以外(mode未指定・staging)は、従来どおりALLOWED_HOSTS
    との完全一致を要求する。"""
    hostname = netloc.rsplit(":", 1)[0] if ":" in netloc else netloc
    denied, _reason = is_denied_hostname(hostname)
    if denied:
        return False
    if mode == "local":
        from . import security  # 循環importを避けるため遅延import
        return security.is_local_or_private_hostname(netloc)
    if mode == "readonly":
        from . import security
        return security.is_public_hostname(netloc)
    return netloc in ALLOWED_HOSTS


def model_for_purpose(purpose: str) -> str:
    """用途別(l1-text/l2-vision/verify/plan/judge)のNamed Router名。
    ORCA_MODEL_<PURPOSE> が未設定なら既定の ORCA_MODEL を使う。
    どちらもモデルIDではなくNamed Router名/エイリアスのみ(AC-19: コードにモデルIDを書かない)。"""
    key = f"ORCA_MODEL_{purpose.upper().replace('-', '_')}"
    return os.environ.get(key) or ORCA_MODEL
