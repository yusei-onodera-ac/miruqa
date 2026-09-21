-- v0.7(第1b節): プロジェクトの種類。作成後は変更しない(Java側でも更新エンドポイントを用意しない)。
-- DEV_ENV = 開発環境の点検(フル。ローカルまたは所有確認済みの公開ステージング)。
-- PUBLIC_READONLY = 公開ページの点検(読み取り専用)。
-- 既存行(v0.6までに作られたプロジェクト)は、すべて開発環境の点検として扱う(DEV_ENVが既定)。
ALTER TABLE projects ADD COLUMN kind VARCHAR(32) NOT NULL DEFAULT 'DEV_ENV';
