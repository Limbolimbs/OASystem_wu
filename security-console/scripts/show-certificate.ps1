param([string]$PrivateDirectory='D:\oa-maintenance\control-private',
      [string]$JavaExecutable=(Get-Command java.exe -ErrorAction Stop).Source)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Security
$privateBytes=[Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes((Join-Path $PrivateDirectory 'settings.dpapi')),$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
try {
 $certificateSettings=[Text.Encoding]::UTF8.GetString($privateBytes)|ConvertFrom-Json
 $env:CONTROL_CERT_PASSWORD=$certificateSettings.CONTROL_CERT_PASSWORD
 $keytoolExecutable=Join-Path (Split-Path -Parent $JavaExecutable) 'keytool.exe'
 & $keytoolExecutable -list -v -alias control -keystore (Join-Path $PrivateDirectory 'local.p12') -storepass:env CONTROL_CERT_PASSWORD
 if($LASTEXITCODE -ne 0){throw '証明書を確認できません。'}
}finally{Remove-Item Env:CONTROL_CERT_PASSWORD -ErrorAction SilentlyContinue;[Array]::Clear($privateBytes,0,$privateBytes.Length);$certificateSettings=$null}
