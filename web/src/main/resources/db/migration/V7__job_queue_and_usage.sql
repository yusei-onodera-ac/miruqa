-- v0.6 P3: DBのジョブキュー(同時実行は組織ごとに2件まで)と、使用量の記録(usage_events)。
--
-- run_records自体を「ジョブ」として扱う(承認された瞬間はまだワーカーに実行を依頼せず、
-- status='queued'・worker_run_id=NULLのまま作成する。実際にワーカーへ依頼(startRun)するのは、
-- 同時実行の上限に空きができてから)。run_idは、承認直後からURL・履歴で使う安定した識別子
-- (このJava層が発行する)。worker_run_idは、実際にワーカーへ依頼した後にだけ設定される
-- (ワーカー側の本当のrunId。以後のワーカーAPI呼び出しはすべてこちらを使う)。
--
-- 既存行(このマイグレーション以前に作られたRunRecord)は、すべて「承認と同時に即実行」という
-- 旧方式で作られているため、run_id自体が実際のワーカーのrunIdと一致している。
-- そのため、worker_run_id = run_id で埋め、statusは'running'にしておく
-- (実際の状態は、次回アクセス時にワーカーへ問い合わせて確定させる。詳細はJobQueueService参照)。

ALTER TABLE run_records ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'running';
ALTER TABLE run_records ADD COLUMN worker_run_id VARCHAR(64);
ALTER TABLE run_records ADD COLUMN queued_at TIMESTAMP;
ALTER TABLE run_records ADD COLUMN started_at TIMESTAMP;
ALTER TABLE run_records ADD COLUMN finished_at TIMESTAMP;
-- ワーカーがHTMLのエラーページ等、長いエラーメッセージを返すことがあるため、余裕を持たせる
-- (呼び出し側でも念のため切り詰める。JobQueueService参照)。
ALTER TABLE run_records ADD COLUMN error_reason VARCHAR(2000);

UPDATE run_records SET worker_run_id = run_id, queued_at = created_at, started_at = created_at
  WHERE worker_run_id IS NULL;

CREATE INDEX idx_run_records_org_status ON run_records(organization_id, status);

-- B-2: 使用量イベント。実行1件につき1行だけ(run_idにUNIQUE制約)にすることで、
-- 確定額の追記行を別の呼び出しとして数える二重計上を防ぐ(find-or-createで冪等に記録する)。
CREATE TABLE usage_events (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  organization_id BIGINT NOT NULL,
  project_id BIGINT NOT NULL,
  run_id VARCHAR(64) NOT NULL,
  cost_usd DOUBLE NOT NULL,
  call_count INT,
  outcome_status VARCHAR(32) NOT NULL,
  recorded_at TIMESTAMP NOT NULL,
  CONSTRAINT uq_usage_events_run_id UNIQUE (run_id)
);

CREATE INDEX idx_usage_events_org_recorded_at ON usage_events(organization_id, recorded_at);
