<#
编译桌宠。零第三方依赖，直接用 JDK 自带的 javac。
用法：pwsh -File build.ps1
#>
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$out = Join-Path $root 'out'
New-Item -ItemType Directory -Force -Path $out | Out-Null

$sources = Get-ChildItem (Join-Path $root 'src\pet') -Filter '*.java' | ForEach-Object { $_.FullName }
if (-not $sources) { throw "没有找到源文件" }

& javac -encoding UTF-8 -d $out $sources
if ($LASTEXITCODE -ne 0) { throw "javac 编译失败（退出码 $LASTEXITCODE）" }

Write-Output "编译完成 -> $out"
