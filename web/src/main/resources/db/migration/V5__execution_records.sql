-- v0.6 P3(前半): Run/Planをプロジェクト・組織に紐付ける。実データ(Plan/Run本体)は引き続き
-- ワーカー側のrunsディレクトリのJSONが正だが、テナント分離(組織で絞り込み・別組織のIDで404)は
-- Java層のこのメタデータテーブルで担保する。

CREATE TABLE plan_records (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  plan_id VARCHAR(64) NOT NULL,
  project_id BIGINT NOT NULL,
  organization_id BIGINT NOT NULL,
  created_by BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT uq_plan_records_plan_id UNIQUE (plan_id),
  CONSTRAINT fk_plan_records_project FOREIGN KEY (project_id) REFERENCES projects(id)
);

CREATE TABLE run_records (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id VARCHAR(64) NOT NULL,
  plan_id VARCHAR(64) NOT NULL,
  project_id BIGINT NOT NULL,
  organization_id BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT uq_run_records_run_id UNIQUE (run_id),
  CONSTRAINT fk_run_records_project FOREIGN KEY (project_id) REFERENCES projects(id)
);
