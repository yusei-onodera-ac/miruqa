-- v0.7 P5: ログインが必要な画面の点検のため、対象サイトのテスト用アカウント(氏名は持たない、
-- ユーザー名/メールアドレス・パスワードのみ)を、プロジェクトに1組ずつ保管する。
-- パスワードは平文では保存せず、AES/GCMで暗号化してから保存する(CredentialEncryptionService)。
-- 実行時にだけ復号してワーカーへ渡し(authorization.testAccount)、ワーカー側では永続化しない。
CREATE TABLE test_credentials (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  organization_id BIGINT NOT NULL,
  project_id BIGINT NOT NULL,
  username VARCHAR(255) NOT NULL,
  encrypted_password VARCHAR(1000) NOT NULL,
  iv VARCHAR(64) NOT NULL,
  created_by BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT uq_test_credentials_project UNIQUE (project_id),
  CONSTRAINT fk_test_credentials_project FOREIGN KEY (project_id) REFERENCES projects(id)
);
