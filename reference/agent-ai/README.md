# 移植元（参照専用）

Day1に作った既存プロジェクト（/Users/onoderayusei/agent-ai）の、移植に必要な部分だけのコピー。
`.env`・ログ・`agent/`のClaude API直結部分は含まない。移植（CLAUDE.md 第5章の移植マップ）が終わったら、このディレクトリは削除してよい。

動作確認（対象は自作デモのみ。ORCAROUTER_API_KEY が必要）:
    python3 -m http.server 8765 --directory reference/agent-ai/demo_site
    cd reference/agent-ai && python usability_test.py http://127.0.0.1:8765/ "このサービスの新規会員登録を完了する" --headless
