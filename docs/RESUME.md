# 再開用メモ(セッションが長時間化した場合の引き継ぎ用)

最終更新: 2026-09-22(dev/worker、ワーカー担当セッション)。モードC(公開ページ読み取り専用の
ワーカー側強制、`bdc517d`)、自然言語での項目追加(v0.7 3.6a、`ba6a7f4`)は完了・コミット済み。

**P6中核は6項目すべて完了+追加改善(コミット済み)**: 重複統合の強化・価格改ざん判定
(数量改ざんT-05の欠落修正、さらに1TestCase内で価格・数量を両方改ざんする場合の短絡評価
バグも追加修正)・探索の成果(0件で終わる回への歯止め)・状態管理と抑制(Finding未対応/対応済み/
対応しない)・再実行(`POST /runs/{id}/rerun`)・**検出率の3回計測**(実施済み。修正前3回・
修正後2回、demo-site・共有Plan`p-f6458088`で実測。全5回とも安定してL1-L3=6/8、L4=3/6、
誤検知0〜1件)。指揮官指摘(「検出率は実行ではなく項目書の生成で決まっている」)を受けて
**定型のP-SEC項目(rapid_click・navigate_direct・override_param・fill_abnormal×5モード)を
画面種別から必ずコード生成する**`_scripted_test_cases_for_node()`を追加し、新規Plan(30項目)
での再計測で**L1-L3=6/8、L4=4/6(3/6から改善、AC-11の4/6以上を達成)**、誤検知1件を確認
(証拠は`docs/design-decisions.md`判断26)。続けて`recon_site()`にカート→決済確認への
自動遷移を追加し(checkoutノードが下見で発見されないbugを修正)、再計測でL1-L3=7/8・L4=4/6
(内訳改善、仕込み#9(二重注文)を新規検出)を確認(判断27)。仕込み#10(手順スキップ)は
navigate_directの手順文を修正済みだが未検証(次回の計測で確認)。途中でピア経由のセキュリティ
指摘(`agent/planning.py`のマスク漏れ)・ベンチ自体のauthorization未設定バグ・判定の短絡評価
バグも発見・修正済み。テスト: Python 141件成功、`mvn test` 97件成功。詳細は
`docs/STATUS-web.md`「P6中核」節、`docs/design-decisions.md`判断23〜27。

**P5(秘密情報の暗号化保管・ログインが必要な画面の点検)も完了(コミット直前)**。
デモサイト2(`demo-site2/`、社内備品貸出予約システム、ポート8766)は実装済みだったため、
ワーカーのログイン自動化(`agent/browser.py`の`attempt_login()`・`login_if_needed()`、
`recon_site()`・`execute_plan()`への組み込み)と、Java側の暗号化保管
(`CredentialEncryptionService`、AES/GCM、`CREDENTIAL_ENCRYPTION_KEY`環境変数、fail-closed)を
実装。テスト用アカウントは`authorization`とは別の最上位フィールド`testAccount`として
実行のたびに渡し、Plan/Runのどちらにも永続化しない設計(**実装中に1件、authorizationへ
混入させてplan.jsonに漏れる不具合を作ったが、ライブ確認で発見・修正・回帰テスト追加済み**)。
ライブ確認(ワーカー直接・Java層経由の両方)で、ログイン後の画面(`/items`・`/reservations`)を
含むPlan生成と、1項目の実行(`pass`)を確認。テスト: Python 152件成功(+11)、
`mvn test` 110件成功(+13)。詳細は`docs/STATUS-web.md`「P5」節、`docs/design-decisions.md`判断28。

ガードレール(合成PII/秘密鍵マスク)の動作確認(a)(b)(c)と、記事用の計測(M5a-d/M3/M4a-c/M2)は、
指揮官指示により**後回し**(まとめて短時間で実施予定)。

指揮官から作業順の固定指示(2026-09-22、ピッチまで約28時間時点で更新): (完了)モードC →
(完了)4) 自然言語でのテスト項目追加 → (完了)5) P6中核 → (完了)6) P5 →
(完了)7) 1コマンド起動(`scripts/start-local.sh`は`dev/aux`ブランチから`merge dev/aux`で
取り込み済み)とREADME → (完了)8) P8(旧UI部品`.card`/`.badge`/`.chip`の削除、MiruQA名称・
v0.7のスクリーンショット撮り直し) → (完了)9) P4縮小(プラン名・上限の表示)/P7縮小
(プロジェクト単位の削除、バックアップ・復元の手順。コミット`6b40db1`) →
**(次)10) ガードレール確認(a)(b)(c)と記事用計測(M5a-d/M3/M4a-c/M2、まとめて短時間で)**。
P-A11Y/P-UXのコード判定強化は保留(着手しない)。詳細は下部「追記(2026-09-22、
ピッチまで約28時間時点)」節を参照。

このセッションは11時間以上動いており、コンテキストの自動圧縮や再起動が起きても続きから
再開できるよう、このファイルを都度更新する(区切りごとに)。**まずこのファイルを読んでから作業を再開すること。**

## 1. 現在のフェーズと進捗

v0.5(仕様書駆動・SaaS風UI)は完了。現在は **v0.6(`/Users/onoderayusei/AI_HACK_2026/docs/CHANGE-v0.6.md`、
販売できるサービスとしての土台)** に着手中。作業順は第5章のP1〜P8、ただし指揮官(企画セッション)の
指示により以下の順に変更済み:

```
P1 → P2 → 【UIチェックポイント】 → P3 → P6の中核 → P4 → P5 → P7 → P8の残り
```

### 完了した作業(v0.6)
- **P1(利用者管理・DB・テナント分離・ワーカーAPI認証)は完了**。
  - DB: H2ファイルモード + Flyway。`web/src/main/resources/db/migration/V1__init.sql`に
    `organizations`/`users`/`memberships`/`projects`/`audit_logs`。
  - 認証: `web/src/main/java/ai/hack2026/web/auth/`一式(`User`/`Organization`/`Membership`/`Role`/
    `SecurityConfig`/`AppUserDetailsService`/`AppUserPrincipal`/`AuthEventListener`(ログイン試行制限)/
    `MockMailService`(模擬メール)/`SignupService`/`AuthController`)。
  - プロジェクト管理: `web/src/main/java/ai/hack2026/web/project/`一式(`Project`/`ProjectRepository`/
    `ProjectController`)。テナント分離は`findByIdAndOrganizationId`のように、idだけで取得する
    メソッドを用意しない設計で担保。
  - 監査ログ: `web/src/main/java/ai/hack2026/web/audit/`一式。
  - ワーカーAPI認証: `agent/config.py`の`WORKER_SHARED_SECRET`、`agent/server.py`の
    `_check_auth()`、Java側`WorkerClient`の`addAuthHeader()`。
  - 自動テスト: `web/src/test/java/ai/hack2026/web/project/TenantIsolationTest.java`(AC-U1、成功)。
  - デザイン: `web/src/main/resources/static/style.css`に第3a章のトークンを`.v6-*`クラスとして追加
    (既存の`.v5`系クラスと共存中。旧画面の全面移行はUIチェックポイントで行う)。
  - 新画面: `templates/auth/{login,signup}.html`、`templates/project/{list,new,detail}.html`。
  - スクショ: `docs/screenshots/v0.6/p1-*.png`(ログイン・サインアップ・ログイン失敗・
    プロジェクト一覧(空/1件)・プロジェクト作成・詳細)。
  - 踏んだ不具合(詳細は`docs/STATUS-web.md`「v0.6」節): Flyway自動設定の分離(`spring-boot-flyway`が
    別途必要)、`th:field`がSpring 7と非互換、フォームクラスのbareフィールドがバインドされない、
    `DaoAuthenticationProvider`を明示登録しないとログインできない、CSS詳細度の事故。
  - **既存の点検フロー(JobController/PlanController/RunController)は、まだProject/組織に
    紐付けていない**(ログイン必須にはなったが、Plan/Run自体は引き続き`runs/`直下のJSON)。
    `/runs` `/plans` `/history` は認証必須にはなったが、**組織で絞り込んでいない**
    (同じログイン済みユーザーなら他組織のRun/Planも見える状態)。この修正はP3
    (ジョブキュー・実行のプロジェクト紐付け)で行う。同じ種類のテナント分離テスト
    (`TenantIsolationTest`と同様、別組織のIDで404になることの確認)をP3でもこの3画面に追加すること。

- **P1補修(指揮官コードレビュー対応)は完了**(コミット待ち、または直近でコミット済み。
  `docs/STATUS-web.md`「v0.6 P1補修」節参照):
  1. 模擬メール認証・パスワード再設定: `AuthToken`/`TokenService`(SHA-256ハッシュ保存・
     ワンタイム・期限つき)、`AuthController`に`/verify-email` `/forgot-password` `/reset-password`
     を追加。メールアドレスの存在有無を外部から推測されないよう、`forgot-password`は常に同じ文言。
  2. パスワード強度: `PasswordPolicy`(8文字以上・よくある弱いパスワードの拒否)。
  3. ワーカーAPI認証をフェイルクローズドに変更: `WORKER_SHARED_SECRET`が空だと`WORKER_AUTH_DISABLED=1`
     が無い限り起動を拒否する(`agent/server.py`の`main()`)。比較は`hmac.compare_digest`
     (定数時間)。`agent/tests/test_worker_auth.py`に「認証なしで401」の自動テストあり(7件・成功)。
  4. 監査ログ: サインアップ・ログイン・プロジェクト作成は記録済み。**プロジェクトの更新・削除は
     機能自体がまだ存在しない**(P1の`ProjectController`はcreate/detail/listのみ)ため、監査ログの
     追加対象がない。将来これらの機能を追加する際は、`project_created`と同様に
     `AuditService.record()`を必ず呼ぶこと(未実装のまま放置しない)。
  5. UI: 全新規画面に共通ヘッダー(`fragments/shell.html`の`header`フラグメント、
     `GlobalModelAttributes`でログイン中メールアドレスを全画面に注入)を追加。
     `project/detail.html`から「P2で実装予定」等の内部事情の文言を削除し、中立的な文言に置換。
     実行履歴・仕様書・診断開始ボタンのプレースホルダーをプロジェクト詳細に追加
     (診断開始ボタンは`/`(旧フォーム)へ対象URLを引き継いで遷移。本格的な連携はP3)。
  - `mvn test`: 4件成功(`SiteInspectorWebApplicationTests`/`AuthDebugTest`/`TenantIsolationTest`×2)。
  - Pythonワーカー側`agent/tests/`: 7件成功。

### 未着手(次にやること、下記2章に詳細)
- P2: ドメイン所有確認、同意記録、P-SEC許可条件、拒否リスト・SSRF対策、実行環境の分離、緊急停止。
- UIチェックポイント(P2完了後): `docs/design-notes.md`作成 → 主要画面を新デザインで作成 →
  実データでスクショ → AIっぽさ自己チェック。指揮官のレビューは非同期(進行は止めない)。
- P3〜P8: `docs/QUESTIONS.md` #22のフェーズ表を参照。

## 2. 次にやること(具体的な順番)

1. **P2は完了(2026-09-21)**。受入基準の確認結果は`docs/STATUS-web.md`「P2の受入基準の確認」節
   (AC-U2/L1/L2/L3/S2すべて合格。留保事項1件を記録)。以下は各項目の実装経緯:
   - **拒否リスト・SSRF対策**: `agent/security.py`(P1補修時点で作成済みだったが未配線)を
     `agent/browser.py`の`route_handler`に配線済み(コミット`f36353c`)。テスト22件
     (`agent/tests/`一式)。
   - **ドメイン所有確認(L-1)・テスト環境宣言(L-2)**: Java層に`domains`テーブル(`V3__domains.sql`)、
     `Domain`/`DomainRepository`/`DomainVerificationService`/`DomainSafetyChecker`一式を実装。
     `/.well-known/<サービス名>-verification.txt`方式で実際にHTTP取得して照合(模擬をやめた)。
     `demo-site/server.py`に`/.well-known/*`の実ルートを追加し、curlで「未開始→開始→確認済み→
     テスト環境宣言済み」まで実データで動作確認済み。テスト10件
     (`DomainSafetyCheckerTest`6件、`DomainVerificationServiceTest`4件)。
     `mvn test`: 23件成功(コミット予定、このRESUME更新と同じ区切り)。
   - **拒否リスト(L-4、指揮官指摘)**: ホスト名そのものの拒否リスト(`.go.jp`/`.lg.jp`サフィックス、
     `cyberace.co.jp`/`orcarouter.ai`)を、ワーカー(`agent/config.py`)・Java
     (`HostnameDenylist`)の両方に追加。コミット`2d7fcee`。テスト計29件(Java)・17件(Python)。
   - **同意の記録(L-3)は完了**: `consents`テーブル(`V4__consents.sql`)、`Consent`/
     `ConsentRepository`/`ConsentService`。サインアップ時の利用規約同意(必須チェックボックス)と、
     実行ごとのドメイン名再入力+テスト権限チェックを実装。テスト7件
     (`ConsentServiceTest`4件、`JobControllerConsentTest`3件、後者は「同意なしでは
     ワーカーへ一切連絡しないこと」をMockitoで直接検証)。`mvn test`: 36件成功。
   - **CSRF不備の修正(AC-U2、指揮官指摘)は完了**: `index.html`の点検フォーム(コミット`a3597bc`)に
     加え、`plan.html`(項目書一括承認フォーム・「続きを生成する」フォーム)・`run.html`(承認・
     却下ボタンのfetch)にも同種のCSRF不備が見つかった(コミット`d2d5435`)。実質、実行の承認・
     再開という中核操作がすべてログイン済みユーザーには403で失敗する状態だった。
     `<meta name="_csrf">`/`<meta name="_csrf_header">`パターンで修正。
     `CsrfProtectionTest`(10件、状態変更系の全POSTエンドポイントを網羅)を追加し、実ブラウザ
     (Playwright)でも承認ボタンの実クリックで403にならないことを確認済み。`mvn test`: 46件成功。
     **教訓**: 旧画面(v0.5)はJSでフォーム・fetchを動的に組み立てている箇所が多く、
     Thymeleafの`th:action`自動CSRF注入に頼れない。新しい画面・操作を追加するたびに、
     `CsrfProtectionTest`に対象エンドポイントを追加すること。
   - **P-SEC許可条件(L-2)の連携は完了**: Java層(`JobController`)がRun開始時に、
     `Domain`(所有確認・テスト環境宣言)と直前に記録した`Consent`のIDから`authorization`
     (`{host, verified, testEnvDeclared, consentId, grantedAt}`)を組み立て、
     `WorkerClient.createPlan(url, specIds, authorization)`でワーカーの`POST /api/plans`に渡す。
     ワーカー(`_start_plan_build`)は`authorization.host`が対象URLのホストと食い違う場合だけ
     拒否(400 `authorization_mismatch`)。authorizationはPlanに保存され、Run実行時に
     `agent/loop.py`が`BrowserSession(authorization=...)`として引き継ぎ、`agent/policy.py`の
     P-SECゲート(危険度`needs_approval`)は`TEST_MODE=1`・`testEnvDeclared=true`・
     `testCaseApproved`の3条件すべてが揃わないと`deny`にする(以前は2条件)。
     テスト: `agent/tests/test_policy_authorization.py`(7件)。実データ確認:
     ワーカーへ直接curlでauthorizationを送り、host一致時の保存・不一致時の400拒否を確認済み
     (実測コスト$0.1218、7回のLLM呼び出し)。
   - **L-2のE2E確認を追加(指揮官指摘)**: 単体テストとPlan作成の実データ確認だけでは
     AC-L2の確認として不十分との指摘を受け、`agent/tests/test_l2_authorization_e2e.py`(4件)を
     追加。`MACRO_CODE_FASTPATH=1`でrapid_clickをLLM呼び出し0件で実行できることを使い、実際の
     Playwright+demo-siteサブプロセスに対して、testEnvDeclared=false/authorization無し/
     TEST_MODE=0の3パターンで拒否・注文が増えないこと、testEnvDeclared=trueかつTEST_MODE=1で
     実際に注文が増えることを確認。LLMコストは発生していない。
   - **緊急停止は完了**: `agent/loop.py`に`request_cancel`/`is_cancel_requested`(協調的
     キャンセル。TestCaseループ・探索ループの各反復先頭で検出)、`agent/server.py`に
     `POST /api/runs/{id}/cancel`、ワーカー起動時の`_cleanup_orphaned_state()`(`recon`のまま
     残ったPlanを`failed`に、`running`等のまま残ったRunを`interrupted`に整理。P1補修で発生した
     孤立事象の再発防止)。Java側`RunController`に`POST /runs/{id}/stop`(監査ログ)、
     `run.html`に「実行を停止」ボタン。テスト: `agent/tests/test_emergency_stop.py`(11件、
     Playwright+demo-site+MACRO_CODE_FASTPATHでexecute_plan()の途中打ち切りを実際に確認する
     E2Eテストを含む。LLM呼び出し0件)、`RunControllerStopTest`(1件)、
     `CsrfProtectionTest`に`/runs/{id}/stop`を追加。
   - **AC-L1の不足を発見・是正**: L-2実装時は一般の点検を所有確認の有無と無関係に進めていたが、
     CHANGE-v0.6.mdのAC-L1(「所有確認が済んでいないドメインでは、診断を開始できない」)を
     満たしていなかった。`JobController.create()`に、`Domain.verified`でなければ403で
     診断開始自体を拒否する処理を追加。
   - **副産物のバグ(重大)**: 是正の確認中に、`JobController.hostnameOf()`(host名のみ)と
     `ProjectController.hostnameOf()`(host:port)の規約の食い違いにより、**非標準ポートの
     ドメイン(デモサイトの8765等)では、所有確認を完了していても常に「未確認」と判定される**
     不具合を発見。`JobController`側を`ProjectController`と同じ規約に揃えて解消
     (`index.html`のドメイン再入力欄の説明もポート込み表記に更新)。
   - 実データ確認: 新規ユーザーで「未確認→403拒否→所有確認完了→成功」の一連の流れをcurlで確認済み
     (実測コスト$0.0159)。`mvn test`: 49件成功。`agent/tests/`: 50件成功。
2. **UIチェックポイント + P3前半(実行・プランのプロジェクト紐付け)に着手中(2026-09-21)**。
   指揮官の指示で順序を一部変更: ダッシュボード・プロジェクト詳細・不具合一覧は、実行が
   プロジェクトに紐づいていないと実データで作れないため、P3前半を先に済ませる。
   - **P3前半は完了**: `plan_records`/`run_records`テーブル(`V5__execution_records.sql`)で
     Run/PlanをProject/組織に紐付け。`JobController`は`projectId`必須(URLを直接打つ入口は廃止、
     プロジェクト詳細の「診断を開始」ボタン経由に一本化)。`PlanController`/`RunController`/
     `ApprovalController`/`HistoryController`にテナント分離を追加(他組織のplanId/runIdは404、
     `ExecutionTenantIsolationTest`3件で確認)。`DemoDataSeeder`
     (`SEED_DEMO_DATA=true`、既定OFF)で`runs/samples/`をデモ組織へ登録し、実データで
     画面確認・スクショができるようにした(`run-0b84031e60`=22件の指摘・実測$0.6072の本物の
     実行を含む)。実データで、ログイン→履歴→実行詳細(22件確認)→プロジェクト詳細の一連の
     流れと、別組織からは404になることを確認済み。`mvn test`: 52件成功。
   - **IDOR是正(指揮官指摘、P3前半のレビューで発見)**: `GET /specs/{specId}.json`が組織の
     確認なしに任意のspecId(仕様書、顧客の機密になりうるデータ)を返せてしまっていた。
     `spec_records`テーブルを追加して是正(`ExecutionTenantIsolationTest.otherOrgSpecIdIsNotAccessible`
     で確認)。この指摘を受けて、Java層の全7コントローラー・全エンドポイントの組織スコープを
     棚卸しし、`docs/STATUS-web.md`に表で記録した(他に穴は無いことを確認済み)。`mvn test`: 53件成功。
   - **主要6画面のUIチェックポイントは完了(2026-09-21)**: ①ログイン(変更なし)②オンボーディング
     (`project/onboarding.html`新規。プロジェクト0件の組織に自動表示)③ダッシュボード
     (`DashboardController`/`dashboard.html`新規。コストのカードは意図的に置かない=集計を
     持っていないため)④プロジェクト詳細(`project/detail.html`を4タブに全面書き換え:
     概要/実行履歴/仕様書/設定・ドメイン確認)⑤不具合一覧(`FindingsController`/`findings.html`
     新規。直近30実行を横断。状態列は「未対応」固定でQ-8の実更新はP6で実装予定)⑥履歴(変更なし)。
     `mvn test`: 54件成功。実データ(`SEED_DEMO_DATA=true`+実ワーカー)で6画面すべてHTTP 200・
     実データ表示を確認し、`docs/screenshots/v0.6/`にスクショ保存済み。「やらないこと」自己チェック
     結果は`docs/STATUS-web.md`に記録(該当なし。列幅の軽微な指摘1件のみ)。コスト実行LLM呼び出し
     0件(合計$2.5693のまま変化なし、テンプレート・コントローラーのみの変更のため)。
   - **次**: 新規点検の流れ(`index.html`→`plan.html`→`run.html`)のv6化(`run.html`は
     6画面チェックポイントでは意図的に手を付けていない。浅い着替えの後で全面書き換えると
     二度手間になるため)。
   - **指揮官指示(2026-09-21、順序の修正)**: 続けて新規点検の流れ
     (`index.html`→`plan.html`→`run.html`)もv6化する(P3の残りに入る前に。後回しにしない)。
     `plan.html`のテスト項目書は「Excelの項目書のような表」(項目ID／観点／前提条件／手順／
     期待結果／結果／エビデンス)にする。`history.html`もv6化。v0.5系`.card`/`.badge`/`.chip`は
     P8までに0にする。
   - **指揮官指示**: ランディングページ(軽いもの、未ログイン時の`/`の入口)はP8で作る
     (「やらない」ではなく「後回し」。`docs/design-notes.md`参照)。
   - **指揮官指示**: Q-8(不具合の状態変更・誤検知の抑制)は、AC-Q4(P6の受入基準)のため、
     表の列だけでなく実際の更新機能をP6で必ず実装する(見送らない)。
   - **指揮官のデザインレビュー是正8点は完了(2026-09-21)**: JST変換、日時の新しい順ソート、
     列のnowrap化、コスト・所要時間列の追加、行への実行詳細リンク、開発者向け自己言及の削除、
     Q-8未実装の「状態」列の非表示化、観点空欄の「—」表示、デモプロジェクト名の是正。
     `DisplayFormat`ユーティリティ新設。`mvn test`: 54件成功(この時点でsurefireの合計を
     取り違えて「39件」と誤報告 → 指揮官指摘で発覚 → `docs/STATUS-web.md`/本ファイルとも訂正済み。
     **教訓**: 今後は必ず`target/surefire-reports`の合計を集計してから報告する)。
   - **新規点検の流れのv6化は完了(2026-09-21)**: `index.html`/`plan.html`/`run.html`/
     `history.html`/`error.html`をすべてshell化・`.v6-*`クラスへ移行。`plan.html`のテスト項目書は
     指示どおりExcel形式の表(項目ID/観点/前提条件/手順/期待結果/結果/エビデンス。列の表示切替・
     ソート機能つき)に。新設`.v6-steps`(ウィザードの段階表示。色つきピルではなく罫線下線)。
     `run.html`(456行)はJSのロジックは変えず出力HTMLをv6クラスに置換。副次的に、実行中/待機中の
     状態バッジが英語("running"等)のままだった小さな不備も日本語化。`mvn test`: 54件成功。
     **実データでの一気通貫確認(実LLM使用)**: Playwrightでログイン→プロジェクト詳細→新規診断→
     プラン生成(32件のExcel形式項目書、列切替・ソートを実機操作)→通常危険度項目の承認→実行画面
     (進捗・KPI・不具合票・コストタブ)まで最後まで通した。送料不一致の横断指摘も実データで確認。
     コスト実測: 実行前$2.5693(449件)→実行後$2.7003(461件)、差分+$0.1310・12回のLLM呼び出し
     (日次上限$13.0の約20.8%)。スクショは`docs/screenshots/v0.6/6〜9c-*.png`。
   - **次**: P3の残り(ジョブキュー・DB化、同時実行数の制限、組織単位のコスト上限、利用量記録)。
3. その後、P3(ジョブキュー)→P6中核(品質)→P4(課金)→P5(セキュリティ)→P7(削除・バックアップ)→
   P8残り、の順(指揮官指示、`docs/QUESTIONS.md`にも記録済み)。
   - **P3の「ワーカー再起動時の孤立プラン整理」は緊急停止の実装で先に完了済み**:
     `agent/server.py`の`_cleanup_orphaned_state()`(P1補修中に発生した`p-3813959a`・
     `p-5a62f06b`の`recon`孤立と同種の問題への対応。`agent/tests/test_emergency_stop.py`の
     `OrphanedStateCleanupTest`で検証済み)。P3では、ジョブキュー(DB)への移行に合わせて、
     この整理ロジックをDBの状態に対しても行うよう作り直すこと(現状はrunsディレクトリの
     JSONファイルを直接書き換える実装)。
4. **フェーズの区切りごとに**: 動作確認 → `dev/worker`にコミット → `docs/STATUS.md`
   (Java層は`docs/STATUS-web.md`)を更新 → **このRESUME.mdも更新**。

## 3. 守るべき決定事項

- **CHANGE-v0.5.md 第9章(変更しないもの)は引き続き有効**: LLM呼び出しは必ずOrcaRouter経由、
  モデルIDをコードに固定しない、ストリーミング不使用、許可ドメインのみ、P-SECは事前承認必須、
  ペルソナ・ゴールは完全廃止(UI/API/契約/CLI/プロンプトすべて)。
- **CHANGE-v0.6.md 第0a章(規模)が最優先**: 「1組織(1顧客)が安全に繰り返し使える」規模。
  招待リンク・多段ロール・APIトークン・決済アダプタ・負荷試験・英語UI・ダークモードは作らない
  (`docs/roadmap.md`に将来の拡張として書くだけ。**まだroadmap.md未作成、要対応**)。
- **CHANGE-v0.6.md 第3a章(デザイン方針)が最優先**: 「人間のデザイナーが作ったように見える」こと。
  グラデーション・ガラス風・絵文字・中央揃えの定型ヒーロー・宣伝文句・色つきピル・ダミー数字は禁止。
  罫線と表中心、角丸2〜4px、色は意味づけのみ(`style.css`の`.v6-*`トークン参照)、状態は
  「■ 不合格」のような四角い印+文字、実データでスクショを撮って自己チェックする。
  T-DASH等は「構造と用語」だけ参考にし、見た目は模倣しない(用語: プロジェクト/テストスイート/
  テストケース/画面定義/期待結果/実行結果/エビデンス/不具合票/再テスト)。
- **承認範囲(代理承認の範囲、変わっていない)**: ローカル作業・コミット・依存追加・テスト・ローカルの
  デモ実行まで。**git push・削除・秘密情報の表示/コミット・許可ドメイン外・OrcaRouter設定変更は不可**。
- **予算・コストの運用**:
  - `.env`の`LLM_BUDGET_PER_DAY_USD=13.0`は**意図した値**(おのゆー確認済み、`docs/QUESTIONS.md` #21解決)。
    OrcaRouterの残クレジット約$13を日次上限にしている。**この値の80%(約$10.4)に達したら、
    おのゆーに連絡すること**(ライブLLM実行を止める基準)。1実行あたりの上限は$0.5のまま。
  - ワーカー側でこの上限を勝手に書き換えない(おのゆーが管理)。
  - 実行のたびに、`python -m agent.cost_report`の実効コスト合計(実行前後)を報告する運用を継続中。
  - 通常の検証は、安いモデル・小さいTESTCASE_MAX_CASES等で行い、ライブLLM実行は必要最小限にする。
- **虚偽の主張をしない**: 「対応済み」「認証取得済み」「法令適合」等、事実でない主張を画面・資料に
  書かない。規約類の下書きには必ず【要法務確認】を付ける。測っていない数字を出さない。
- 本物の課金・メール送信・外部アカウント作成はしない(模擬のみ)。

## 4. ハマりやすい点(実測ずみ、時間を無駄にしないための申し送り)

- **Spring Boot 4.0は非常に新しく、細部のAPIがドキュメントと食い違うことが多い**。何かがおかしいと
  感じたら、まず「このモジュール分割・パッケージ移動で変わっていないか」を疑う。実例:
  - `spring-boot-starter-web` → `spring-boot-starter-webmvc`
  - JacksonはデフォルトでJackson 3系(`tools.jackson.*`)。Jackson 2を使うところは明示依存が必要
    (`WorkerClient`はSpring `RestClient`を諦め、JDK標準`HttpClient`+Jackson 2で直接実装している)
  - Flywayの自動設定は`spring-boot-flyway`という別モジュールが必要(`flyway-core`だけでは動かない)
  - `AutoConfigureMockMvc`は`org.springframework.boot.webmvc.test.autoconfigure`パッケージに移動
  - `DaoAuthenticationProvider`はコンストラクタで`UserDetailsService`を渡し、
    `setPasswordEncoder()`で暗号化器を設定する(旧来の逆の組み方はコンパイルエラーになる)
  - `th:field`(Thymeleafのフォームバインディングタグ)がこの環境のSpring 7と噛み合わず例外になる。
    **フォームは`th:field`を使わず、`th:value`+素の`name`属性で組む方針にした**(今後も同様に)。
- **フォームクラス(`@ModelAttribute`で受けるクラス)は、必ずgetter/setterを用意すること**。
  bareフィールドだけだとバインディングが静かに失敗し、値が常にnullになる(エラーにもならず
  発見しづらい)。
- **環境変数は、プロセス起動時にしか読まれない**。`.env`やJavaの環境変数を変更したら、
  ワーカー(`agent.server`)・Java層(`spring-boot:run`)の両方を再起動しないと反映されない。
  起動コマンドは5章参照。
- **`runs/ledger.jsonl`の二重計上に注意**: 1回のLLM呼び出しにつき`type:"call"`行と
  `type:"settlement"`行(確定額、別行で追記)の2行が存在しうる。素朴に全行を合計すると二重計上に
  なる。必ず`agent.ledger.merged_calls()`/`aggregate()`(`requestId`単位で合流させる)経由で集計する
  (`python -m agent.cost_report`はこの経由で正しく集計している)。
- **H2ファイルのロック**: アプリが起動中は`web/data/app.mv.db`を外部から直接開けない
  (`AUTO_SERVER=TRUE`だが単純な別プロセスからの接続は失敗した実績あり)。DBの中身を見たい場合は、
  アプリ自体にデバッグ用の一時的なテスト/エンドポイントを足すのが早い。
- **開発中のテンプレート反映**: `spring.thymeleaf.cache=false`を設定済みなので、`.html`テンプレートの
  変更は再起動不要で反映される。ただし**Javaクラスの変更は再起動が必要**(devtoolsは入れていない)。
- **Playwrightでのブラウザ自動化**: `page.fill()`がタイムアウトする場合、たいてい「前のページ遷移が
  失敗している」か「バリデーションエラーで同じページに留まっている」ことが原因。`page.url`と
  レスポンスの実際のHTML(エラーメッセージ)を必ず確認すること。迷ったらcurl+cookie jarで
  段階を分けて確認する方が原因の切り分けが早い。

## 5. 動作確認の手順

### ワーカー(Python)の起動
```bash
cd /Users/onoderayusei/AI_HACK_2026-worker
pkill -f "agent.server"  # 既存プロセスがあれば止める(設定変更を反映するため)
(.venv/bin/python3 -m agent.server > /tmp/agent_server.log 2>&1 &)
curl http://127.0.0.1:8770/api/health -H "X-Worker-Auth: $(grep '^WORKER_SHARED_SECRET=' .env | cut -d= -f2)"
```
デモサイト: `.venv/bin/python3 demo-site/server.py`(既定8765番。`TEST_MODE=1`を付けると
`/__test/state`が有効になる)。

### Java層の起動
```bash
cd /Users/onoderayusei/AI_HACK_2026-worker/web
pkill -f "spring-boot:run"
SECRET=$(grep '^WORKER_SHARED_SECRET=' ../.env | cut -d= -f2)
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home \
  WORKER_SHARED_SECRET="$SECRET" nohup ./mvnw -q spring-boot:run > /tmp/web_app.log 2>&1 &
# 起動待ち: curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8080/login が200になるまで
```
ブラウザで `http://127.0.0.1:8080/signup` からサインアップ → `/login` → `/projects`。

### テストの実行
```bash
cd /Users/onoderayusei/AI_HACK_2026-worker/web
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home ./mvnw -q test
```
`TenantIsolationTest`(AC-U1)を含む全テストが通ること(実行時間は数秒〜十数秒)。

### コスト確認
```bash
cd /Users/onoderayusei/AI_HACK_2026-worker
.venv/bin/python3 -m agent.cost_report   # 実効コスト合計・目的別・モデル別・日別
```

### 開発用DBのリセット(ローカルのみ。テストデータが壊れた場合)
```bash
cd /Users/onoderayusei/AI_HACK_2026-worker/web
rm -f data/app.mv.db data/app.trace.db data/app.lock.db  # 起動中は削除できないので先にkillする
```

### バックアップ・復元の手順(v0.7 P7縮小)
状態は2箇所に分かれている: Java層のメタデータ(利用者・組織・プロジェクト・実行記録・
テスト用アカウント等)はH2ファイル1本(`web/data/app.mv.db`)、ワーカー側の実行結果本体
(Plan/Run・スクリーンショット・費用台帳)は`runs/`配下のファイル一式。どちらもファイルの
コピーだけで完結する(専用のバックアップツールは無い)。

**バックアップ**(両方のプロセスを止めてから。実行中にコピーすると不整合の可能性がある):
```bash
# 1. Web層・ワーカーを止める(scripts/start-local.shならCtrl-C)
# 2. コピーする
cp web/data/app.mv.db backup-app-$(date +%Y%m%d).mv.db
tar czf backup-runs-$(date +%Y%m%d).tar.gz runs/
```

**復元**:
```bash
# 1. Web層・ワーカーを止める
# 2. 戻す(既存ファイルは上書きされるので、必要なら退避してから)
cp backup-app-YYYYMMDD.mv.db web/data/app.mv.db
rm -rf runs/ && tar xzf backup-runs-YYYYMMDD.tar.gz
# 3. 起動し直す
```
Java層のマイグレーション(`web/src/main/resources/db/migration/`)はFlywayが起動時に
自動適用するため、古いバックアップ(マイグレーション未適用)を戻しても、次回起動時に
不足分が自動的に当たる(スキーマを壊す変更は無い前提。破壊的な変更をした場合はこの限りでない)。

**プロジェクト単位の削除**(`/projects/{id}/delete`、確認のためプロジェクト名の入力が必要)は、
Java層のメタデータ(Plan/Run記録・仕様書記録・不具合の状態・テスト用アカウント)だけを削除し、
ワーカー側の`runs/`配下(実行結果本体)は削除しない(誤操作からの復旧・監査のため意図的に残す。
使用量の記録(コストの内部請求根拠)も削除しない)。ワーカー側のファイルも完全に消したい場合は、
`runs/plans/<planId>/`・`runs/<runId>/`を手動で削除する。

## 追記(2026-09-22、ピッチまで約28時間時点)

P5完了後、指揮官指示で作業順を更新: (完了)P6中核 → (完了)P5 →
(完了)7) 1コマンド起動・README(`dev/aux`をmerge、`scripts/start-local.sh`・`landing-app/`を
取り込み。`.env.example`に`ALLOWED_HOSTS`8766・`CREDENTIAL_ENCRYPTION_KEY`を追加、
`scripts/start-local.sh`がJava層にも`.env`を渡すよう修正) →
(完了)8) P8(旧UI部品`.card`/`.badge`/`.chip`等の削除。全17テンプレートで実際に使われている
クラス名を機械的に洗い出し、未使用と確認したものだけ削除。`docs/screenshots/v0.7/`に
ログイン・ダッシュボード・プロジェクト・プラン・実行結果・不具合一覧の6画面を撮り直し) →
(完了)9) P4縮小(ダッシュボードにプラン名・日次月次の使用量表示を追加。強制自体は既存の
`UsageService`のロジックを再利用)/P7縮小(`POST /projects/{id}/delete`。確認名の完全一致・
テナント分離・監査ログ・関連レコードのカスケード削除。バックアップ・復元の手順を本ファイルに
追記。作業中に`.properties`ファイルの日本語リテラルがISO-8859-1解釈で文字化けする不具合を
発見・修正(`@Value`注釈側の既定値のみを使う方式に変更)。コミット`6b40db1`) →
**(次)10) ガードレール確認(a)(b)(c)と記事用計測(M5a-d/M3/M4a-c/M2)**。
テスト: Python 152件成功、`mvn test` 114件成功(110件から4件増)、`landing-app/` mvn test 47件成功。
実効コストは$7.77前後(2026-09-22時点、停止の目安$10.4)。P4/P7縮小自体は追加のLLM呼び出しを
伴わないため、費用は変化していない。

## 追記2(2026-09-22、計測完了・提出用Git整理に着手)

項目10(ガードレール確認a/b/c・記事用計測M5a-d/M3/M4a/M2)が完了(コミット`f9d0509`)。
実効コストは$9.2935で打ち切り(停止目安$10.4の約89%。詳細は`docs/design-decisions.md`判断29)。

指揮官承認により、次は**提出用Git整理**(新規公開リポジトリ`miruqa`への切り出し)に着手する。
手順(ピアからの指示、2026-09-22):
0. (完了)計測結果のコミット。
1. `/Users/onoderayusei/AI_HACK_2026-release/`に、`dev/worker`の最新から成果物だけをコピー
   (開発用文書は`/Users/onoderayusei/AI_HACK_2026-private/`に退避。削除はしない)。
2. 内部参照(「指揮官」「CLAUDE.md」「STATUS.md」等、約80箇所)の除去。Java層の
   `SiteInspectorWebApplication`/`site-inspector-web`を`MiruqaWebApplication`/`miruqa-web`に
   リネーム。
3. 秘密情報の検査(.env・APIキー・パスワード等がツリーに無いことを確認)。
4. releaseツリーで`mvn test`・Python unittest・`scripts/start-local.sh`起動確認。
5. 差分の要約をピアへ報告。**ここで止まる**(公開リポジトリ作成・push・旧リポジトリ非公開化は、
   ピアの合図があるまで実行しない。標準指示: git push・削除・可視性変更は代理承認の範囲外)。

続けて`docs/TECHNICAL-GUIDE.html`(ピア作成)のファクトチェック(手順5の後、30分以内)。

## 追記3(2026-09-22、提出用Git整理: 手順1〜5完了・手順6待ち)

提出用ツリーは`/Users/onoderayusei/miruqa/`(退避先`/Users/onoderayusei/miruqa-private/`)に
名称変更済み。手順1〜5(ファイル整理・内部参照除去・Java層リネーム・秘密情報検査・検証)が
完了し、ピアへ差分報告済み。追加でピアから4点の修正依頼(docs最新版への再同期、
landing-app/scripts/のdev/aux再同期、qiita-article.mdの内部メモ削除、bench結果の
「おのゆー」置換)があり、対応・再検証・再報告済み(Java 114件・landing-app 47件・
Python 152件、全て成功)。`docs/TECHNICAL-GUIDE.html`のファクトチェックも完了し、
誤りは見つからなかった。

**次**: ピアの合図を待って、手順6(GitHub公開リポジトリ`miruqa`の作成・初回push・
旧リポジトリの非公開化)を実行する。この操作(push・可視性変更)は代理承認の範囲外のため、
明確な合図なしには実行しない。

## 追記4(2026-09-22、提出用リポジトリ公開完了)

ユーザー本人の直接確認を得たうえで、手順6(公開リポジトリ作成・push・旧リポジトリ非公開化)を
実行した。**公開リポジトリ**: https://github.com/yusei-onodera-ac/miruqa
(コミット`fc4b559`、タグ`submission-2026-09-23`)。クローンでの検証(ファイル数360件一致、
秘密情報0件、`mvn -q -DskipTests package`がweb/・landing-app/両方で成功)済み。
**旧リポジトリ**(`yusei-onodera-ac/AI_HACK_2026`)は`PRIVATE`化済み(改名・削除はしていない)。

実行前に、`/Users/onoderayusei/miruqa`内に自分の検証作業(mvn test・起動確認)で生成された
`web/target/`・`web/data/app.mv.db`・`web/outbox/`・`runs/plans`等の実行時生成物が残って
いたのを発見し、削除してからコミットした(.gitignoreにより元々追跡対象外だったため実害は
無かったが、念のため)。コミット者情報がローカルgit設定の実名+ホスト名になっている点を、
ピア経由でおのゆーに報告済み(変更要否は未回答)。

これで提出用Git整理(手順0〜6)は完了。次の作業指示待ち。
