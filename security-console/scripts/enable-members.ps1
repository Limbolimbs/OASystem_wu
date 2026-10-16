param([string]$PrivateDirectory='D:\oa-maintenance\control-private')
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Security
if([IO.Path]::GetPathRoot([IO.Path]::GetFullPath($PrivateDirectory)) -ne 'D:\'){throw 'Dドライブの保守領域を指定してください。'}
$settingsFile=Join-Path $PrivateDirectory 'settings.dpapi'
$clear=[Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes($settingsFile),$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
try{
 $settings=[Text.Encoding]::UTF8.GetString($clear)|ConvertFrom-Json
 if($settings.PSObject.Properties.Name -contains 'CONTROL_MEMBER_KEY'){Write-Output '個人認証の鍵は設定済みです。最高管理者の資格情報は変更していません。';return}
 $keyBytes=New-Object byte[] 32
 $generator=[Security.Cryptography.RandomNumberGenerator]::Create()
 try{$generator.GetBytes($keyBytes);$settings|Add-Member -NotePropertyName CONTROL_MEMBER_KEY -NotePropertyValue ([Convert]::ToBase64String($keyBytes))}finally{$generator.Dispose();[Array]::Clear($keyBytes,0,$keyBytes.Length)}
 $newBytes=[Text.Encoding]::UTF8.GetBytes(($settings|ConvertTo-Json -Compress))
 try{
  $sealed=[Security.Cryptography.ProtectedData]::Protect($newBytes,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
  Copy-Item -LiteralPath $settingsFile -Destination (Join-Path $PrivateDirectory ('settings.before-members-'+(Get-Date -Format 'yyyyMMdd_HHmmss')+'.dpapi')) -ErrorAction Stop
  $staged=Join-Path $PrivateDirectory 'members.staged.dpapi'
  [IO.File]::WriteAllBytes($staged,$sealed)
  Move-Item -LiteralPath $staged -Destination $settingsFile -Force
 }finally{[Array]::Clear($newBytes,0,$newBytes.Length)}
 Write-Output '個人TOTPの専用鍵をDPAPIへ追加しました。最高管理者のID・パスワード・認証器は変更していません。'
}finally{[Array]::Clear($clear,0,$clear.Length);$settings=$null}
