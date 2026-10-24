param(
 [Parameter(Mandatory=$true)][string]$Configuration,
 [string]$Jar,
 [ValidateRange(1024,65535)][int]$Port=8080,
 [string]$JavaExecutable=(Get-Command java.exe -ErrorAction Stop).Source
)
$ErrorActionPreference='Stop'
$repository=Split-Path -Parent $PSScriptRoot
if(-not $Jar){$Jar=Join-Path $repository 'target\OASystem-1-0.0.1-SNAPSHOT.jar'}
$configPath=(Resolve-Path -LiteralPath $Configuration -ErrorAction Stop).Path
$jarPath=(Resolve-Path -LiteralPath $Jar -ErrorAction Stop).Path
if(@(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue).Count){throw "ポート$Port は使用中です。既存プロセスを確認してください。自動停止は行いません。"}
$configUri=[uri]::new($configPath).AbsoluteUri
& $JavaExecutable -jar $jarPath "--spring.config.additional-location=$configUri" "--server.port=$Port" '--spring.jpa.hibernate.ddl-auto=validate' '--spring.sql.init.mode=never'
if($LASTEXITCODE -ne 0){throw 'OAを起動できません。接続設定・CA・スキーマを確認してください。'}
