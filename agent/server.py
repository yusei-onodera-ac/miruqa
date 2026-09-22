"""ワーカーHTTP API(docs/contracts.md の「ワーカーAPI」)。Java層(web/)からはここだけを呼ぶ。

標準ライブラリの http.server のみで動く(依存を増やさない)。認証・課金・DBは作らない
(FR-40と対になる、ワーカー側の対応)。ワーカーが止まっている・遅い・400を返しても
Java層が落ちずに再試行できるよう、エラーはすべて {"error","message"} の形で返す。

起動:
    python3 -m agent.server            #既定 127.0.0.1:8770
"""

import cgi
import hmac
import json
import sys
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from . import config, ledger, loop, planning, specs as specs_module
from .loop import execute_plan
from report.html import build_csv, build_report_html, load_run


def verify_worker_auth(provided, expected):
    """共有秘密の比較(定数時間比較。指揮官レビューで指摘)。純粋関数として切り出し、
    単体テストしやすくしている(agent/tests/test_worker_auth.py参照)。"""
    if not expected:
        return True  # WORKER_AUTH_DISABLED=1のときだけこの分岐に来る(呼び出し元で保証)
    return hmac.compare_digest(provided or "", expected)

RUNS = {}  # runId -> {"thread": Thread, "status": str}
PENDING = {}  # approvalId -> {"runId","kind","action","verdict","event","decision"}
LOCK = threading.Lock()


def _error(status, code, message=""):
    return status, {"error": code, "message": message or code}


def _run_summary(run_data):
    return {
        "runId": run_data.get("runId"),
        "startedAt": run_data.get("startedAt"),
        "target": run_data.get("target"),
        "planId": run_data.get("planId"),
        "status": run_data.get("status"),
        "metrics": run_data.get("metrics"),
    }


def _start_plan_build(url, spec_ids, authorization=None, test_account=None,
                       carry_over_plan_id=None, carry_over_run_id=None):
    netloc = urlparse(url).netloc
    mode = (authorization or {}).get("mode")
    if not config.is_host_allowed(netloc, mode=mode):
        return None, _error(400, "domain_not_allowed", f"{netloc} は許可ドメインではありません")

    # v0.6 P2(L-2)/v0.7(第1b節): Java層が組み立てたauthorizationのhostが、対象URLのホストと
    # 食い違う場合は拒否する(なりすまし・取り違えの防止)。modeも、利用者の入力・AIの出力では
    # 変えられない(Java層がproject.kindと対象の分類から決めたものだけを信用する)。
    if authorization and authorization.get("host") and authorization["host"] != netloc:
        return None, _error(
            400, "authorization_mismatch",
            f"authorization.host({authorization['host']})が対象URLのホスト({netloc})と一致しません",
        )

    plan_id = f"p-{uuid.uuid4().hex[:8]}"

    def worker():
        try:
            planning.build_plan(
                url, spec_ids=spec_ids, plan_id=plan_id, authorization=authorization, test_account=test_account,
                carry_over_plan_id=carry_over_plan_id, carry_over_run_id=carry_over_run_id,
            )
        except Exception as exc:  # 下見・生成の失敗でもプロセスは生きている(FR-30)
            print(f"[worker] plan {plan_id} failed: {exc!r}")
            failed = planning.load_plan(plan_id) or {"planId": plan_id, "target": url, "specIds": spec_ids}
            failed["status"] = "failed"
            failed["error"] = repr(exc)
            planning.save_plan(failed)

    threading.Thread(target=worker, daemon=True).start()
    return plan_id, None


def _start_plan_resume(plan_id):
    plan = planning.load_plan(plan_id)
    if not plan:
        return _error(404, "plan_not_found")
    if plan.get("status") not in ("ready", "approved", "failed"):
        return _error(400, "plan_not_resumable", f"この状態からは続きを生成できません(status={plan.get('status')})")

    plan["status"] = "recon"  # 生成中であることをUIのポーリングに伝える(下見はやり直さないが、状態遷移を揃える)
    planning.save_plan(plan)

    def worker():
        try:
            planning.resume_plan(plan_id)
        except Exception as exc:  # FR-30: 続き生成の失敗でもプロセスは生きている
            print(f"[worker] plan {plan_id} resume failed: {exc!r}")
            failed = planning.load_plan(plan_id) or plan
            failed["status"] = "failed"
            failed["error"] = repr(exc)
            planning.save_plan(failed)

    threading.Thread(target=worker, daemon=True).start()
    return None


def _approve_plan(plan_id, test_case_ids):
    plan = planning.load_plan(plan_id)
    if not plan:
        return None, _error(404, "plan_not_found")
    if plan.get("status") not in ("ready", "approved"):
        return None, _error(400, "plan_not_ready", f"項目書がまだ準備できていません(status={plan.get('status')})")

    approve_ids = set(test_case_ids or [])
    for tc in plan.get("testCases", []):
        selected = tc["id"] in approve_ids
        tc["enabled"] = selected
        tc["approved"] = selected
    plan["status"] = "approved"
    planning.save_plan(plan)
    return plan, None


def _start_run(plan_id, test_account=None, resume_run_id=None):
    """resume_run_id: v0.8第6章。指定すると、その既存run(status=paused)を再開する
    (新しいrunIdは発行しない。呼び出し元=Java層は、既存のrunIdを使い続けられる)。"""
    plan = planning.load_plan(plan_id)
    if not plan:
        return None, _error(404, "plan_not_found")
    if plan.get("status") != "approved":
        return None, _error(400, "plan_not_approved", f"項目書が承認されていません(status={plan.get('status')})")

    resume = bool(resume_run_id)
    run_id = resume_run_id or f"run-{uuid.uuid4().hex[:10]}"

    def approval_resolver(action, verdict):
        appr_id = verdict.get("approvalId") or f"appr-{uuid.uuid4().hex[:10]}"
        entry = {"runId": run_id, "kind": "action", "action": action, "verdict": verdict, "event": threading.Event(), "decision": None}
        with LOCK:
            PENDING[appr_id] = entry
        entry["event"].wait(timeout=config.MAX_DURATION_SEC)
        with LOCK:
            PENDING.pop(appr_id, None)
        return entry["decision"] or "reject"

    def worker():
        try:
            execute_plan(plan_id, run_id=run_id, approval_resolver=approval_resolver, test_account=test_account, resume=resume)
        except Exception as exc:  # ワーカースレッド自体が落ちてもプロセスは生きている(FR-30)
            print(f"[worker] run {run_id} failed: {exc!r}")

    t = threading.Thread(target=worker, daemon=True)
    with LOCK:
        RUNS[run_id] = {"thread": t}
    t.start()
    return run_id, None


class Handler(BaseHTTPRequestHandler):
    server_version = "InspectorWorker/0.1"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        print(f"[worker-api] {self.address_string()} {fmt % args}")

    def _send_json(self, status, obj):
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _send_html(self, status, body):
        data = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _read_json(self):
        length = int(self.headers.get("Content-Length", 0) or 0)
        if not length:
            return {}
        raw = self.rfile.read(length)
        try:
            return json.loads(raw.decode("utf-8"))
        except Exception:
            return None

    def _check_auth(self):
        """v0.6 P1/P2: Java層とワーカー間の共有秘密。フェイルクローズド(指揮官レビューで指摘):
        WORKER_SHARED_SECRETが空でも、WORKER_AUTH_DISABLED=1でなければmain()が起動自体を
        拒否するため、ここに到達する時点では認証は必ず有効になっている。一致しなければ401を返して
        Trueを返す(呼び出し元はこれ以降の処理を打ち切る)。比較は定数時間(verify_worker_auth)。"""
        if config.WORKER_AUTH_DISABLED and not config.WORKER_SHARED_SECRET:
            return False  # 明示的に無効化された開発用途のみ(main()で警告ログ済み)
        provided = self.headers.get("X-Worker-Auth", "")
        if verify_worker_auth(provided, config.WORKER_SHARED_SECRET):
            return False
        status, body = _error(401, "unauthorized", "共有秘密(X-Worker-Auth)が一致しません")
        self._send_json(status, body)
        return True

    def do_GET(self):
        if self._check_auth():
            return
        parsed = urlparse(self.path)
        path, qs = parsed.path, parse_qs(parsed.query)

        if path == "/api/health":
            return self._send_json(200, {"status": "ok", "testMode": config.TEST_MODE, "allowedHosts": sorted(config.ALLOWED_HOSTS)})

        if path == "/api/cost/summary":
            return self._send_json(200, ledger.aggregate())

        if path == "/api/runs":
            summaries = []
            for run_dir in sorted(config.RUNS_DIR.glob("run-*")):
                run_json = run_dir / "run.json"
                if run_json.exists():
                    try:
                        summaries.append(_run_summary(json.loads(run_json.read_text(encoding="utf-8"))))
                    except Exception:
                        continue
            return self._send_json(200, summaries)

        if path.startswith("/api/runs/") and path.endswith("/report"):
            run_id = path.split("/")[3]
            run_dir = config.RUNS_DIR / run_id
            if not (run_dir / "run.json").exists():
                status, body = _error(404, "run_not_found")
                return self._send_json(status, body)
            run = load_run(run_dir)
            return self._send_html(200, build_report_html(run, config.RUNS_DIR))

        if path.startswith("/api/runs/") and path.endswith("/export"):
            run_id = path.split("/")[3]
            run_dir = config.RUNS_DIR / run_id
            if not (run_dir / "run.json").exists():
                status, body = _error(404, "run_not_found")
                return self._send_json(status, body)
            run = load_run(run_dir)
            fmt = (qs.get("format") or ["html"])[0]
            if fmt == "csv":
                plan = planning.load_plan(run.get("planId")) if run.get("planId") else None
                data = build_csv(run, plan).encode("utf-8-sig")  # BOM付き(Excelでの文字化け対策)
                self.send_response(200)
                self.send_header("Content-Type", "text/csv; charset=utf-8")
                self.send_header("Content-Disposition", f'attachment; filename="{run_id}.csv"')
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                return self.wfile.write(data)
            if fmt == "html":
                return self._send_html(200, build_report_html(run, config.RUNS_DIR))
            status, body = _error(400, "unsupported_export_format", "formatは csv または html を指定してください")
            return self._send_json(status, body)

        if path.startswith("/api/runs/") and path.endswith("/cost"):
            run_id = path.split("/")[3]
            run_dir = config.RUNS_DIR / run_id
            if not (run_dir / "run.json").exists():
                status, body = _error(404, "run_not_found")
                return self._send_json(status, body)
            run = json.loads((run_dir / "run.json").read_text(encoding="utf-8"))
            plan_id = run.get("planId")
            # Runの実行分(exec/exploratory/charter)だけでなく、その元になったPlanの下見・
            # 項目書生成(spec-extract/testcase-gen)のコストも合わせて見せる(実際にこのRunに
            # 至るまでにかかった費用の全体像。記録漏れバグの再発防止のため台帳を正とする)。
            entries = [e for e in ledger.read_all() if e.get("runId") == run_id or (plan_id and e.get("planId") == plan_id)]
            return self._send_json(200, ledger.aggregate(entries=entries))

        if path.startswith("/api/runs/"):
            run_id = path.split("/")[3] if len(path.split("/")) > 3 else ""
            run_dir = config.RUNS_DIR / run_id
            if not (run_dir / "run.json").exists():
                status, body = _error(404, "run_not_found")
                return self._send_json(status, body)
            run = json.loads((run_dir / "run.json").read_text(encoding="utf-8"))
            return self._send_json(200, run)

        if path.startswith("/api/specs/"):
            spec_id = path.split("/")[3] if len(path.split("/")) > 3 else ""
            spec = specs_module.load_spec(spec_id)
            if not spec:
                status, body = _error(404, "spec_not_found")
                return self._send_json(status, body)
            return self._send_json(200, spec)

        if path.startswith("/api/plans/") and path.endswith("/cost"):
            plan_id = path.split("/")[3]
            entries = [e for e in ledger.read_all() if e.get("planId") == plan_id]
            return self._send_json(200, ledger.aggregate(entries=entries))

        if path.startswith("/api/plans/"):
            plan_id = path.split("/")[3] if len(path.split("/")) > 3 else ""
            plan = planning.load_plan(plan_id)
            if not plan:
                status, body = _error(404, "plan_not_found")
                return self._send_json(status, body)
            return self._send_json(200, plan)

        if path == "/api/approvals":
            status_filter = (qs.get("status") or ["pending"])[0]
            with LOCK:
                items = []
                if status_filter == "pending":
                    for appr_id, entry in PENDING.items():
                        items.append({"runId": entry["runId"], "approvalId": appr_id, "kind": "action", "action": entry["action"], "verdict": entry["verdict"]})
            return self._send_json(200, items)

        status, body = _error(404, "not_found")
        return self._send_json(status, body)

    def _handle_post_specs(self):
        ctype, _ = cgi.parse_header(self.headers.get("Content-Type", ""))
        if ctype != "multipart/form-data":
            status, body = _error(400, "invalid_content_type", "multipart/form-data(フィールド名 file)で送ってください")
            return self._send_json(status, body)
        environ = {
            "REQUEST_METHOD": "POST",
            "CONTENT_TYPE": self.headers.get("Content-Type", ""),
            "CONTENT_LENGTH": self.headers.get("Content-Length", "0"),
        }
        try:
            form = cgi.FieldStorage(fp=self.rfile, headers=self.headers, environ=environ)
        except Exception as exc:
            status, body = _error(400, "invalid_multipart", f"アップロードの解析に失敗しました: {exc!r}")
            return self._send_json(status, body)

        if "file" not in form or not getattr(form["file"], "filename", None):
            status, body = _error(400, "missing_file", 'フィールド名 "file" でファイルを送ってください')
            return self._send_json(status, body)

        file_item = form["file"]
        filename = Path(file_item.filename).name  # パス区切りを含む値からファイル名だけを取り出す
        ext = Path(filename).suffix.lower()
        if ext not in specs_module.SUPPORTED_TYPES:
            status, body = _error(400, "unsupported_spec_type", f"対応形式: {', '.join(specs_module.SUPPORTED_TYPES)}")
            return self._send_json(status, body)

        tmp_dir = config.SPECS_DIR / f"upload-{uuid.uuid4().hex[:8]}"
        tmp_dir.mkdir(parents=True, exist_ok=True)
        tmp_path = tmp_dir / filename
        tmp_path.write_bytes(file_item.file.read())

        try:
            spec, _llm_calls = specs_module.build_spec(tmp_path, filename=filename)
        except Exception as exc:
            status, body = _error(400, "spec_extraction_failed", f"仕様書の抽出に失敗しました: {exc!r}")
            return self._send_json(status, body)
        specs_module.save_spec(spec, source_path=tmp_path)
        return self._send_json(200, spec)

    def do_POST(self):
        if self._check_auth():
            return
        parsed = urlparse(self.path)
        path = parsed.path

        if path == "/api/specs":
            return self._handle_post_specs()

        if path == "/api/plans":
            body = self._read_json()
            if body is None:
                status, err = _error(400, "invalid_json")
                return self._send_json(status, err)
            url = body.get("url")
            spec_ids = body.get("specIds") or []
            authorization = body.get("authorization")
            # v0.7 P5: testAccountはauthorizationとは別の最上位フィールドにする(意図的な設計。
            # authorizationはPlanに保存されるが、testAccount(パスワードを含む)は保存しない)。
            test_account = body.get("testAccount")
            # v0.8第4章: 前回の結果の引き継ぎ(任意)。carryOverPlanIdが指すPlanが組織スコープ内かは
            # Java層が保証する(ワーカー自身は組織の概念を持たないため、呼び出し元を信頼する設計)。
            carry_over_plan_id = body.get("carryOverPlanId")
            carry_over_run_id = body.get("carryOverRunId")
            if not url:
                status, err = _error(400, "missing_fields", "url は必須です")
                return self._send_json(status, err)
            plan_id, err = _start_plan_build(
                url, spec_ids, authorization=authorization, test_account=test_account,
                carry_over_plan_id=carry_over_plan_id, carry_over_run_id=carry_over_run_id,
            )
            if err:
                status, body = err
                return self._send_json(status, body)
            return self._send_json(200, {"planId": plan_id, "status": "recon"})

        if path.startswith("/api/plans/") and path.endswith("/resume"):
            plan_id = path.split("/")[3] if len(path.split("/")) > 3 else ""
            err = _start_plan_resume(plan_id)
            if err:
                status, body = err
                return self._send_json(status, body)
            return self._send_json(200, {"planId": plan_id, "status": "recon"})

        if path.startswith("/api/plans/") and path.endswith("/approve"):
            plan_id = path.split("/")[3] if len(path.split("/")) > 3 else ""
            body = self._read_json()
            if body is None:
                status, err = _error(400, "invalid_json")
                return self._send_json(status, err)
            plan, err = _approve_plan(plan_id, body.get("testCaseIds") or [])
            if err:
                status, body = err
                return self._send_json(status, body)
            return self._send_json(200, {"planId": plan_id, "status": plan["status"]})

        if path.startswith("/api/plans/") and path.endswith("/cases/propose"):
            # v0.7 3.6a: 自然言語での項目追加(下見・項目書生成の後、文章から追加案を作る)
            plan_id = path.split("/")[3] if len(path.split("/")) > 3 else ""
            body = self._read_json()
            if body is None:
                status, err = _error(400, "invalid_json")
                return self._send_json(status, err)
            plan = planning.load_plan(plan_id)
            if not plan:
                status, err = _error(404, "plan_not_found")
                return self._send_json(status, err)
            proposals, _llm_call, err = planning.propose_test_cases(plan, body.get("text") or "")
            planning.save_plan(plan)
            if err:
                status, err_body = err
                return self._send_json(status, err_body)
            remaining = max(0, config.TESTCASE_ADD_MAX_REQUESTS_PER_PLAN - plan.get("testCaseAddRequestCount", 0))
            return self._send_json(200, {"planId": plan_id, "proposals": proposals, "requestsRemaining": remaining})

        if path.startswith("/api/plans/") and path.endswith("/cases") and "propose" not in path:
            # v0.7 3.6a: 追加案のうち、利用者がONにしたものだけを項目書へ採用する
            plan_id = path.split("/")[3] if len(path.split("/")) > 3 else ""
            body = self._read_json()
            if body is None:
                status, err = _error(400, "invalid_json")
                return self._send_json(status, err)
            plan = planning.load_plan(plan_id)
            if not plan:
                status, err = _error(404, "plan_not_found")
                return self._send_json(status, err)
            plan, added = planning.adopt_test_cases(plan, body.get("acceptedIds") or [])
            planning.save_plan(plan)
            return self._send_json(200, plan)

        if path == "/api/runs":
            body = self._read_json()
            if body is None:
                status, err = _error(400, "invalid_json")
                return self._send_json(status, err)
            plan_id = body.get("planId")
            if not plan_id:
                status, err = _error(400, "missing_fields", "planId は必須です")
                return self._send_json(status, err)
            # v0.7 P5: testAccountはrun.jsonに保存しない。実行のたびに呼び出し元(Java層)が
            # 復号して再送する(ワーカー側は永続化しない設計)。
            test_account = body.get("testAccount")
            run_id, err = _start_run(plan_id, test_account=test_account)
            if err:
                status, body = err
                return self._send_json(status, body)
            return self._send_json(200, {"runId": run_id, "status": "queued"})

        if path.startswith("/api/runs/") and path.endswith("/cancel"):
            # v0.6 P2(緊急停止)→v0.8第6章(中断・再開): 協調的な停止要求。次のステップ境界
            # (TestCase間・探索ステップ間・LLM呼び出し前)で検出され、ブラウザを閉じて
            # "paused"(再開可能)として終了する(即座には止まらない)。呼び出し元(Java層)は、
            # 本文に{"reason": "credit_exhausted"}等を付けられる(省略時はuser_stop=利用者操作)。
            run_id = path.split("/")[3] if len(path.split("/")) > 3 else ""
            run_dir = config.RUNS_DIR / run_id
            if not (run_dir / "run.json").exists():
                status, body = _error(404, "run_not_found")
                return self._send_json(status, body)
            body = self._read_json() or {}
            loop.request_cancel(run_id, reason=body.get("reason"))
            return self._send_json(200, {"runId": run_id, "status": "cancel_requested"})

        if path.startswith("/api/runs/") and path.endswith("/resume"):
            # v0.8第6章: 中断(paused)した実行を、完了済みのTestCase・探索は再実行せず、
            # 続きから再開する。二重課金の防止(request_id冪等)はWeb層側のクレジット消費で担保する。
            run_id = path.split("/")[3] if len(path.split("/")) > 3 else ""
            run_dir = config.RUNS_DIR / run_id
            run_json_path = run_dir / "run.json"
            if not run_json_path.exists():
                status, body = _error(404, "run_not_found")
                return self._send_json(status, body)
            existing = json.loads(run_json_path.read_text(encoding="utf-8"))
            if existing.get("status") != "paused":
                status, body = _error(400, "not_paused", f"再開できるのはpausedの実行のみです(現在: {existing.get('status')})")
                return self._send_json(status, body)
            body = self._read_json() or {}
            test_account = body.get("testAccount")
            run_id, err = _start_run(existing.get("planId"), test_account=test_account, resume_run_id=run_id)
            if err:
                status, body = err
                return self._send_json(status, body)
            return self._send_json(200, {"runId": run_id, "status": "resuming"})

        if path.startswith("/api/approvals/"):
            appr_id = path.split("/")[3] if len(path.split("/")) > 3 else ""
            body = self._read_json()
            if body is None or body.get("decision") not in ("approve", "reject"):
                status, err = _error(400, "invalid_decision", 'decision は "approve" または "reject"')
                return self._send_json(status, err)
            with LOCK:
                entry = PENDING.get(appr_id)
            if not entry:
                status, err = _error(404, "approval_not_found")
                return self._send_json(status, err)
            entry["decision"] = body["decision"]
            entry["event"].set()
            return self._send_json(200, {"approvalId": appr_id, "decision": body["decision"]})

        status, body = _error(404, "not_found")
        return self._send_json(status, body)


def _cleanup_orphaned_state():
    """v0.6 P2(緊急停止・再起動時の整理): ワーカーの再起動で進行中の状態(会話履歴・
    ブラウザセッション)が失われ、Plan/Runが宙に浮く事象が、P1補修中に実際に発生した
    (recon状態のまま孤立)。再開はせず、UIから見て「中断された」と分かる状態に整理する。"""
    cleaned_plans = 0
    for plan_path in config.PLANS_DIR.glob("*/plan.json"):
        try:
            plan = json.loads(plan_path.read_text(encoding="utf-8"))
        except Exception:
            continue
        if plan.get("status") == "recon":
            plan["status"] = "failed"
            plan["note"] = "ワーカーの再起動により、生成が中断されました。もう一度お試しください。"
            plan_path.write_text(json.dumps(plan, ensure_ascii=False, indent=1), encoding="utf-8")
            cleaned_plans += 1

    cleaned_runs = 0
    for run_path in config.RUNS_DIR.glob("run-*/run.json"):
        try:
            run = json.loads(run_path.read_text(encoding="utf-8"))
        except Exception:
            continue
        if run.get("status") in ("running", "waiting_approval", "queued"):
            # v0.8第6章: ワーカー再起動も「中断(paused)」で確定し、失敗にしない(再開できる)。
            run["status"] = "paused"
            run["pauseReason"] = "worker_restart"
            run["summary"] = "ワーカーの再起動のため、一時停止しました(部分結果)。再開すると、続きから実行できます。"
            run_path.write_text(json.dumps(run, ensure_ascii=False, indent=1), encoding="utf-8")
            cleaned_runs += 1

    if cleaned_plans or cleaned_runs:
        print(f"[worker] 起動時の整理: 孤立したPlan {cleaned_plans}件をfailedに、孤立したRun {cleaned_runs}件をpausedに整理しました。")


def main():
    # v0.6 P2修正(指揮官レビュー): フェイルクローズド。共有秘密が空のまま無警告で起動しない
    if not config.WORKER_SHARED_SECRET:
        if not config.WORKER_AUTH_DISABLED:
            sys.exit(
                "[worker] WORKER_SHARED_SECRETが未設定です。認証なしでの起動は既定で拒否します。\n"
                "         .envに WORKER_SHARED_SECRET=$(openssl rand -hex 32) を設定するか、\n"
                "         開発用途に限り WORKER_AUTH_DISABLED=1 を明示してください。"
            )
        print("[worker] 警告: WORKER_AUTH_DISABLED=1のため、ワーカーAPIは無認証で起動します(開発専用)。", file=sys.stderr)
    _cleanup_orphaned_state()
    print(f"worker API: http://{config.WORKER_HOST}:{config.WORKER_PORT}/  (ALLOWED_HOSTS={config.ALLOWED_HOSTS})")
    ThreadingHTTPServer((config.WORKER_HOST, config.WORKER_PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
