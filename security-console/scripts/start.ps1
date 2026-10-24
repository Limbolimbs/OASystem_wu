param([string]$PrivateDirectory='D:\oa-maintenance\control-private',[ValidateRange(1024,65535)][int]$Port=9443,
      [string]$JavaExecutable=(Get-Command java.exe -ErrorAction Stop).Source)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Security
$consoleRoot=Split-Path -Parent $PSScriptRoot
if(!(Test-Path -LiteralPath "$consoleRoot\target\security-console-1.0.0.jar")){throw '配布JARがありません。先に管理コンソールをビルドしてください。'}
if(@(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue).Count){throw "ポート$Port は使用中です。既存プロセスを確認してください。自動停止は行いません。"}
$privateBytes=[Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes((Join-Path $PrivateDirectory 'settings.dpapi')),$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
$consoleNames=@()
try{
 $setup=[Text.Encoding]::UTF8.GetString($privateBytes)|ConvertFrom-Json
 foreach($property in $setup.PSObject.Properties){if($property.Name.StartsWith('CONTROL_')){[Environment]::SetEnvironmentVariable($property.Name,[string]$property.Value,'Process');$consoleNames+=$property.Name}}
 $env:CONTROL_PORT=[string]$Port
 $env:CONTROL_KEYSTORE='file:'+(Join-Path $PrivateDirectory 'local.p12').Replace('\','/')
 & $JavaExecutable -jar "$consoleRoot\target\security-console-1.0.0.jar"
 if($LASTEXITCODE -ne 0){throw '管理コンソールを起動できません。表示されたエラーを確認してください。'}
}finally{foreach($name in $consoleNames){[Environment]::SetEnvironmentVariable($name,$null,'Process')};Remove-Item Env:CONTROL_PORT,Env:CONTROL_KEYSTORE -ErrorAction SilentlyContinue;[Array]::Clear($privateBytes,0,$privateBytes.Length);$setup=$null}
