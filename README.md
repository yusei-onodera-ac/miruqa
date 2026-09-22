# MiruQA — AI HACK 2026

URLと（あれば）仕様書を渡すと、AIエージェントが**実際にサイトを操作**して点検し、証拠付きレポートを出す。
連打や手順スキップ・価格改ざんなどの想定外の操作も、**承認済みのテスト項目書の範囲でだけ**実際に試す（脆弱性診断）。
ログインが必要な画面も、暗号化保管したテスト用アカウントで点検できる。


| ドキュメント | 内容 |
|---|---|
| [`docs/SERVICE-OVERVIEW.md`](docs/SERVICE-OVERVIEW.md) | サービス概要（機能・技術の網羅版。実装状況の記号つき） |
| [`docs/MIRUQA-FULL-SPEC.md`](docs/MIRUQA-FULL-SPEC.md) | 機能・サービスロジックの完全版仕様（実装状況つき） |
| [`docs/SECURITY-PAPER.html`](docs/SECURITY-PAPER.html) | セキュリティの設計・脅威モデル・実測結果・既知の限界 |
| [`docs/TECHNICAL-GUIDE.html`](docs/TECHNICAL-GUIDE.html) | 内部の技術解説（アーキテクチャ・シーケンス・ER・コストの流れ） |
| [`docs/design-decisions.md`](docs/design-decisions.md) | OrcaRouterの設計判断ログ（観察→判断→固定。実測値つき。OrcaRouter賞の根拠） |
| [`docs/pricing.md`](docs/pricing.md) | 料金設計の根拠（実測ベース。クレジット制導入前の試算のため、現行の料金案とは一部ずれる） |
| [`docs/roadmap.md`](docs/roadmap.md) | 今回作らなかったもの（次の段階で必要になる項目） |
| [`docs/contracts.md`](docs/contracts.md) | データ契約（ワーカーAPI、`Action`／`PolicyVerdict`／`LlmCall`／`Finding`／`Plan`／`Run`） |
| [`docs/qiita-article.md`](docs/qiita-article.md) | 解説記事（提出用） |

| パス | 内容 |
|---|---|
| `demo-site/` | 点検対象1（架空ECサイト、ポート8765。仕込み不具合14件） |
| `demo-site2/` | 点検対象2（社内備品貸出予約システム、ポート8766。ログインあり、仕込み不具合10件） |
| `web/` | Java層（Spring Boot） |
| `landing-app/` | 製品紹介LP（別アプリ。ピッチ用途、点検対象ではない） |



## 安全上の約束
- 診断対象は**自作のデモサイト（`demo-site/`・`demo-site2/`）と、利用者が所有確認・宣言した自分のサイトだけ**。他社サイト（審査員・協賛企業を含む）には実行しない。
- 能動的なテスト（連打・手順スキップ・価格改ざん等）は、テスト環境の宣言と、テスト項目書の承認が揃わないと実行できない。
- 秘密情報（`.env`、暗号化鍵、パスワード）はコミットしない。テスト用アカウントのパスワードは暗号化して保管し、実行時だけ復号して使う（保存はしない）。
- 個人情報はLLMに送る前にマスクする。
- チャージのカード情報（番号・期限・名義）は保存もログ出力もしない。決済はデモ実装（`FakePaymentGateway`）で、実際の課金は発生しない。

## 既知の限界（正直な一覧。詳細は `docs/SECURITY-PAPER.html` 第8章）
- 氏名のマスクは正規表現ベースで、姓と名の間にスペースがないと検出できない（実測）。
- OrcaRouter Guardrails側の電話番号マスクは、ハイフンなし11桁・国際表記（`+81…`）を通す（アプリ側のマスクは効くため二重防御ではある）。
- 緊急停止は協調的で、最大30秒以上かかる場合がある。
- コストのルーター比較は n=1〜2 と小さく、参考値。
