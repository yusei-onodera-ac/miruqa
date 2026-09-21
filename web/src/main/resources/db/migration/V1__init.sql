-- v0.6 P1: 利用者管理の土台(組織・メンバー・プロジェクト・監査ログ)。
-- 第0a章の規模(1組織=1顧客)に合わせ、招待リンク・APIトークンのテーブルは作らない(設計上の割り切り)。

CREATE TABLE organizations (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(200) NOT NULL,
  created_at TIMESTAMP NOT NULL
);

CREATE TABLE users (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  email VARCHAR(320) NOT NULL,
  password_hash VARCHAR(200) NOT NULL,
  display_name VARCHAR(200) NOT NULL,
  email_verified_at TIMESTAMP NULL,
  failed_login_count INT NOT NULL DEFAULT 0,
  locked_until TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT uq_users_email UNIQUE (email)
);

-- ロールはowner/admin/member/viewer(第0a章: 招待リンク・多段の複雑な運用は対象外。
-- ownerが画面からメンバーを直接追加する簡易な方式のみ)
CREATE TABLE memberships (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  organization_id BIGINT NOT NULL,
  role VARCHAR(20) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_memberships_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_memberships_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
  CONSTRAINT uq_memberships_user_org UNIQUE (user_id, organization_id)
);

CREATE TABLE projects (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  organization_id BIGINT NOT NULL,
  name VARCHAR(200) NOT NULL,
  target_url VARCHAR(500) NOT NULL,
  created_by BIGINT NULL,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_projects_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
  CONSTRAINT fk_projects_created_by FOREIGN KEY (created_by) REFERENCES users(id)
);
CREATE INDEX idx_projects_org ON projects(organization_id);

-- 重要操作の簡易な監査ログ(U-7)。organization_idはnull許容(サインアップ等、組織作成前の操作用)
CREATE TABLE audit_logs (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  organization_id BIGINT NULL,
  user_id BIGINT NULL,
  action VARCHAR(100) NOT NULL,
  detail VARCHAR(1000) NULL,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_audit_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
  CONSTRAINT fk_audit_user FOREIGN KEY (user_id) REFERENCES users(id)
);
CREATE INDEX idx_audit_org ON audit_logs(organization_id);
