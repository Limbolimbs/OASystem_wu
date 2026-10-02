# 勤怠管理のデータベース適用手順

このフォルダーのSQLは `oa_system` データベース向けです。既存データを削除しません。

1. Aivenのデータベースをバックアップし、接続先が `oa_system` であることを確認します。
2. 未適用の場合のみ、`01_employee_soft_delete.sql`、`02_attendance.sql` の順に実行します。
3. `03_attendance_management.sql` を実行します。勤務ルールの初期値は平日09:00～18:00、休憩60分です。管理画面から変更でき、変更は翌日から適用されます。導入前の過去のルールは不明なため、導入時のルールを履歴の初期値にします。
4. `SHOW TABLES FROM oa_system;` で `attendance_policy`、`attendance_policy_history`、`company_holiday`、`work_schedule`、`attendance_request`、`attendance_break`、`attendance_event`、`attendance_audit_log` を確認します。
5. 管理者でログインし、従業員一覧から総務担当者の権限を `GENERAL_AFFAIRS` に変更します。部署名だけでは承認権限を与えません。
6. アプリの `spring.jpa.hibernate.ddl-auto=validate` を維持して起動します。SQLを実行する前は不足テーブルの検証エラーで起動しませんが、DBを自動変更しません。

通常の `mvnw test` は実DBに接続しません。実DBを使う起動確認は、バックアップ・接続先を確認したうえで `OA_INTEGRATION_TEST=true` を指定して実行します。統合テストでは自動スキーマ変更を無効にしています。

権限: `ADMIN` は全勤怠の参照・審査・ルール管理、`GENERAL_AFFAIRS` は全勤怠の参照・審査、`EMPLOYEE` は自分の打刻・申請・履歴参照ができます。自分の申請は承認できません。

確認例: 出勤→休憩開始→休憩終了→退勤、夜勤の翌日退勤、打刻忘れの修正申請、外勤/在宅/残業/休日出勤/休暇の申請と承認・差し戻し、申請取り下げ、無権限での管理URLアクセス。過去の打刻原本は `attendance_event` に残し、修正・審査は監査ログに記録します。

本機能は勤怠管理の試作実装です。給与計算、休暇残日数、法定休日・36協定の判定、承認経路の複数段階化、認証強化（パスワードハッシュ化・CSRF対策）などは別途必要です。実運用・販売前に法務/労務とセキュリティのレビューを行ってください。
