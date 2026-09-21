"""ブラウザ操作(Playwright)。reference/agent-ai/usability_test.py の BrowserSession を移植し、
移植方針に沿って強化したもの。

強化点:
  (1) 許可外ホストへの遷移・リクエストを事前に遮断(page.route)。事後のgo_backはしない。
  (2) 各アクションの前に agent.policy.evaluate を呼ぶ(絶対条件6)。
  (3) rapid_click / navigate_direct / fill_abnormal / override_param を追加(回数・間隔に上限)。
  (4) スクショはファイル保存(runsディレクトリ配下)。base64をメモリに溜めない。
  (5) ネットワークのrequest/responseを記録(L4の判定用)。
  (6) ペルソナごとのシステムプロンプト断片とviewport。
  (7) 詰まり検知(同一状態がK回続く)、CAPTCHA・ログイン要求での中断をコードで実装。
"""

import hashlib
import math
import time
from urllib.parse import urljoin, urlparse

from . import config, policy, security

MAX_TEXT_CHARS = 800
MAX_ELEMENTS = 40
MAX_FIELDS = 25

GATHER_JS = """
() => {
  const sel = 'a[href], button, input, select, textarea, [role="button"], [role="link"], [onclick]';
  const isVisible = (el) => {
    const r = el.getBoundingClientRect();
    const s = getComputedStyle(el);
    return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none';
  };
  document.querySelectorAll('[data-ut-index]').forEach((e) => e.removeAttribute('data-ut-index'));
  const out = [];
  let i = 0;
  for (const el of document.querySelectorAll(sel)) {
    if (!isVisible(el)) continue;
    el.setAttribute('data-ut-index', String(i));
    const tag = el.tagName.toLowerCase();
    const isField = tag === 'input' || tag === 'textarea' || tag === 'select';
    const type = el.type || '';
    const text = (el.innerText || el.getAttribute('aria-label') || el.getAttribute('title')
      || (type === 'submit' || type === 'button' ? el.value : '') || '').trim().slice(0, 80);
    const label = el.labels && el.labels.length
      ? el.labels[0].innerText.trim().slice(0, 80)
      : (el.getAttribute('aria-label') || '');
    out.push({
      i, tag, type, text, label,
      placeholder: el.placeholder || '',
      valueLength: isField ? (el.value || '').length : 0,
      value: isField && type !== 'password' ? (el.value || '').slice(0, 60) : '',
      checked: !!el.checked,
      disabled: !!el.disabled,
    });
    i++;
  }
  return out;
}
"""

HIDDEN_FIELDS_JS = """
() => Array.from(document.querySelectorAll('input[type="hidden"]'))
  .map(el => ({ name: el.name, value: el.value }))
  .filter(h => h.name)
"""

CURSOR_JS = """
(() => {
  const attach = () => {
    if (window.__utCursor) return;
    const dot = document.createElement('div');
    dot.style.cssText = 'position:fixed;z-index:2147483647;width:20px;height:20px;margin:-10px 0 0 -10px;'
      + 'border-radius:50%;background:rgba(255,0,0,.55);border:2px solid #fff;pointer-events:none;left:-50px;top:-50px;';
    document.documentElement.appendChild(dot);
    window.__utCursor = dot;
  };
  const addTrail = (x, y, size, color, life) => {
    const t = document.createElement('div');
    t.style.cssText = `position:fixed;z-index:2147483646;width:${size}px;height:${size}px;`
      + `margin:${-size / 2}px 0 0 ${-size / 2}px;border-radius:50%;background:${color};pointer-events:none;`
      + `left:${x}px;top:${y}px;transition:opacity ${life}ms;`;
    document.documentElement.appendChild(t);
    requestAnimationFrame(() => { t.style.opacity = '0'; });
    setTimeout(() => t.remove(), life);
  };
  document.addEventListener('mousemove', (e) => {
    attach();
    window.__utCursor.style.left = e.clientX + 'px';
    window.__utCursor.style.top = e.clientY + 'px';
    addTrail(e.clientX, e.clientY, 6, 'rgba(255,0,0,.4)', 1500);
  }, true);
  document.addEventListener('mousedown', (e) => {
    attach();
    addTrail(e.clientX, e.clientY, 44, 'rgba(255,140,0,.5)', 600);
  }, true);
})();
"""

INTERRUPT_KEYWORDS = {
    "captcha": ("CAPTCHA", "私はロボットではありません", "ロボットではありません", "reCAPTCHA"),
    "login_required": ("ログインしてください", "ログインが必要です", "パスワードをお忘れ"),
}


def _fn(name, description, properties=None, required=None):
    props = dict(properties or {})
    props.setdefault("reason", {"type": "string", "description": "なぜこの操作を選んだか(短く)"})
    return {
        "type": "function",
        "function": {
            "name": name,
            "description": description,
            "parameters": {"type": "object", "properties": props, "required": required or []},
        },
    }


BROWSER_TOOLS = [
    _fn("get_page_state", "現在のページのURL・テキスト・操作できる要素の一覧を取得する"),
    _fn("navigate", "許可されたサイト内のURLを開く", {"url": {"type": "string"}}, ["url"]),
    _fn("click", "要素一覧の番号で指定した要素まで、マウスを動かしてクリックする", {"index": {"type": "integer"}}, ["index"]),
    _fn(
        "fill",
        "要素一覧の番号で指定した入力欄をマウスでクリックして、キーボードで文字を入力する",
        {"index": {"type": "integer"}, "text": {"type": "string"}},
        ["index", "text"],
    ),
    _fn(
        "select_option",
        "要素一覧の番号で指定した選択欄(select)の値を選ぶ",
        {"index": {"type": "integer"}, "value": {"type": "string"}},
        ["index", "value"],
    ),
    _fn("hover", "要素一覧の番号で指定した要素の上にマウスを動かす(クリックはしない)", {"index": {"type": "integer"}}, ["index"]),
    _fn("scroll", "ページをスクロールする", {"direction": {"type": "string", "enum": ["down", "up"]}}),
    _fn("go_back", "ブラウザの「戻る」を押す"),
    _fn(
        "rapid_click",
        "【実行中のテスト項目が承認済みのときだけ使える】同じ要素を短時間に連続でクリックする(二重送信の確認)。"
        "countは最大10、intervalMsは最小100。",
        {"index": {"type": "integer"}, "count": {"type": "integer"}, "intervalMs": {"type": "integer"}},
        ["index", "count", "intervalMs"],
    ),
    _fn(
        "navigate_direct",
        "【実行中のテスト項目が承認済みのときだけ使える】手順を踏まずに、決済・完了画面などへ直接アクセスする(手順スキップの確認)。",
        {"url": {"type": "string"}},
        ["url"],
    ),
    _fn(
        "fill_abnormal",
        "入力欄に異常な値(空・超長文字列・特殊文字・絵文字・無害なマーカー)を入力する。"
        "このテスト項目の危険度がneeds_approvalの場合は、承認済みのときだけ使える。",
        {
            "index": {"type": "integer"},
            "mode": {"type": "string", "enum": ["empty", "huge", "special_chars", "emoji", "marker_html"]},
        },
        ["index", "mode"],
    ),
    _fn(
        "override_param",
        "【実行中のテスト項目が承認済みのときだけ使える】hiddenフィールドやURLパラメータの値(価格・数量など)を書き換える。",
        {
            "name": {"type": "string"},
            "value": {"type": "string"},
            "kind": {"type": "string", "enum": ["hidden_field", "url_param"]},
        },
        ["name", "value", "kind"],
    ),
]


class PolicyDenied(Exception):
    def __init__(self, verdict, rejected_by_approval=False):
        self.verdict = verdict
        self.rejected_by_approval = rejected_by_approval
        msg = "承認されませんでした" if rejected_by_approval else "ポリシーにより拒否されました"
        super().__init__(f"{msg}: {verdict.get('reason')}")


class StuckDetected(Exception):
    pass


def attempt_login(page, test_account):
    """v0.7 P5: 利用者が事前に登録したテスト用アカウントで、現在のページのログインフォームに
    ログインを試みる(実行時だけ使う。呼び出し元はplan.json/run.jsonにtest_accountを保存しない)。
    パスワード欄が見つからない画面(既にログイン済み、またはログイン不要のサイト)では何もしない。
    絶対条件5「認証を回避しない」との関係: これは第三者の認証を破る操作ではなく、利用者自身が
    所有・登録した対象への、利用者自身のテスト用アカウントでの通常のログインであり、
    CAPTCHA・他人の認証の突破とは別物(モードC(読み取り専用)では、呼び出し元がそもそも
    この関数を呼ばない)。戻り値: ログインを試みたらTrue(成否は問わない)、対象が無ければFalse。"""
    if not test_account or not test_account.get("username") or not test_account.get("password"):
        return False
    try:
        password_field = page.query_selector('input[type="password"]')
        if not password_field:
            return False
        username_field = page.query_selector(
            'input[type="email"], input[name*="email" i], input[name*="user" i], input[type="text"]'
        )
        if not username_field:
            return False
        username_field.fill(test_account["username"])
        password_field.fill(test_account["password"])
        submit = page.query_selector('form button[type="submit"], form input[type="submit"]')
        if submit:
            submit.click()
        else:
            password_field.press("Enter")
        page.wait_for_load_state("domcontentloaded", timeout=8000)
        return True
    except Exception:
        return False


_COMMON_LOGIN_PATHS = ("/login", "/signin", "/sign-in", "/account/login", "/users/login")


def login_if_needed(page, start_url, test_account):
    """v0.7 P5: 対象のトップページ自体にログインフォームが無い(ログインへの誘導リンクだけがある)
    サイトが多いため、まずtest_accountで直接ログインを試み、見つからなければよくある
    ログインパスを順に試す(demo-site2はこのパターン: "/"にはリンクだけで、実際のフォームは
    "/login")。どこにも見つからなければ何もしない(ログイン不要、または未対応の画面構成)。
    戻り値: ログインを試みたらTrue(成否は問わない)。"""
    if not test_account or not test_account.get("username") or not test_account.get("password"):
        return False
    try:
        page.goto(start_url, timeout=15000)
        page.wait_for_load_state("domcontentloaded", timeout=8000)
    except Exception:
        pass
    if attempt_login(page, test_account):
        return True
    for path in _COMMON_LOGIN_PATHS:
        try:
            page.goto(urljoin(start_url, path), timeout=15000)
            page.wait_for_load_state("domcontentloaded", timeout=8000)
        except Exception:
            continue
        if attempt_login(page, test_account):
            return True
    return False


class BrowserSession:
    def __init__(
        self,
        page,
        allowed_netlocs,
        run_dir,
        approval_resolver=None,
        on_verdict=None,
        on_screenshot=None,
        authorization=None,
    ):
        self.page = page
        self.allowed = set(allowed_netlocs)
        self.run_dir = run_dir
        self.approval_resolver = approval_resolver
        self.on_verdict = on_verdict
        self.on_screenshot = on_screenshot
        # v0.6 P2(L-2): Java層が組み立てたauthorization({host, verified, testEnvDeclared,
        # consentId, grantedAt})。所有確認・テスト環境宣言の状態を持つ。無指定(None)なら
        # 空dictにし、testEnvDeclaredが常にFalse扱いになる(=P-SEC操作は許可されない、安全側)。
        self.authorization = authorization or {}
        # 現在実行中のTestCase(dict: id/risk/approved等)。exploratoryフェーズや下見ではNone。
        # set_current_test_case()で切り替える。ゲート判定(_gate/_gate_macro)がこれを参照する。
        self._current_test_case = None

        self.steps = 0
        self.clicks = 0
        self.mouse_pos = (0.0, 0.0)
        self.mouse_distance = 0.0
        self.trace = []
        self.screenshot_paths = []
        self.network_log = []
        self.blocked_requests = []
        self.dialogs_seen = []
        self.policy_log = []
        self._state_signatures = []
        self._shot_seq = 0
        self.interrupted = None  # None | "captcha" | "login_required" | "stuck" | "rate_limited"

        self._readonly_last_request_at = {}  # host -> 直近のdocument取得時刻(v0.7第1a節3、低速化)
        self._install_guards()

    # --- ガード類(コンストラクタで一度だけ設置) ---
    def _install_guards(self):
        def route_handler(route, request):
            netloc = urlparse(request.url).netloc
            mode = self.authorization.get("mode")
            if netloc and netloc not in self.allowed:
                self.blocked_requests.append({"url": request.url, "resourceType": request.resource_type, "t": time.time(), "reason": "not_allowed"})
                return route.abort()
            # 許可リストに載っているnetlocでも、DNSの再バインディング(許可後にIPだけが
            # プライベート/メタデータアドレスへ変化する攻撃)に備え、名前解決したIPも毎回検証する
            # (v0.6 P2、S-2)。config.SANDBOX_HOSTSに入っている自作デモサイトのループバックだけは例外
            if netloc:
                safe, reason = security.check_netloc_safe(netloc, mode=mode)
                if not safe:
                    self.blocked_requests.append({"url": request.url, "resourceType": request.resource_type, "t": time.time(), "reason": reason})
                    return route.abort()
            if mode == "readonly":
                # v0.7(第1a節1): GET(とページ表示に必要なリソース取得)以外は遮断する。
                # フォームのsubmit・fetch/XHRのPOST/PUT/DELETEも、実際のネットワーク層で止める
                # (クリックの経路に関わらず、結果として送信されるリクエスト自体を遮断するため確実)。
                if request.method != "GET":
                    self.blocked_requests.append({
                        "url": request.url, "resourceType": request.resource_type, "t": time.time(),
                        "reason": f"readonly_non_get_blocked({request.method})",
                    })
                    return route.abort()
                # v0.7(第1a節3): 同一ホストへのページ取得(document)は、1秒に1回以下にする
                # (画像・css・js等の付随リソースまで律速すると実用的な速度で使えなくなるため、
                # ページ本体の取得だけを対象にする)。
                if netloc and request.resource_type == "document":
                    last = self._readonly_last_request_at.get(netloc)
                    now = time.time()
                    if last is not None:
                        wait = config.READONLY_MIN_REQUEST_INTERVAL_SEC - (now - last)
                        if wait > 0:
                            time.sleep(wait)
                    self._readonly_last_request_at[netloc] = time.time()
            return route.continue_()

        self.page.route("**/*", route_handler)

        def on_response(response):
            try:
                self.network_log.append(
                    {
                        "url": response.url,
                        "method": response.request.method,
                        "status": response.status,
                        "resourceType": response.request.resource_type,
                        "t": time.time(),
                    }
                )
                # v0.7(第1a節3): 429/503を受けたら、再試行せずただちに終了する
                # (self.interruptedは既存のloop側の停止チェック(sess.interrupted)がそのまま拾う)。
                if self.authorization.get("mode") == "readonly" and response.status in (429, 503):
                    self.interrupted = "rate_limited"
            except Exception:
                pass

        self.page.on("response", on_response)

        def on_dialog(dialog):
            self.dialogs_seen.append({"type": dialog.type, "message": dialog.message, "t": time.time()})
            try:
                dialog.dismiss()
            except Exception:
                pass

        self.page.on("dialog", on_dialog)

    def set_current_test_case(self, test_case):
        """実行するTestCase(dict、またはNone=探索的テスト/下見)を切り替える。
        risk・approvedはこのdictから読む(LLMは自分で承認済みだと申告できない)。"""
        self._current_test_case = test_case

    # --- 内部ユーティリティ ---
    def _settle(self, timeout_ms=8000):
        try:
            self.page.wait_for_load_state("domcontentloaded", timeout=timeout_ms)
        except Exception:
            pass  # 遅いページ・無限リダイレクト等でタイムアウトしても、状態観察は続ける
        self.page.wait_for_timeout(300)

    @staticmethod
    def _describe(e):
        i, tag, kind = e["i"], e["tag"], e["type"]
        if tag == "a":
            return f'[{i}] リンク "{e["text"]}"'
        if tag == "select":
            return f'[{i}] 選択欄 label="{e["label"] or "(なし)"}"'
        if tag == "input" and kind in ("checkbox", "radio"):
            state = "ON" if e["checked"] else "OFF"
            return f'[{i}] {kind} label="{e["label"] or "(なし)"}" 状態={state}'
        if tag in ("input", "textarea") and kind not in ("submit", "button"):
            value = f'{e["valueLength"]}文字入力済み' if kind == "password" else f'"{e["value"]}"'
            return f'[{i}] 入力欄({kind or "text"}) label="{e["label"] or "(なし)"}" placeholder="{e["placeholder"]}" 現在値={value}'
        return f'[{i}] ボタン "{e["text"]}"' + (" (無効)" if e["disabled"] else "")

    @staticmethod
    def _select(elements):
        fields = [e for e in elements if e["tag"] != "a"][:MAX_FIELDS]
        links = [e for e in elements if e["tag"] == "a"][: MAX_ELEMENTS - len(fields)]
        return sorted(fields + links, key=lambda e: e["i"])

    def _detect_interrupt(self, text):
        for kind, keywords in INTERRUPT_KEYWORDS.items():
            if any(k in text for k in keywords):
                return kind
        return None

    def _state(self):
        try:
            elements = self.page.evaluate(GATHER_JS)
        except Exception as exc:
            return f"URL: {self.page.url}\n(画面の要素取得に失敗しました: {exc!r})"
        shown = self._select(elements)
        try:
            text = self.page.inner_text("body", timeout=5000).strip()[:MAX_TEXT_CHARS]
        except Exception:
            text = "(本文の取得に失敗しました)"
        lines = "\n".join(self._describe(e) for e in shown) or "(操作できる要素なし)"
        if len(elements) > len(shown):
            lines += f"\n(ほか{len(elements) - len(shown)}個の要素は省略)"

        try:
            hidden_fields = self.page.evaluate(HIDDEN_FIELDS_JS) or []
        except Exception:
            hidden_fields = []
        hidden_block = ""
        if hidden_fields:
            hidden_lines = "\n".join(f'- name="{h["name"]}" value="{h["value"]}"' for h in hidden_fields[:20])
            hidden_block = f"\n--- hiddenフィールド(override_paramのnameに使う。表示はされない) ---\n{hidden_lines}"

        interrupt = self._detect_interrupt(text)
        note = ""
        if interrupt:
            self.interrupted = interrupt
            note = "※CAPTCHA/ログイン要求を検知したため、これ以上の操作は行わず状況を報告してください。\n"
        if self.blocked_requests and self.blocked_requests[-1]["t"] > time.time() - 2:
            note += "※許可されていないサイトへのリクエストをブロックしました。\n"

        # 詰まり検知: (URL, 要素シグネチャ)がK回連続で同じなら記録
        sig_src = self.page.url + "|" + "|".join(f'{e["tag"]}:{e["text"]}' for e in shown)
        sig = hashlib.sha1(sig_src.encode("utf-8")).hexdigest()
        self._state_signatures.append(sig)
        recent = self._state_signatures[-config.STUCK_THRESHOLD :]
        if len(recent) == config.STUCK_THRESHOLD and len(set(recent)) == 1:
            note += f"※同じ状態が{config.STUCK_THRESHOLD}回続いています(つまずき)。別の操作を検討するか、断念を検討してください。\n"

        title = self._safe_title()
        return f"{note}URL: {self.page.url}\nタイトル: {title}\n--- 画面のテキスト ---\n{text}\n--- 操作できる要素 ---\n{lines}{hidden_block}"

    def _safe_title(self):
        try:
            return self.page.title()
        except Exception:
            return "(取得失敗)"

    def is_stuck(self):
        recent = self._state_signatures[-config.STUCK_THRESHOLD :]
        return len(recent) == config.STUCK_THRESHOLD and len(set(recent)) == 1

    def _locator(self, index):
        loc = self.page.locator(f'[data-ut-index="{int(index)}"]')
        if loc.count() == 0:
            raise ValueError(f"要素[{index}]が見つかりません。get_page_state で最新の一覧を確認してください。")
        return loc.first

    def _element_label(self, loc):
        try:
            return loc.evaluate(
                "el => ((el.labels && el.labels[0] ? el.labels[0].innerText : '') || el.innerText || el.placeholder"
                " || el.getAttribute('aria-label') || el.type || el.tagName).trim().slice(0, 40)"
            )
        except Exception:
            return ""

    def _save_screenshot(self, jpeg_bytes):
        self._shot_seq += 1
        name = f"step-{self._shot_seq:03d}.jpg"
        path = self.run_dir / name
        path.write_bytes(jpeg_bytes)
        rel = f"{self.run_dir.name}/{name}"
        self.screenshot_paths.append(rel)
        if self.on_screenshot:
            self.on_screenshot(rel)
        return rel

    def _move_mouse_to(self, loc, action, index):
        loc.scroll_into_view_if_needed(timeout=5000)
        box = loc.bounding_box()
        if not box:
            raise ValueError("要素の位置を取得できません。")
        label = self._element_label(loc)
        shot_bytes = self.page.screenshot(type="jpeg", quality=60)
        shot_ref = self._save_screenshot(shot_bytes)
        sx, sy = self.mouse_pos
        tx, ty = box["x"] + box["width"] / 2, box["y"] + box["height"] / 2
        distance = math.hypot(tx - sx, ty - sy)
        n = max(6, min(30, int(distance / 15)))

        started = time.time()
        path = []
        for k in range(1, n + 1):
            p = k / n
            eased = p * p * (3 - 2 * p)
            x, y = sx + (tx - sx) * eased, sy + (ty - sy) * eased
            self.page.mouse.move(x, y)
            path.append([round(x), round(y)])
        self.page.wait_for_timeout(60)

        self.mouse_pos = (tx, ty)
        self.mouse_distance += distance
        duration = time.time() - started
        self.trace.append(
            {
                "action": action,
                "index": index,
                "label": label,
                "from": [round(sx), round(sy)],
                "to": [round(tx), round(ty)],
                "distance_px": round(distance),
                "duration_s": round(duration, 2),
                "path": path,
                "screenshot": shot_ref,
            }
        )
        return f"(マウス移動 {distance:.0f}px / {duration:.1f}秒)\n"

    def _gate(self, tool, args, reason=""):
        tc = self._current_test_case
        action = {
            "tool": tool,
            "args": args,
            "reason": reason,
            "step": self.steps,
            "testCaseId": tc["id"] if tc else None,
            "risk": tc["risk"] if tc else None,
            "testCaseApproved": bool(tc and tc.get("approved")),
            "testEnvDeclared": bool(self.authorization.get("testEnvDeclared")),
            "mode": self.authorization.get("mode"),
        }
        verdict = policy.evaluate(action)
        self.policy_log.append({"action": action, "verdict": verdict})
        if self.on_verdict:
            self.on_verdict(action, verdict)
        if verdict["verdict"] == "deny":
            raise PolicyDenied(verdict)
        if verdict["verdict"] == "pending_approval":
            decision = self.approval_resolver(action, verdict) if self.approval_resolver else "reject"
            if decision != "approve":
                raise PolicyDenied(verdict, rejected_by_approval=True)
        return verdict

    # --- 基本アクション(既存) ---
    def get_page_state(self, reason=""):
        # reason はツールスキーマ上は全ツール共通で付けている(_fn参照)。
        # 律儀にreasonを付けてくるモデル(実際にGemini 3.5 Flashで踏んだ)でも
        # TypeErrorにならないよう、他のアクションメソッドと同様に受け取れるようにする。
        return self._state()

    def navigate(self, url, reason=""):
        self._gate("navigate", {"url": url}, reason)
        self.steps += 1
        try:
            self.page.goto(url, timeout=15000)
        except Exception as exc:
            return f"(ナビゲーションでエラーが発生しました: {exc!r})\n" + self._state()
        self._settle()
        return self._state()

    def click(self, index, reason=""):
        loc = self._locator(index)
        label = self._element_label(loc)
        self._gate("click", {"index": index, "label": label}, reason)
        self.steps += 1
        moved = self._move_mouse_to(loc, "click", index)
        self.page.mouse.down()
        self.page.mouse.up()
        self.clicks += 1
        self._settle()
        return moved + self._state()

    def fill(self, index, text, reason=""):
        loc = self._locator(index)
        label = self._element_label(loc)
        self._gate("type", {"index": index, "label": label, "textLength": len(text)}, reason)
        self.steps += 1
        is_password = False
        try:
            is_password = loc.evaluate("el => el.type === 'password'")
        except Exception:
            pass
        moved = self._move_mouse_to(loc, "fill", index)
        self.page.mouse.down()
        self.page.mouse.up()
        self.clicks += 1
        try:
            loc.fill("", timeout=2000)
        except Exception:
            pass
        self.page.keyboard.type(text, delay=15)
        self.trace[-1]["typed"] = "●" * len(text) if is_password else text
        return moved + self._state()

    def select_option(self, index, value, reason=""):
        loc = self._locator(index)
        label = self._element_label(loc)
        self._gate("select", {"index": index, "label": label, "value": value}, reason)
        self.steps += 1
        loc.select_option(value)
        self._settle()
        return self._state()

    def hover(self, index, reason=""):
        loc = self._locator(index)
        label = self._element_label(loc)
        self._gate("hover", {"index": index, "label": label}, reason)
        self.steps += 1
        moved = self._move_mouse_to(loc, "hover", index)
        return moved + self._state()

    def scroll(self, direction="down", reason=""):
        self._gate("scroll", {"direction": direction}, reason)
        self.steps += 1
        dy = 600 if direction == "down" else -600
        self.page.mouse.wheel(0, dy)
        self.page.wait_for_timeout(200)
        return self._state()

    def go_back(self, reason=""):
        self._gate("back", {}, reason)
        self.steps += 1
        self.page.go_back()
        self._settle()
        return self._state()

    # --- 能動的テスト操作(risk=needs_approvalのTestCaseはTEST_MODE + 承認済みのときだけ、
    #     policy.evaluateが許可。risk=normalのTestCase(P-INPUTの空欄/超長/絵文字等)はそのまま許可) ---
    def rapid_click(self, index, count=10, intervalMs=100, reason=""):
        count = max(1, min(int(count), config.L4_MAX_COUNT))
        interval_ms = max(config.L4_MIN_INTERVAL_MS, int(intervalMs))
        loc = self._locator(index)
        label = self._element_label(loc)
        self._gate_macro("rapid_click", {"index": index, "label": label, "count": count, "intervalMs": interval_ms}, reason)
        self.steps += 1
        before_count = len(self.network_log)
        loc.scroll_into_view_if_needed(timeout=5000)
        box = loc.bounding_box()
        cx, cy = (box["x"] + box["width"] / 2, box["y"] + box["height"] / 2) if box else self.mouse_pos
        self.page.mouse.move(cx, cy)
        self.mouse_pos = (cx, cy)
        for _ in range(count):
            self.page.mouse.down()
            self.page.mouse.up()
            self.clicks += 1
            self.page.wait_for_timeout(interval_ms)
        self._settle()
        after_requests = self.network_log[before_count:]
        return (
            f"(連打 {count}回・間隔{interval_ms}ms を実行しました。発生したリクエスト数: {len(after_requests)})\n"
            + self._state()
        )

    def _gate_macro(self, tool, args, reason):
        tc = self._current_test_case
        if tc is None:
            # 能動的テスト操作は、実行中のTestCaseが分かっているときだけ許可する(LLMが勝手にtestIdを
            # 名乗って承認済みだと主張することはできない設計。探索的テスト中はそもそも呼ばせない)。
            verdict = {
                "verdict": "deny",
                "approvalId": None,
                "reason": "実行中のテスト項目が無いため、能動的テスト操作は実行できない",
                "rule": "test_case_context_required",
                "source": "local",
            }
            self.policy_log.append({"action": {"tool": tool, "args": dict(args), "reason": reason, "step": self.steps}, "verdict": verdict})
            raise PolicyDenied(verdict)
        action = {
            "tool": tool,
            "args": dict(args),
            "reason": reason,
            "step": self.steps,
            "testCaseId": tc["id"],
            "risk": tc["risk"],
            "testCaseApproved": bool(tc.get("approved")),
            "testEnvDeclared": bool(self.authorization.get("testEnvDeclared")),
            "mode": self.authorization.get("mode"),
        }
        verdict = policy.evaluate(action)
        self.policy_log.append({"action": action, "verdict": verdict})
        if self.on_verdict:
            self.on_verdict(action, verdict)
        if verdict["verdict"] == "deny":
            raise PolicyDenied(verdict)
        if verdict["verdict"] == "pending_approval":
            decision = self.approval_resolver(action, verdict) if self.approval_resolver else "reject"
            if decision != "approve":
                raise PolicyDenied(verdict, rejected_by_approval=True)
        return verdict

    def navigate_direct(self, url, reason=""):
        self._gate_macro("navigate_direct", {"url": url}, reason)
        self.steps += 1
        try:
            self.page.goto(url, timeout=15000)
        except Exception as exc:
            return f"(ナビゲーションでエラーが発生しました: {exc!r})\n" + self._state()
        self._settle()
        return self._state()

    # 絶対条件の「非破壊、無害なマーカー文字列のみ」を満たすため、special_charsは実行され得ない
    # 記号・引用符・バックスラッシュ・アンパサンド・改行・全角記号だけで構成する
    # (開発者からの指摘、2026-09-22: 以前は<script>タグと実際のSQL攻撃文字列を含んでいた)。
    ABNORMAL_TEXTS = {
        "empty": "",
        "huge": "あ" * 5000,
        "special_chars": "&%$#@!'\"\\\n;-- 〜＜＞",
        "emoji": "🎉🛒💥😀" * 20,
        "marker_html": "<b>MARKER_XSS_TEST</b>",
    }

    def fill_abnormal(self, index, mode, reason=""):
        text = self.ABNORMAL_TEXTS.get(mode, mode)
        loc = self._locator(index)
        label = self._element_label(loc)
        self._gate_macro("fill_abnormal", {"index": index, "label": label, "mode": mode}, reason)
        self.steps += 1
        moved = self._move_mouse_to(loc, "fill", index)
        try:
            loc.fill("", timeout=2000)
        except Exception:
            pass
        if text:
            self.page.keyboard.type(text, delay=5)
        self.trace[-1]["typed"] = f"(異常入力:{mode})"
        return moved + self._state()

    def override_param(self, name, value, kind="hidden_field", reason=""):
        self._gate_macro("override_param", {"name": name, "value": value, "kind": kind}, reason)
        self.steps += 1
        if kind == "hidden_field":
            try:
                found = self.page.evaluate(
                    "([name, value]) => { const el = document.querySelector(`[name=\"${name}\"]`);"
                    " if (el) { el.value = value; return true; } return false; }",
                    [name, value],
                )
            except Exception as exc:
                return f"(書き換えに失敗しました: {exc!r})"
            if not found:
                return f"(name=\"{name}\" のフィールドは見つかりませんでした。画面のhiddenフィールド一覧を確認してください)\n" + self._state()
            return f"(hiddenフィールド {name} を {value} に書き換えました)\n" + self._state()
        if kind == "url_param":
            from urllib.parse import urlsplit, urlunsplit, parse_qsl, urlencode

            parts = urlsplit(self.page.url)
            q = dict(parse_qsl(parts.query))
            q[name] = value
            new_url = urlunsplit((parts.scheme, parts.netloc, parts.path, urlencode(q), parts.fragment))
            try:
                self.page.goto(new_url, timeout=15000)
            except Exception as exc:
                return f"(ナビゲーションでエラーが発生しました: {exc!r})"
            self._settle()
            return f"(URLパラメータ {name}={value} で遷移しました)\n" + self._state()
        return "Error: kindは hidden_field または url_param を指定してください。"

    def impls(self):
        def logged(name, fn):
            def wrapper(**kwargs):
                shown = ", ".join(f"{k}={v!r}" for k, v in kwargs.items())
                print(f"  -> {name}({shown})", flush=True)
                try:
                    return fn(**kwargs)
                except PolicyDenied as exc:
                    print(f"     denied: {exc}", flush=True)
                    return f"Error(policy): {exc}"

            return wrapper

        names = [
            "get_page_state",
            "navigate",
            "click",
            "fill",
            "select_option",
            "hover",
            "scroll",
            "go_back",
            "rapid_click",
            "navigate_direct",
            "fill_abnormal",
            "override_param",
        ]
        return {n: logged(n, getattr(self, n)) for n in names}
