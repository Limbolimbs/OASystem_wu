param([string]$Repository = 'D:\eclipse-workspace\OASystem',
      [string]$BackupDirectory = ('D:\oa-maintenance\security-schema-' + (Get-Date -Format 'yyyyMMdd_HHmmss')))
$ErrorActionPreference = 'Stop'
if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) {
    throw 'アプリを停止してから09を適用してください。'
}
$migrationClasspath = "$Repository\target\classes;D:\oa-maintenance\build-cache\repository\com\mysql\mysql-connector-j\9.7.0\mysql-connector-j-9.7.0.jar"
& 'C:\Program Files\BellSoft\LibericaJDK-25-Full\bin\java.exe' --class-path $migrationClasspath com.example.demo.maintenance.SecuritySchemaMigration "$Repository\src\main\resources\application.properties" "$Repository\src\main\resources\database\09_session_login_audit.sql" $BackupDirectory
if ($LASTEXITCODE -ne 0) { throw '09の適用が失敗しました。既存DDLバックアップを確認してください。' }
Write-Output "DDLバックアップと移行確認: $BackupDirectory"
