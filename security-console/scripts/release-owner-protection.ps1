param([string]$PrivateDirectory='D:\oa-maintenance\control-private',
      [string]$JarPath=(Join-Path (Split-Path -Parent $PSScriptRoot) 'target\security-console-1.0.0.jar'),
      [string]$JavaExecutable=(Get-Command java.exe -ErrorAction Stop).Source)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Security
$clear=[Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes((Join-Path $PrivateDirectory 'settings.dpapi')),$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
$prior=@{}
try {
 $settings=[Text.Encoding]::UTF8.GetString($clear)|ConvertFrom-Json
 foreach($property in $settings.PSObject.Properties){
  if($property.Name.StartsWith('CONTROL_')){
   $prior[$property.Name]=[Environment]::GetEnvironmentVariable($property.Name,'Process')
   [Environment]::SetEnvironmentVariable($property.Name,[string]$property.Value,'Process')
  }
 }
 & $JavaExecutable '-Dloader.main=com.example.control.OwnerProtectionRecovery' -cp $JarPath org.springframework.boot.loader.launch.PropertiesLauncher
 if($LASTEXITCODE -ne 0){throw '解除を完了できません。記録を確認してください。'}
} finally {
 foreach($name in $prior.Keys){[Environment]::SetEnvironmentVariable($name,$prior[$name],'Process')}
 [Array]::Clear($clear,0,$clear.Length);$settings=$null
}
