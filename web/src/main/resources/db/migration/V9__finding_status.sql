-- v0.7 P6中核: 不具合の状態管理(未対応/対応済み/対応しない)と、再実行時の抑制。
-- Findingそのものは引き続きワーカー側run.jsonが正(このDBにはコピーしない)。Finding自体のIDは
-- 実行のたびに振り直されるため、fingerprint(観点・種別・仕様参照+正規化した題名。仕様参照が
-- 無ければ観点・種別・detail。agent/loop.pyの重複統合キーと同じ考え方)ごとにプロジェクト内で
-- 状態を持つ。同じ不具合が再実行で再検出されても、fingerprintが一致すれば以前つけた状態が
-- 引き継がれる(抑制: findings一覧は既定で未対応のみを表示する)。
CREATE TABLE finding_status (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  organization_id BIGINT NOT NULL,
  project_id BIGINT NOT NULL,
  fingerprint VARCHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'UNADDRESSED',
  updated_by_user_id BIGINT,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT uq_finding_status UNIQUE (organization_id, project_id, fingerprint),
  CONSTRAINT fk_finding_status_project FOREIGN KEY (project_id) REFERENCES projects(id)
);
