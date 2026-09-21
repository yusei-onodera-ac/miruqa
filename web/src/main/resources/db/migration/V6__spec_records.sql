-- v0.6 P3(前半・開発者からの指摘): 仕様書(specId)も組織に紐付ける。GET /specs/{specId}.jsonが
-- 組織の確認なしに誰でも任意のspecIdを取得できてしまっていた(IDOR)ことへの是正。

CREATE TABLE spec_records (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  spec_id VARCHAR(64) NOT NULL,
  project_id BIGINT NOT NULL,
  organization_id BIGINT NOT NULL,
  created_by BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT uq_spec_records_spec_id UNIQUE (spec_id),
  CONSTRAINT fk_spec_records_project FOREIGN KEY (project_id) REFERENCES projects(id)
);
