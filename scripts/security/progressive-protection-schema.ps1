param([string]$Repository='D:\eclipse-workspace\OASystem',
      [string]$ClassesDirectory='D:\oa-maintenance\progressive-protection-20261015\build\oa\classes',
      [string]$BackupDirectory=('D:\oa-maintenance\progressive-schema-'+(Get-Date -Format 'yyyyMMdd_HHmmss')))
$ErrorActionPreference='Stop'
$migrationClasspath="$ClassesDirectory;D:\oa-maintenance\build-cache\repository\com\mysql\mysql-connector-j\9.7.0\mysql-connector-j-9.7.0.jar"
& 'C:\Program Files\BellSoft\LibericaJDK-25-Full\bin\java.exe' --class-path $migrationClasspath com.example.demo.maintenance.ProgressiveProtectionMigration "$Repository\src\main\resources\application.properties" "$Repository\src\main\resources\database\13_progressive_protection.sql" $BackupDirectory
if($LASTEXITCODE -ne 0){throw '13の適用が失敗しました。適用前DDLと記録を確認してください。'}
Write-Output "追加DDLの記録: $BackupDirectory"
