param([string]$Repository='D:\eclipse-workspace\OASystem',
      [string]$BuildRoot='D:\oa-maintenance\progressive-protection-20261015\build')
$ErrorActionPreference='Stop'
# 本番DPAPI・DB資格情報を読み込まない。専用QA MySQLの準備後に実行する。
& (Join-Path $PSScriptRoot 'verify-session-management.ps1') -Repository $Repository -BuildRoot $BuildRoot -Tests 'IsolatedMembersStorageTest,IsolatedControlStorageTest'
