param(
    [ValidateSet('check','migrate','restore')][string]$Mode = 'check',
    [string]$Repository = 'D:\eclipse-workspace\OASystem',
    [string]$DependencyRepository = 'D:\oa-maintenance\build-cache\repository',
    [string]$Java = 'C:\Program Files\BellSoft\LibericaJDK-25-Full\bin\java.exe',
    [string]$BackupDirectory
)
$ErrorActionPreference = 'Stop'
if ($Mode -ne 'check' -and (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue)) {
    throw 'アプリを停止してからパスワード移行・復旧を実行してください。'
}
$libraryPaths = @(
    "$DependencyRepository\org\springframework\security\spring-security-crypto\7.1.1\spring-security-crypto-7.1.1.jar",
    "$DependencyRepository\org\springframework\spring-core\7.0.9\spring-core-7.0.9.jar",
    "$DependencyRepository\commons-logging\commons-logging\1.3.5\commons-logging-1.3.5.jar",
    "$DependencyRepository\org\bouncycastle\bcprov-jdk18on\1.86\bcprov-jdk18on-1.86.jar",
    "$DependencyRepository\com\mysql\mysql-connector-j\9.7.0\mysql-connector-j-9.7.0.jar"
)
foreach ($libraryPath in $libraryPaths) { if (!(Test-Path -LiteralPath $libraryPath)) { throw "依存ファイルがありません: $libraryPath" } }
$migrationClasspath = (@("$Repository\target\classes") + $libraryPaths) -join ';'
$migrationKey = $null
try {
    if ($Mode -eq 'migrate') {
        if (!$BackupDirectory) { $BackupDirectory = 'D:\oa-maintenance\password-backups\' + (Get-Date -Format 'yyyyMMdd_HHmmss') + '_' + [Guid]::NewGuid().ToString('N') }
        if ([IO.Path]::GetPathRoot([IO.Path]::GetFullPath($BackupDirectory)) -ne 'D:\') { throw 'バックアップ先はDドライブを指定してください。' }
        if (Test-Path -LiteralPath $BackupDirectory) { throw '新しいバックアップディレクトリを指定してください。既存データは上書きしません。' }
        New-Item -ItemType Directory -Path $BackupDirectory | Out-Null
        $backupAcl = [Security.AccessControl.DirectorySecurity]::new()
        $backupAcl.SetAccessRuleProtection($true, $false)
        $currentIdentity = [Security.Principal.WindowsIdentity]::GetCurrent().User
        $backupAcl.SetOwner($currentIdentity)
        foreach ($identity in @($currentIdentity, [Security.Principal.SecurityIdentifier]::new('S-1-5-18'), [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))) {
            $backupAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
        }
        Set-Acl -LiteralPath $BackupDirectory -AclObject $backupAcl
        $migrationKey = [byte[]]::new(32)
        $randomSource = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $randomSource.GetBytes($migrationKey) } finally { $randomSource.Dispose() }
        $protectedKey = [Security.Cryptography.ProtectedData]::Protect($migrationKey, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        [IO.File]::WriteAllBytes((Join-Path $BackupDirectory 'backup-key.dpapi'), $protectedKey)
    } elseif ($Mode -eq 'restore') {
        if (!$BackupDirectory) { throw '復旧する暗号化バックアップのディレクトリを指定してください。' }
        $migrationKey = [Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes((Join-Path $BackupDirectory 'backup-key.dpapi')), $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
    }
    if ($migrationKey) { $env:OA_MIGRATION_KEY = [Convert]::ToBase64String($migrationKey) }
    $backupFile = if ($BackupDirectory) { Join-Path $BackupDirectory 'passwords.aesgcm' } else { 'D:\oa-maintenance\unused-password-backup' }
    & $Java --class-path $migrationClasspath com.example.demo.maintenance.PasswordMigration "$Repository\src\main\resources\application.properties" $Mode $backupFile
    if ($LASTEXITCODE -ne 0) { throw '移行ツールが失敗しました。秘密情報を表示せず、実行結果と移行済み件数を確認してください。' }
    if ($Mode -eq 'migrate') { Write-Output "暗号化バックアップ: $BackupDirectory" }
} finally {
    Remove-Item Env:OA_MIGRATION_KEY -ErrorAction SilentlyContinue
    if ($migrationKey) { [Array]::Clear($migrationKey, 0, $migrationKey.Length) }
}
