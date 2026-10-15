<# 下载结束后校验模型并连续执行两次衣袖补洞验证，状态写入 _tmp/comfy/setup-status.json。 #>
param(
  [Parameter(Mandatory = $true)][int]$DownloadProcessId,
  [string]$ComfyRoot = 'E:\AI\ComfyUI_windows_portable'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$logDir = Join-Path $repoRoot '_tmp\comfy'
$pythonExe = Join-Path $ComfyRoot 'python_embeded\python.exe'
$modelPath = Join-Path $ComfyRoot 'ComfyUI\models\checkpoints\sd_xl_base_1.0_inpainting_0.1.safetensors'
$statusPath = Join-Path $logDir 'setup-status.json'
New-Item -ItemType Directory -Path $logDir -Force | Out-Null

function Write-SetupStatus([string]$Phase, [string]$Detail) {
  [ordered]@{ phase = $Phase; detail = $Detail; updatedAt = [DateTimeOffset]::Now.ToString('o') } |
    ConvertTo-Json | Set-Content -LiteralPath $statusPath -Encoding utf8
}

try {
  Write-SetupStatus 'waiting_download' '等待已有下载进程完成，随后验证模型并执行两次补洞。'
  $deadline = [DateTime]::UtcNow.AddHours(6)
  while ($process = Get-CimInstance Win32_Process -Filter "ProcessId=$DownloadProcessId") {
    if ($process.CommandLine -notlike '*tools\download-comfy-model.py*') {
      throw '进程标识已被其他程序复用，停止等待。'
    }
    if ([DateTime]::UtcNow -gt $deadline) { throw '下载等待超过六小时，保留断点。' }
    Start-Sleep -Seconds 5
  }
  Write-SetupStatus 'checking_model' '下载进程已退出，核对文件大小和完整 SHA-256。'
  if ((Get-Item -LiteralPath $modelPath).Length -ne 6938069944) { throw '模型未下载完整，不能开始补洞。' }
  $digest = (Get-FileHash -LiteralPath $modelPath -Algorithm SHA256).Hash.ToLowerInvariant()
  if ($digest -ne 'fe1b97fe6544814eb6fc8ce53f04ad8d339ec6946b58b0afd566fcc47813fa8a') {
    throw '模型 SHA-256 不匹配，不能开始补洞。'
  }
  if (-not (Get-NetTCPConnection -State Listen -LocalPort 8188 -ErrorAction SilentlyContinue)) {
    $server = Start-Process -FilePath $pythonExe -ArgumentList @('-X','utf8','-s','ComfyUI\main.py','--windows-standalone-build','--disable-auto-launch','--listen','127.0.0.1','--reserve-vram','1') -WorkingDirectory $ComfyRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logDir 'server.stdout.log') -RedirectStandardError (Join-Path $logDir 'server.stderr.log') -PassThru
    $server.Id | Set-Content -LiteralPath (Join-Path $logDir 'server.pid')
  }
  $ready = $false
  for ($i = 0; $i -lt 60; $i++) {
    try {
      $null = Invoke-RestMethod -Uri 'http://127.0.0.1:8188/system_stats' -TimeoutSec 2
      $ready = $true
      break
    } catch { Start-Sleep -Seconds 2 }
  }
  if (-not $ready) { throw 'ComfyUI 未就绪，检查 server.stderr.log。' }
  $smokeDir = Join-Path $logDir 'smoke'
  for ($run = 1; $run -le 2; $run++) {
    Write-SetupStatus "smoke_$run" "执行第 $run 次 Q 版衣袖补洞和像素验证。"
    & $pythonExe -X utf8 -s (Join-Path $PSScriptRoot 'comfy-inpaint.py') --smoke --out $smokeDir
    if ($LASTEXITCODE -ne 0) { throw "第 $run 次补洞失败，检查 smoke/history.json 和日志。" }
    Copy-Item -LiteralPath (Join-Path $smokeDir 'report.json') -Destination (Join-Path $logDir "smoke-run-$run.json")
    $files = @(Get-ChildItem -LiteralPath $smokeDir -File | Sort-Object Name | Select-Object -ExpandProperty Name)
    if ($run -eq 1) { $firstFiles = $files }
    elseif (@(Compare-Object $firstFiles $files).Count -ne 0) { throw '两次产出目录结构不一致。' }
  }
  Write-SetupStatus 'checks_passed_visual_review_pending' '模型校验和两次补洞执行通过，蒙版外像素检查通过；仍需人工验收 comparison.png 的风格与接缝。'
} catch {
  Write-SetupStatus 'failed' $_.Exception.Message
  throw
}
