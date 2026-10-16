param([string]$SourceConfig = 'D:\eclipse-workspace\OASystem\src\main\resources\application.properties',
      [string]$PrivateDirectory = 'D:\oa-maintenance\control-private',
      [string]$DependencyRepository = 'D:\oa-maintenance\build-cache\repository',
      [string]$JavaExecutable = (Get-Command java.exe -ErrorAction Stop).Source)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Security
$consoleRoot = Split-Path -Parent $PSScriptRoot
$keytoolExecutable=Join-Path (Split-Path -Parent $JavaExecutable) 'keytool.exe'
if(!(Test-Path -LiteralPath $JavaExecutable) -or !(Test-Path -LiteralPath $keytoolExecutable)){throw 'Java 25のjava.exeとkeytool.exeを指定してください。'}
if(Test-Path -LiteralPath $PrivateDirectory){throw '初期化済みディレクトリは上書きしません。'}
if([IO.Path]::GetPathRoot([IO.Path]::GetFullPath($PrivateDirectory)) -ne 'D:\'){throw '秘密情報はDドライブへ保存してください。'}
New-Item -ItemType Directory -Path $PrivateDirectory | Out-Null
$consoleAcl=[Security.AccessControl.DirectorySecurity]::new()
$consoleAcl.SetAccessRuleProtection($true,$false)
$consoleOwner=[Security.Principal.WindowsIdentity]::GetCurrent().User
$consoleAcl.SetOwner($consoleOwner)
foreach($identity in @($consoleOwner,[Security.Principal.SecurityIdentifier]::new('S-1-5-18'),[Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))){$consoleAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($identity,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))}
Set-Acl -LiteralPath $PrivateDirectory -AclObject $consoleAcl
$libraries=@(
 "$DependencyRepository\org\springframework\security\spring-security-crypto\7.1.1\spring-security-crypto-7.1.1.jar",
 "$DependencyRepository\org\springframework\spring-core\7.0.9\spring-core-7.0.9.jar",
 "$DependencyRepository\commons-logging\commons-logging\1.3.5\commons-logging-1.3.5.jar",
 "$DependencyRepository\org\bouncycastle\bcprov-jdk18on\1.86\bcprov-jdk18on-1.86.jar",
 "$DependencyRepository\com\mysql\mysql-connector-j\9.7.0\mysql-connector-j-9.7.0.jar"
)
foreach($library in $libraries){if(!(Test-Path -LiteralPath $library)){throw "依存ファイルが不足しています: $library"}}
$classpath=(@("$consoleRoot\target\classes")+$libraries)-join ';'
$backupDirectory='D:\oa-maintenance\control-schema-' + (Get-Date -Format 'yyyyMMdd_HHmmss')
try {
 $bootstrapJson=& $JavaExecutable --class-path $classpath com.example.control.Provisioning $SourceConfig $backupDirectory
 if($LASTEXITCODE -ne 0){throw '初期化に失敗しました。DDLは自動コミットのため、保守ディレクトリを確認してください。'}
 $consoleSecrets=$bootstrapJson | ConvertFrom-Json
 $secretBytes=[Text.Encoding]::UTF8.GetBytes($bootstrapJson)
 try{[IO.File]::WriteAllBytes((Join-Path $PrivateDirectory 'settings.dpapi'),[Security.Cryptography.ProtectedData]::Protect($secretBytes,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser))}finally{[Array]::Clear($secretBytes,0,$secretBytes.Length)}
 $env:CONTROL_CERT_PASSWORD=$consoleSecrets.CONTROL_CERT_PASSWORD
 & $keytoolExecutable -genkeypair -alias control -keyalg RSA -keysize 3072 -validity 365 -dname 'CN=localhost' -ext 'SAN=DNS:localhost,IP:127.0.0.1' -storetype PKCS12 -keystore (Join-Path $PrivateDirectory 'local.p12') -storepass:env CONTROL_CERT_PASSWORD -keypass:env CONTROL_CERT_PASSWORD 2>&1 | Out-Null
 if($LASTEXITCODE -ne 0){throw 'ローカル証明書を生成できません。設定ファイルは保持しています。'}
 Write-Output "初期化・暗号化保管: $PrivateDirectory"
 Write-Output "SQL移行記録: $backupDirectory"
 Write-Output '管理ID・パスワード・認証器登録情報はshow-enrollment.ps1を本機で実行して確認してください。'
}finally{Remove-Item Env:CONTROL_CERT_PASSWORD -ErrorAction SilentlyContinue;$bootstrapJson=$null;$consoleSecrets=$null}
