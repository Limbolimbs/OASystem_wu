param([string]$Repository='D:\eclipse-workspace\OASystem',
      [string]$ClassesDirectory='D:\oa-maintenance\session-management-20261015\build\console\classes',
      [string]$PrivateDirectory='D:\oa-maintenance\control-private',
      [string]$BackupDirectory=('D:\oa-maintenance\session-schema-'+(Get-Date -Format 'yyyyMMdd_HHmmss')))
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Security
$privateBytes=[Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes((Join-Path $PrivateDirectory 'settings.dpapi')),$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
try{
 $configuration=[Text.Encoding]::UTF8.GetString($privateBytes)|ConvertFrom-Json
 $classpath="$ClassesDirectory;D:\oa-maintenance\build-cache\repository\com\mysql\mysql-connector-j\9.7.0\mysql-connector-j-9.7.0.jar"
 & 'C:\Program Files\BellSoft\LibericaJDK-25-Full\bin\java.exe' --class-path $classpath com.example.control.SessionSchemaMigration "$Repository\src\main\resources\application.properties" "$Repository\src\main\resources\database\14_session_management.sql" $BackupDirectory ([string]$configuration.CONTROL_DB_USER)
 if($LASTEXITCODE -ne 0){throw '14の移行を確認してください。既存資格情報の再発行は不要です。'}
 Write-Output "14 migration backup: $BackupDirectory"
}finally{[Array]::Clear($privateBytes,0,$privateBytes.Length);$configuration=$null}
