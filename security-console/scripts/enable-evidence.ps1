param([string]$PrivateDirectory='D:\oa-maintenance\control-private',
      [string]$PublicKeyFile='D:\oa-maintenance\control-public\evidence-public.pem',
      [string]$JavaExecutable=(Get-Command java.exe -ErrorAction Stop).Source)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Security
foreach($target in @($PrivateDirectory,$PublicKeyFile)){if([IO.Path]::GetPathRoot([IO.Path]::GetFullPath($target)) -ne 'D:\'){throw 'Dドライブの保守パスを指定してください。'}}
$consoleRoot=Split-Path -Parent $PSScriptRoot
$settingsFile=Join-Path $PrivateDirectory 'settings.dpapi'
$clear=[Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes($settingsFile),$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
try{
 $settings=[Text.Encoding]::UTF8.GetString($clear)|ConvertFrom-Json
 if($settings.PSObject.Properties.Name -contains 'CONTROL_EVIDENCE_PRIVATE_KEY'){
  if(!(Test-Path -LiteralPath $PublicKeyFile)){throw '公開鍵がありません。保管元から復元してください。'}
  Write-Output 'アクセス証跡の鍵は設定済みです。変更していません。';return
 }
 $encodedKey=& $JavaExecutable --class-path "$consoleRoot\target\classes" com.example.control.EvidenceKeys $PublicKeyFile
 if($LASTEXITCODE -ne 0){throw '証跡鍵の生成に失敗しました。'}
 $settings|Add-Member -NotePropertyName CONTROL_EVIDENCE_PRIVATE_KEY -NotePropertyValue ([string]$encodedKey)
 $settings|Add-Member -NotePropertyName CONTROL_EVIDENCE_PUBLIC_KEY -NotePropertyValue ('file:'+$PublicKeyFile.Replace('\','/'))
 $newBytes=[Text.Encoding]::UTF8.GetBytes(($settings|ConvertTo-Json -Compress))
 try{
  $sealed=[Security.Cryptography.ProtectedData]::Protect($newBytes,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
  $backup=Join-Path $PrivateDirectory ('settings.before-evidence-'+(Get-Date -Format 'yyyyMMdd_HHmmss')+'.dpapi')
  Copy-Item -LiteralPath $settingsFile -Destination $backup -ErrorAction Stop
  [IO.File]::WriteAllBytes((Join-Path $PrivateDirectory 'settings.staged.dpapi'),$sealed)
  Move-Item -LiteralPath (Join-Path $PrivateDirectory 'settings.staged.dpapi') -Destination $settingsFile -Force
 }finally{[Array]::Clear($newBytes,0,$newBytes.Length)}
 Write-Output "証跡用公開鍵: $PublicKeyFile"
 Write-Output '証跡用秘密鍵は既存DPAPIへ暗号化保存しました。管理ID・パスワード・認証器は変更していません。'
}finally{[Array]::Clear($clear,0,$clear.Length);$settings=$null;$encodedKey=$null}
