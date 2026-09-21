"""2つ目のデモサイト（点検対象）: 架空の社内備品の貸出予約システム。

1つ目（ECサイト）との違い: ログインが必要な画面、モーダル（<dialog>）、iframe、JSで描画される一覧、
連番IDの参照、キャンセルの二重実行、を持つ。過学習の防止と、ログイン後の画面の診断（Q-6/Q-7）に使う。
標準ライブラリの http.server のみ。状態はメモリ上（DBなし）。予約・キャンセルは擬似（メール送信なし）。

起動:
    python3 demo-site2/server.py --port 8766
    TEST_MODE=1 python3 demo-site2/server.py --port 8766   # /__test/state を有効化

デモ用のログイン情報（架空。実在の認証情報ではない）:
    alice@example.test / demo-pass-1   （山田 花子）
    bob@example.test   / demo-pass-2   （鈴木 次郎）

`# SEEDED FLAW B<n>` のコメントは、仕込んだ不具合の位置（demo-site2/spec/spec.md の仕様と突き合わせる）。
エージェントには見せない（点検対象のコードなので、診断の入力にしないこと）。
"""

import argparse
import html
import json
import os
import random
import string
import time
from http import cookies
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

TEST_MODE = os.environ.get("TEST_MODE", "0") == "1"
WELL_KNOWN_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "well-known")

USERS = {
    "alice@example.test": {"password": "demo-pass-1", "name": "山田 花子"},
    "bob@example.test": {"password": "demo-pass-2", "name": "鈴木 次郎"},
}
SESSIONS = {}  # sid -> {"email": str|None}
ITEMS = {
    1: {"name": "貸出用ノートPC", "stock": 3, "limit": 2},
    2: {"name": "プロジェクター", "stock": 2, "limit": 1},
    3: {"name": "ワイヤレスマイク", "stock": 5, "limit": 3},
}
RESERVATIONS = {}  # id -> {"id","email","item_id","qty","date","status"}
NEXT_RESERVATION_ID = [5001]  # SEEDED FLAW B4: 連番。他人の予約IDが推測できる
FAILED_LOGINS = []  # ログイン失敗の記録（SEEDED FLAW B1: これを見て制限する処理が無い）

CSS = """
body{font-family:'Hiragino Sans','Yu Gothic',Meiryo,sans-serif;margin:0;color:#222;background:#f7f7f4}
header{background:#1c3a5c;color:#fff;padding:12px 24px;display:flex;gap:24px;align-items:center}
header a{color:#fff;text-decoration:none}
main{max-width:860px;margin:24px auto;padding:0 16px}
table{border-collapse:collapse;width:100%;background:#fff}
th,td{border:1px solid #d5d3ca;padding:8px 10px;text-align:left}
.btn{background:#1c3a5c;color:#fff;border:0;padding:8px 16px;border-radius:3px;cursor:pointer}
.err{color:#b3261e}
.muted{color:#ccc}  /* SEEDED FLAW B9: 補助テキストの色が薄く、コントラスト不足 */
footer{max-width:860px;margin:32px auto;padding:0 16px;font-size:12px}
"""


def page(title, body, email=None):
    nav = ""
    if email:
        nav = (f'<a href="/items">備品一覧</a><a href="/reservations">予約一覧</a>'
               f'<a href="/help">ヘルプ</a><span>{html.escape(USERS[email]["name"])} さん</span>'
               f'<a href="/logout">ログアウト</a>')
    else:
        nav = '<a href="/login">ログイン</a><a href="/help">ヘルプ</a>'
    return (f'<!doctype html><html lang="ja"><head><meta charset="utf-8"><title>{html.escape(title)} | 備品貸出予約</title>'
            f'<style>{CSS}</style></head><body><header><strong>備品貸出予約</strong>{nav}</header>'
            f'<main>{body}</main><footer><p class="muted">社内利用限定・問い合わせは総務まで</p></footer></body></html>')


def page_top():
    return page("トップ", "<h1>社内備品の貸出予約</h1><p>ノートPC・プロジェクター・マイクを予約できます。ログインしてご利用ください。</p>"
                          '<p><a href="/login">ログイン</a></p>')


def page_login(error=""):
    err = f'<p class="err" role="alert">{html.escape(error)}</p>' if error else ""
    return page("ログイン",
                f'<h1>ログイン</h1>{err}<form method="post" action="/login">'
                '<p><label>メールアドレス<br><input name="email" type="email"></label></p>'
                '<p><label>パスワード<br><input name="password" type="password"></label></p>'
                '<p><button class="btn" type="submit">ログイン</button></p></form>')


def page_items(email):
    # 一覧はJSで描画する（HTMLに直接は無い）。診断側が、JS描画後の画面を扱えるかの確認用
    data = json.dumps([{"id": i, "name": v["name"], "stock": v["stock"]} for i, v in ITEMS.items()], ensure_ascii=False)
    body = ('<h1>備品一覧</h1><div id="list">読み込み中…</div>'
            f'<script>const items={data};'
            'document.getElementById("list").innerHTML="<table><tr><th>備品</th><th>在庫</th><th></th></tr>"+'
            'items.map(i=>`<tr><td>${i.name}</td><td>${i.stock}</td><td><a href="/items/${i.id}/reserve">予約する</a></td></tr>`).join("")+"</table>";'
            '</script>')
    return page("備品一覧", body, email)


def page_reserve(email, item_id):
    it = ITEMS[item_id]
    body = (f'<h1>{html.escape(it["name"])} の予約</h1><p>在庫: {it["stock"]}（1回の予約は{it["limit"]}点まで）</p>'
            f'<form id="f" method="post" action="/items/{item_id}/reserve">'
            '<p><label>利用日<br><input name="date" type="date"></label></p>'
            '<p><label>数量<br><input name="qty" value="1"></label></p>'  # SEEDED FLAW B3: 数量・日付をサーバーで検証しない
            '<p><button class="btn" type="button" id="open">予約する</button></p></form>'
            # モーダル（<dialog>）で確認する。SEEDED FLAW B8: ボタンが「OK」だけで、何をOKするのか分からない
            '<dialog id="dlg"><p>この内容でよろしいですか？</p><button class="btn" id="ok">OK</button> '
            '<button type="button" id="ng">戻る</button></dialog>'
            '<script>const d=document.getElementById("dlg");'
            'document.getElementById("open").onclick=()=>d.showModal();'
            'document.getElementById("ng").onclick=()=>d.close();'
            'document.getElementById("ok").onclick=()=>document.getElementById("f").submit();</script>')
    return page("予約", body, email)


def page_reservation(email, r):
    it = ITEMS[r["item_id"]]
    # SEEDED FLAW B4: 予約者のチェックが無く、IDが分かれば他人の予約が見える
    who = USERS[r["email"]]["name"]
    cancel = ""
    if r["status"] == "予約中":
        cancel = (f'<form method="post" action="/reservations/{r["id"]}/cancel">'
                  '<button class="btn" type="submit">キャンセルする</button></form>')
    return page(f'予約 {r["id"]}', f'<h1>予約 #{r["id"]}</h1><table><tr><th>備品</th><td>{html.escape(it["name"])}</td></tr>'
                f'<tr><th>数量</th><td>{r["qty"]}</td></tr><tr><th>利用日</th><td>{html.escape(r["date"])}</td></tr>'
                f'<tr><th>予約者</th><td>{html.escape(who)}</td></tr><tr><th>状態</th><td>{r["status"]}</td></tr></table>{cancel}',
                email)


def page_reservations(email):
    rows = "".join(
        f'<tr><td><a href="/reservations/{r["id"]}">#{r["id"]}</a></td><td>{html.escape(ITEMS[r["item_id"]]["name"])}</td>'
        f'<td>{r["qty"]}</td><td>{html.escape(r["date"])}</td><td>{r["status"]}</td></tr>'
        for r in RESERVATIONS.values() if r["email"] == email)
    table = ('<table><tr><th>ID</th><th>備品</th><th>数量</th><th>利用日</th><th>状態</th></tr>' + rows + '</table>') if rows \
        else "<p>予約はまだありません。</p>"
    return page("予約一覧", f"<h1>予約一覧</h1>{table}", email)


def page_help(email=None):
    # iframe内にFAQを表示する
    return page("ヘルプ", '<h1>ヘルプ</h1><iframe src="/help/faq" title="よくある質問" width="100%" height="260"></iframe>', email)


def page_faq():
    return ('<!doctype html><html lang="ja"><head><meta charset="utf-8"><title>よくある質問</title></head><body>'
            '<h2>よくある質問</h2><ul>'
            # SEEDED FLAW B10: 誤字（「ください」→「くだいさい」）と、存在しないページへのリンク
            '<li>予約のキャンセルは、ご利用日の前日までに行ってくだいさい。</li>'
            '<li>紛失・破損の場合は、<a href="/help/lost-item">こちら</a>から総務へ連絡してください。</li>'
            '</ul></body></html>')


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def _sess(self):
        c = cookies.SimpleCookie(self.headers.get("Cookie", ""))
        sid = c["sid"].value if "sid" in c else None
        new_cookie = None
        if sid not in SESSIONS:
            sid = "".join(random.choices(string.ascii_lowercase + string.digits, k=20))
            SESSIONS[sid] = {"email": None}
            new_cookie = f"sid={sid}; Path=/; HttpOnly"
        return SESSIONS[sid], new_cookie

    def _send(self, status, body, ctype="text/html; charset=utf-8", cookie=None, location=None):
        data = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        if cookie:
            self.send_header("Set-Cookie", cookie)
        if location:
            self.send_header("Location", location)
        self.end_headers()
        self.wfile.write(data)

    def _redirect(self, location, cookie=None):
        self._send(303, "", location=location, cookie=cookie)

    def _form(self):
        n = int(self.headers.get("Content-Length") or 0)
        return {k: v[0] for k, v in parse_qs(self.rfile.read(n).decode("utf-8")).items()}

    def do_GET(self):
        sess, ck = self._sess()
        path = urlparse(self.path).path
        email = sess["email"]
        if path == "/":
            return self._send(200, page_top(), cookie=ck)
        if path == "/login":
            return self._send(200, page_login(), cookie=ck)
        if path == "/logout":
            sess["email"] = None
            return self._redirect("/login", ck)
        if path == "/help":
            return self._send(200, page_help(email), cookie=ck)
        if path == "/help/faq":
            return self._send(200, page_faq(), cookie=ck)
        if path.startswith("/.well-known/"):
            name = path[len("/.well-known/"):]
            fp = os.path.join(WELL_KNOWN_DIR, name)
            if not name or "/" in name or ".." in name or not os.path.isfile(fp):
                return self._send(404, "not found", "text/plain; charset=utf-8")
            with open(fp, "r", encoding="utf-8") as f:
                return self._send(200, f.read(), "text/plain; charset=utf-8")
        if path == "/__test/state":
            if not TEST_MODE:
                return self._send(404, json.dumps({"error": "not_found"}), "application/json")
            return self._send(200, json.dumps({
                "reservationCount": len(RESERVATIONS),
                "stock": {str(i): v["stock"] for i, v in ITEMS.items()},
                "failedLoginCount": len(FAILED_LOGINS),
                "reservations": [{"id": r["id"], "email": r["email"], "qty": r["qty"], "status": r["status"]}
                                 for r in RESERVATIONS.values()],
            }, ensure_ascii=False), "application/json; charset=utf-8")
        # ここから先はログイン必須
        if path in ("/items", "/reservations") or path.startswith("/items/") or path.startswith("/reservations/"):
            if not email:
                return self._redirect("/login", ck)
            if path == "/items":
                return self._send(200, page_items(email), cookie=ck)
            if path == "/reservations":
                return self._send(200, page_reservations(email), cookie=ck)
            parts = path.strip("/").split("/")
            if parts[0] == "items" and len(parts) == 3 and parts[2] == "reserve" and parts[1].isdigit() and int(parts[1]) in ITEMS:
                return self._send(200, page_reserve(email, int(parts[1])), cookie=ck)
            if parts[0] == "reservations" and len(parts) == 2 and parts[1].isdigit() and int(parts[1]) in RESERVATIONS:
                return self._send(200, page_reservation(email, RESERVATIONS[int(parts[1])]), cookie=ck)
        return self._send(404, page("見つかりません", "<h1>404 Not Found</h1><p>お探しのページは見つかりませんでした。</p>", email), cookie=ck)

    def do_POST(self):
        sess, ck = self._sess()
        path = urlparse(self.path).path
        form = self._form()
        email = sess["email"]
        if path == "/login":
            u = USERS.get(form.get("email", ""))
            if u and u["password"] == form.get("password", ""):
                sess["email"] = form["email"]
                return self._redirect("/items", ck)
            FAILED_LOGINS.append({"email": form.get("email", ""), "at": time.time()})
            # SEEDED FLAW B1: 何回失敗しても、制限もロックもかからない
            return self._send(200, page_login("メールアドレスまたはパスワードが違います。"), cookie=ck)
        if not email:
            return self._redirect("/login", ck)
        parts = path.strip("/").split("/")
        if parts[0] == "items" and len(parts) == 3 and parts[2] == "reserve" and parts[1].isdigit() and int(parts[1]) in ITEMS:
            it = ITEMS[int(parts[1])]
            try:
                qty = int(form.get("qty", "1"))
            except ValueError:
                qty = 1
            # SEEDED FLAW B3: 0・負数・上限超え・在庫超え・過去日付を、サーバーで弾かない（仕様: 1〜上限、未来日）
            it["stock"] -= qty
            rid = NEXT_RESERVATION_ID[0]
            NEXT_RESERVATION_ID[0] += 1
            RESERVATIONS[rid] = {"id": rid, "email": email, "item_id": int(parts[1]), "qty": qty,
                                 "date": form.get("date", ""), "status": "予約中"}
            return self._redirect(f"/reservations/{rid}", ck)
        if parts[0] == "reservations" and len(parts) == 3 and parts[2] == "cancel" and parts[1].isdigit() and int(parts[1]) in RESERVATIONS:
            r = RESERVATIONS[int(parts[1])]
            # SEEDED FLAW B5: 他人の予約もキャンセルできる（予約者を確認しない）
            # SEEDED FLAW B6: キャンセル済みでも、押すたびに在庫が戻る（二重実行で在庫が増える）
            ITEMS[r["item_id"]]["stock"] += r["qty"]
            r["status"] = "キャンセル済み"
            return self._redirect(f"/reservations/{r['id']}", ck)
        return self._send(404, page("見つかりません", "<h1>404 Not Found</h1>", email), cookie=ck)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8766)
    ap.add_argument("--host", default="127.0.0.1")
    a = ap.parse_args()
    srv = ThreadingHTTPServer((a.host, a.port), Handler)
    print(f"demo-site2 on http://{a.host}:{a.port}/ (TEST_MODE={'1' if TEST_MODE else '0'})")
    srv.serve_forever()


if __name__ == "__main__":
    main()
