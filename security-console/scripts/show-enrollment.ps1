param([string]$PrivateDirectory='D:\oa-maintenance\control-private')
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Security
$privateBytes=[Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes((Join-Path $PrivateDirectory 'settings.dpapi')),$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
try{
 $setup=[Text.Encoding]::UTF8.GetString($privateBytes) | ConvertFrom-Json
 Write-Host "管理ID: $($setup.CONTROL_LOGIN_ID)"
 Write-Host "初期パスワード: $($setup.enrollmentPassword)"
 Write-Host "認証器の登録キー: $($setup.CONTROL_TOTP_SECRET)"
 Write-Host '認証器には30秒・6桁・SHA1の時刻ベース方式で登録してください。'
 Write-Host 'この画面を録画・共有せず、パスワード管理ツールへ保管してください。'
}finally{[Array]::Clear($privateBytes,0,$privateBytes.Length);$setup=$null}
