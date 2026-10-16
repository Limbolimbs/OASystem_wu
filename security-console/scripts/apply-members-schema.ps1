param([string]$Repository='D:\eclipse-workspace\OASystem',
      [string]$PrivateDirectory='D:\oa-maintenance\control-private',
      [string]$JavaExecutable=(Get-Command java.exe -ErrorAction Stop).Source,
      [string]$DependencyRepository='D:\oa-maintenance\build-cache\repository')
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Security
$clear=[Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes((Join-Path $PrivateDirectory 'settings.dpapi')),$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
try{
 $settings=[Text.Encoding]::UTF8.GetString($clear)|ConvertFrom-Json
 if(!($settings.PSObject.Properties.Name -contains 'CONTROL_MEMBER_KEY')){throw '先にenable-members.ps1を実行してください。'}
 $backup='D:\oa-maintenance\members-schema-'+(Get-Date -Format 'yyyyMMdd_HHmmss')
 $classpath="$Repository\security-console\target\classes;$DependencyRepository\com\mysql\mysql-connector-j\9.7.0\mysql-connector-j-9.7.0.jar"
 & $JavaExecutable --class-path $classpath com.example.control.MemberSchemaMigration "$Repository\src\main\resources\application.properties" "$Repository\src\main\resources\database\12_console_members.sql" $backup ([string]$settings.CONTROL_DB_USER)
 if($LASTEXITCODE -ne 0){throw '12適用に失敗しました。DDL記録を確認してください。'}
 Write-Output "移行記録: $backup"
}finally{[Array]::Clear($clear,0,$clear.Length);$settings=$null}
