param([string]$Repository='D:\eclipse-workspace\OASystem',
      [string]$BuildRoot='D:\oa-maintenance\session-management-20261015\build',
      [string]$Tests='IsolatedSessionsStorageTest,IsolatedMembersStorageTest,IsolatedControlStorageTest')
$ErrorActionPreference='Stop'
if($Tests -notmatch '^[A-Za-z0-9_.*,#]+$'){throw '試験名を確認してください。'}
$previous=@{}
$names=@('OA_ACCEPTANCE_TEST','JAVA_HOME')
try{
 foreach($name in $names){$previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
 $env:OA_ACCEPTANCE_TEST='true';$env:JAVA_HOME='C:\Program Files\BellSoft\LibericaJDK-25-Full'
 Push-Location (Join-Path $Repository 'security-console')
 try{
  & 'D:\oa-maintenance\build-cache\apache-maven-3.9.16\bin\mvn.cmd' -o -s 'D:\oa-maintenance\build-cache\oa-maven-settings.xml' '-Dmaven.repo.local=D:\oa-maintenance\build-cache\repository' "-Dbuild.output-root=$BuildRoot\console" "-Dtest=$Tests" test
  if($LASTEXITCODE -ne 0){throw '合成会話の認証・権限・終了試験を確認してください。'}
 }finally{Pop-Location}
}finally{
 foreach($name in $previous.Keys){[Environment]::SetEnvironmentVariable($name,$previous[$name],'Process')}
}
