param([string]$Directory='D:\oa-maintenance\geoip')
$ErrorActionPreference='Stop'
if([IO.Path]::GetPathRoot([IO.Path]::GetFullPath($Directory)) -ne 'D:\'){throw 'Dドライブの保存先を指定してください。'}
New-Item -ItemType Directory -Path $Directory -Force|Out-Null
foreach($kind in @('city','asn')){
 $page=(Invoke-WebRequest -UseBasicParsing "https://db-ip.com/db/download/ip-to-$kind-lite").Content
 $match=[regex]::Match($page,"https://download\.db-ip\.com/free/dbip-$kind-lite-(\d{4}-\d{2})\.mmdb\.gz")
 if(!$match.Success){throw '公式MMDBダウンロードを確認できません。'}
 $mmdbSection=$page.Substring($page.IndexOf('<dd>MMDB</dd>'))
 $checksum=[regex]::Match($mmdbSection,'SHA1SUM</dt>\s*<dd[^>]*>\s*([0-9a-f]{40})\s*</dd>').Groups[1].Value
 if(!$checksum){throw '公式チェックサムを確認できません。'}
 $version=$match.Groups[1].Value;$compressed=Join-Path $Directory "dbip-$kind-lite-$version.mmdb.gz";$raw=Join-Path $Directory "dbip-$kind-lite-$version.mmdb"
 if(!(Test-Path -LiteralPath $raw)){
  Invoke-WebRequest -UseBasicParsing $match.Value -OutFile $compressed
  $input=[IO.File]::OpenRead($compressed);$gzip=[IO.Compression.GZipStream]::new($input,[IO.Compression.CompressionMode]::Decompress);$output=[IO.File]::Open($raw,[IO.FileMode]::CreateNew)
  try{$gzip.CopyTo($output)}finally{$output.Dispose();$gzip.Dispose();$input.Dispose()}
 }
 if((Get-Item -LiteralPath $raw).Length -gt 300MB){throw 'データサイズが上限を超えました。'}
 $rawHash=(Get-FileHash -LiteralPath $raw -Algorithm SHA1).Hash.ToLowerInvariant()
 $gzipHash=if(Test-Path -LiteralPath $compressed){(Get-FileHash -LiteralPath $compressed -Algorithm SHA1).Hash.ToLowerInvariant()}else{''}
 if($rawHash -ne $checksum -and $gzipHash -ne $checksum){throw '公式チェックサムが一致しません。適用していません。'}
 # 公開データだけ。接続元IPを送信しない。起動中のReaderはMEMORYモードを使用。
 Copy-Item -LiteralPath $raw -Destination (Join-Path $Directory "$kind.mmdb") -Force
 Write-Output "DB-IP $kind Lite ${version}: 検証・配置済み"
}
Write-Output 'CC BY 4.0 / DB-IP.com の表示を管理画面に含めます。再起動すると新しいデータを読み込みます。'
