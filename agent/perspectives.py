"""観点カタログ(旧 L1〜L4 の置き換え。CHANGE-v0.5.md 第3章)。

人間のQAが持つ「観点表」に近い形をデータで持つ。テスト項目書(TestCase)は、
仕様項目(SpecItem) × ここにある観点から生成する(agent側の生成モジュールが参照する)。

各観点:
  label          : 表示名
  summary        : 主な中身(短い説明)
  screens        : 対象になりやすい画面種別(Plan.siteMap の node.kind と対応)
  judgement      : 主な判定方法("spec_compare"=仕様書との比較, "network_log"=リクエスト数等,
                   "state_check"=/__test/state 等の状態確認, "screen_diff"=画面表示の差分,
                   "checklist"=チェックリストとの突合)
  default_risk   : "normal"|"needs_approval"。TestCase生成時の既定値(個別に上書きされうる)
  risk_note      : リスク判定の補足(P-FLOWのように条件付きで needs_approval になる場合の説明)
  procedure_hint : テスト項目書の手順を作るときにLLMへ渡すヒント文
  checklist      : (任意)チェックリスト形式の観点(P-UX/P-A11Y)で使う個別項目
"""

PERSPECTIVES = {
    "P-FUNC": {
        "label": "機能(正常系・異常系・境界値・同値分割)",
        "summary": "仕様どおりの動作、境界値",
        "screens": ["form", "cart", "checkout", "list", "detail"],
        "judgement": "spec_compare",
        "default_risk": "normal",
        "risk_note": "",
        "procedure_hint": (
            "仕様項目に書かれた条件(数値・境界・許可される値の範囲)について、正常な値・境界値・"
            "異常な値それぞれで実際にどう動くかを確認する手順にする。"
        ),
    },
    "P-FLOW": {
        "label": "画面遷移・状態遷移",
        "summary": "戻る・リロード・手順スキップ・直接アクセス",
        "screens": ["cart", "checkout", "form"],
        "judgement": "screen_diff",
        "default_risk": "normal",
        "risk_note": (
            "対象が決済・注文確定に関わる手順(手順スキップ・直接アクセス・戻る/リロードでの再送信)の"
            "場合だけ needs_approval にする。それ以外の通常の画面遷移確認は normal。"
        ),
        "procedure_hint": "戻る・リロード・URL直接アクセスなど、正規の手順を外れた遷移をしたときの挙動を確認する手順にする。",
    },
    "P-INPUT": {
        "label": "入力検証",
        "summary": "必須・桁数・文字種・空欄・超長・絵文字",
        "screens": ["form", "checkout"],
        "judgement": "screen_diff",
        "default_risk": "normal",
        "risk_note": "",
        "procedure_hint": "入力欄に空欄・超長文字列・特殊文字・絵文字を入れ、サーバーエラーにならず入力エラー表示になるかを確認する手順にする。",
    },
    "P-TEXT": {
        "label": "表示・文言",
        "summary": "誤字・表記ゆれ・仕様書との文言差・ページ間の不一致(送料 等)",
        "screens": ["top", "list", "detail", "cart", "checkout", "static"],
        "judgement": "spec_compare",
        "default_risk": "normal",
        "risk_note": "",
        "procedure_hint": "複数の画面にまたがる金額・文言(送料・価格・表記)が一致しているか、仕様書に書かれた文言と一致しているかを確認する手順にする。",
    },
    "P-LINK": {
        "label": "リンク・画像",
        "summary": "リンク切れ、alt欠落",
        "screens": ["top", "list", "detail", "static"],
        "judgement": "screen_diff",
        "default_risk": "normal",
        "risk_note": "",
        "procedure_hint": "主要な画面のリンク・画像を確認し、リンク切れやalt属性の欠落がないかを確認する手順にする。",
    },
    "P-UX": {
        "label": "ユーザビリティ(ペルソナなし)",
        "summary": "ニールセンの10原則等のチェックリストで、導線・フィードバック・エラー表示・戻り導線を評価",
        "screens": ["top", "list", "detail", "cart", "checkout", "form"],
        "judgement": "checklist",
        "default_risk": "normal",
        "risk_note": "",
        "procedure_hint": "チェックリストの各項目について、画面が満たしているかどうかを確認する手順にする。",
        "checklist": [
            "システム状態の可視性: 今どの段階か・処理中かが利用者に分かるか",
            "実世界との一致: 専門用語でなく利用者に馴染みのある言葉・順序を使っているか",
            "利用者の制御と自由度: 「戻る」「取り消し」の手段があるか",
            "一貫性と標準: ボタンの見た目・文言が画面をまたいで一貫しているか",
            "エラーの防止: 誤操作しやすい導線(小さすぎるボタン・紛らわしい文言)がないか",
            "記憶よりも認識: 前の画面の情報を覚えていなくても操作を続けられるか",
            "柔軟性と効率性: ショートカットがなくても迷わず主要な操作ができるか",
            "美的で最小限のデザイン: 不要な情報で主要な操作が埋もれていないか",
            "エラー認識・診断・回復: エラーメッセージが具体的で、次に何をすればよいか分かるか",
            "ヘルプとマニュアル: 迷ったときに参照できる説明があるか",
        ],
    },
    "P-A11Y": {
        "label": "アクセシビリティ",
        "summary": "alt、コントラスト、ラベル、キーボード操作(WCAGの主要項目)",
        "screens": ["top", "list", "detail", "cart", "checkout", "form"],
        "judgement": "checklist",
        "default_risk": "normal",
        "risk_note": "",
        "procedure_hint": "画像のalt属性、フォーム要素のラベル、文字と背景のコントラストを確認する手順にする。",
        "checklist": [
            "画像に代替テキスト(alt)があるか",
            "入力欄にラベル(label要素またはaria-label)が結びついているか",
            "本文の文字色と背景色のコントラストが十分か",
            "キーボード操作(Tab移動)だけで主要な操作が完結するか",
        ],
    },
    "P-SEC": {
        "label": "セキュリティ・堅牢性",
        "summary": "連打・二重送信、価格・数量の改ざん、エスケープ、エラー露出、連番ID",
        "screens": ["cart", "checkout", "form"],
        "judgement": "network_log",
        "default_risk": "needs_approval",
        "risk_note": "常に needs_approval。TEST_MODE=1・承認済み・回数/間隔上限つきでのみ実行できる(絶対条件5)。",
        "procedure_hint": (
            "連打・手順スキップ・入力異常・価格や数量の改ざん・未エスケープ入力・エラー誘発など、"
            "非破壊で無害なマーカー文字列を使った能動テストの手順にする。"
        ),
    },
    "P-PERF": {
        "label": "表示速度",
        "summary": "遅いページ、巨大ページ",
        "screens": ["top", "list", "detail", "static"],
        "judgement": "screen_diff",
        "default_risk": "normal",
        "risk_note": "",
        "procedure_hint": "ページの応答時間・サイズに異常がないかを確認する手順にする。",
    },
    "P-RESP": {
        "label": "レスポンシブ(C)",
        "summary": "主要な画面幅での崩れ",
        "screens": ["top", "list", "detail", "cart", "checkout", "form"],
        "judgement": "screen_diff",
        "default_risk": "normal",
        "risk_note": "",
        "procedure_hint": "スマートフォン相当の画面幅で表示が崩れていないかを確認する手順にする。",
    },
}

# TestCase.risk の判定で needs_approval を強制する観点(P-SECは常時、P-FLOWは対象で判断)
ALWAYS_NEEDS_APPROVAL = {"P-SEC"}

# v0.7(第1a節、モードC=公開ページ・読み取り専用): 状態を変える操作を伴わない、表示系の観点だけを
# 生成する(P-FUNC/P-FLOW/P-INPUT/P-SECは、フォーム送信・遷移・能動テストを前提とするため除外)。
READONLY_ALLOWED_PERSPECTIVES = {"P-TEXT", "P-LINK", "P-A11Y", "P-UX", "P-RESP", "P-PERF"}

# P-FLOWのうち、決済・注文確定に関わる手順だけneeds_approvalにする際の目印(target/titleに含まれるか判定する用)
CHECKOUT_KEYWORDS = ("checkout", "決済", "注文確定", "購入確定", "complete", "完了")


def risk_for(perspective_id, target="", title=""):
    """観点と対象からTestCaseのrisk("normal"|"needs_approval")を決める。

    P-SECは常にneeds_approval。P-FLOWは対象が決済・注文確定関連のときだけneeds_approval。
    それ以外はperspectiveのdefault_riskに従う(現状すべてnormal)。
    """
    if perspective_id in ALWAYS_NEEDS_APPROVAL:
        return "needs_approval"
    if perspective_id == "P-FLOW":
        text = f"{target} {title}"
        if any(k in text for k in CHECKOUT_KEYWORDS):
            return "needs_approval"
        return "normal"
    return PERSPECTIVES.get(perspective_id, {}).get("default_risk", "normal")


def perspectives_for_screen(kind):
    """画面種別(kind)に合いそうな観点IDの一覧を返す(TestCase生成の絞り込みに使う)。"""
    return [pid for pid, p in PERSPECTIVES.items() if kind in p.get("screens", [])]
