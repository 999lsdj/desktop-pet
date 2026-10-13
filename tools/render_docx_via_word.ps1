<#
DOCX 渲染验收工具（本机没有 LibreOffice，改用已安装的 Microsoft Word + 自带 poppler）

流程：Word 打开文档 -> 导出 PDF -> pdftoppm 逐页转 PNG，供肉眼检查排版。
用法：pwsh -File tools\render_docx_via_word.ps1 -Docx docs\x.docx -OutDir _tmp\render
#>
param(
  [Parameter(Mandatory = $true)][string]$Docx,
  [Parameter(Mandatory = $true)][string]$OutDir,
  [int]$Dpi = 150
)

$ErrorActionPreference = 'Stop'

$docxPath = (Resolve-Path -LiteralPath $Docx).Path
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$outAbs = (Resolve-Path -LiteralPath $OutDir).Path
$stem = [System.IO.Path]::GetFileNameWithoutExtension($docxPath)
$pdfPath = Join-Path $outAbs "$stem.pdf"

$word = $null
$doc = $null
try {
  $word = New-Object -ComObject Word.Application
  $word.Visible = $false
  $word.DisplayAlerts = 0
  $doc = $word.Documents.Open($docxPath, $false, $true)
  # 17 = wdExportFormatPDF
  $doc.ExportAsFixedFormat($pdfPath, 17)
  Write-Output "PDF: $pdfPath"
} finally {
  if ($doc) { $doc.Close($false) | Out-Null }
  if ($word) { $word.Quit() | Out-Null }
  foreach ($obj in @($doc, $word)) {
    if ($obj) { [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($obj) }
  }
  [GC]::Collect()
  [GC]::WaitForPendingFinalizers()
}

$pdftoppm = 'C:\Users\23606\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\poppler\Library\bin\pdftoppm.exe'
if (-not (Test-Path $pdftoppm)) { throw "找不到 pdftoppm: $pdftoppm" }

$prefix = Join-Path $outAbs 'page'
& $pdftoppm -png -r $Dpi $pdfPath $prefix
if ($LASTEXITCODE -ne 0) { throw "pdftoppm 转换失败（退出码 $LASTEXITCODE）" }

$pages = Get-ChildItem "$prefix-*.png" | Sort-Object Name
Write-Output "渲染完成，共 $($pages.Count) 页："
$pages | ForEach-Object { Write-Output ("  " + $_.FullName) }
