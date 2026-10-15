<#
桌宠精灵处理工具（纯 PowerShell 实现，不依赖动态编译）

输入：像素风角色图（背景是被压成真实像素的棋盘格，无透明通道）
输出：
  1) 透明底 PNG（泛洪抠掉与边界连通的背景）
  2) 自动识别原始像素格大小 k，按块降采样回原生分辨率精灵图
  3) 最近邻放大预览，供肉眼验收

用法：
  pwsh -File tools\prepare-sprite.ps1 -In <输入图> -OutDir <输出目录>
#>
param(
  [Parameter(Mandatory = $true)][string]$In,
  [Parameter(Mandatory = $true)][string]$OutDir,
  [int]$BgThreshold = 238,
  # alpha 保留输入透明度；auto 判断背景深浅；white / black 强制按背景色抠底
  [string]$BgMode = 'auto',
  # 深色背景的通道上限：三个通道都不超过它才算背景
  [int]$BgDarkThreshold = 20,
  [int]$PreviewScale = 4,
  [int]$MaxBlock = 16,
  [int]$BlockSize = 0
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

# 载入为 32bppArgb
$srcImg = [System.Drawing.Image]::FromFile($In)
$bmp = New-Object System.Drawing.Bitmap($srcImg.Width, $srcImg.Height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$gInit = [System.Drawing.Graphics]::FromImage($bmp)
$gInit.DrawImage($srcImg, (New-Object System.Drawing.Rectangle(0, 0, $srcImg.Width, $srcImg.Height)))
$gInit.Dispose()
$srcImg.Dispose()

Write-Output "input: $In  $($bmp.Width)x$($bmp.Height)"

$rect = New-Object System.Drawing.Rectangle(0, 0, $bmp.Width, $bmp.Height)
$data = $bmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadWrite, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$stride = $data.Stride
$W = $bmp.Width
$H = $bmp.Height
$bytes = New-Object byte[] ($stride * $H)
[System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)

# 1. 泛洪：与边界连通的接近白色像素 -> 背景
# 先定背景判据：黑底立绘和浅底立绘的"背景"是反过来的
if ($BgMode -eq 'auto') {
  $sumLuma = 0.0
  $samples = 0
  foreach ($cx in 0, ($W - 16)) {
    foreach ($cy in 0, ($H - 16)) {
      for ($yy = 0; $yy -lt 16; $yy++) {
        for ($xx = 0; $xx -lt 16; $xx++) {
          $o = ($cy + $yy) * $stride + ($cx + $xx) * 4
          $sumLuma += ($bytes[$o] + $bytes[$o + 1] + $bytes[$o + 2]) / 3.0
          $samples++
        }
      }
    }
  }
  $avgLuma = $sumLuma / $samples
  $BgMode = if ($avgLuma -lt 128) { 'black' } else { 'white' }
  Write-Output ("background mode: auto -> {0} (corner luma {1:N1})" -f $BgMode, $avgLuma)
} elseif ($BgMode -notin @('white', 'black', 'alpha')) {
  throw "BgMode 只能是 auto / white / black / alpha，收到的是 '$BgMode'"
}

$isBg = New-Object 'bool[]' ($W * $H)
$stack = New-Object 'System.Collections.Generic.Stack[int]'

if ($BgMode -eq 'alpha') {
  for ($y = 0; $y -lt $H; $y++) {
    for ($x = 0; $x -lt $W; $x++) {
      $isBg[$y * $W + $x] = ($bytes[$y * $stride + $x * 4 + 3] -eq 0)
    }
  }
} else {
for ($x = 0; $x -lt $W; $x++) {
  $stack.Push($x)
  $stack.Push(($H - 1) * $W + $x)
}
for ($y = 0; $y -lt $H; $y++) {
  $stack.Push($y * $W)
  $stack.Push($y * $W + $W - 1)
}

while ($stack.Count -gt 0) {
  $p = $stack.Pop()
  if ($isBg[$p]) { continue }
  $x = $p % $W
  $y = [int](($p - $x) / $W)
  $o = $y * $stride + $x * 4
  $b = $bytes[$o]; $g = $bytes[$o + 1]; $r = $bytes[$o + 2]
  if ($BgMode -eq 'white') {
    $m = $r
    if ($g -lt $m) { $m = $g }
    if ($b -lt $m) { $m = $b }
    if ($m -lt $BgThreshold) { continue }
  } else {
    $m = $r
    if ($g -gt $m) { $m = $g }
    if ($b -gt $m) { $m = $b }
    if ($m -gt $BgDarkThreshold) { continue }
  }

  $isBg[$p] = $true
  if ($x -gt 0)      { $q = $p - 1; if (-not $isBg[$q]) { $stack.Push($q) } }
  if ($x -lt $W - 1) { $q = $p + 1; if (-not $isBg[$q]) { $stack.Push($q) } }
  if ($y -gt 0)      { $q = $p - $W; if (-not $isBg[$q]) { $stack.Push($q) } }
  if ($y -lt $H - 1) { $q = $p + $W; if (-not $isBg[$q]) { $stack.Push($q) } }
}

}

# 2. 应用透明度 + 统计包围盒
$minX = $W; $minY = $H; $maxX = -1; $maxY = -1; $bgCount = 0
for ($y = 0; $y -lt $H; $y++) {
  $rowBase = $y * $W
  $offBase = $y * $stride
  for ($x = 0; $x -lt $W; $x++) {
    $o = $offBase + $x * 4
    if ($isBg[$rowBase + $x]) {
      $bytes[$o] = 0; $bytes[$o + 1] = 0; $bytes[$o + 2] = 0; $bytes[$o + 3] = 0
      $bgCount++
    } else {
      if ($x -lt $minX) { $minX = $x }
      if ($x -gt $maxX) { $maxX = $x }
      if ($y -lt $minY) { $minY = $y }
      if ($y -gt $maxY) { $maxY = $y }
    }
  }
}

$bgPct = [math]::Round(100.0 * $bgCount / ($W * $H), 1)
Write-Output "background removed: $bgPct% of pixels"
Write-Output "content bbox: x=$minX..$maxX y=$minY..$maxY ($($maxX-$minX+1)x$($maxY-$minY+1))"

# 3. 识别原生像素格：统计颜色变化位置落在 k 的倍数上的比例
$fx = New-Object 'System.Collections.Generic.List[int]'
$fy = New-Object 'System.Collections.Generic.List[int]'
for ($y = $minY; $y -le $maxY; $y += 2) {
  $offBase = $y * $stride
  for ($x = $minX; $x -lt $maxX; $x++) {
    $o1 = $offBase + $x * 4
    $o2 = $o1 + 4
    $d = [math]::Abs([int]$bytes[$o1] - [int]$bytes[$o2]) +
         [math]::Abs([int]$bytes[$o1 + 1] - [int]$bytes[$o2 + 1]) +
         [math]::Abs([int]$bytes[$o1 + 2] - [int]$bytes[$o2 + 2])
    if ($d -gt 40) { $fx.Add($x) }
  }
}
for ($x = $minX; $x -le $maxX; $x += 2) {
  for ($y = $minY; $y -lt $maxY; $y++) {
    $o1 = $y * $stride + $x * 4
    $o2 = $o1 + $stride
    $d = [math]::Abs([int]$bytes[$o1] - [int]$bytes[$o2]) +
         [math]::Abs([int]$bytes[$o1 + 1] - [int]$bytes[$o2 + 1]) +
         [math]::Abs([int]$bytes[$o1 + 2] - [int]$bytes[$o2 + 2])
    if ($d -gt 40) { $fy.Add($y) }
  }
}

$bestK = 1; $bestScore = -1.0
$scoreParts = @()
foreach ($k in 2..$MaxBlock) {
  $hit = 0
  foreach ($p in $fx) { if (($p % $k) -eq 0) { $hit++ } }
  foreach ($p in $fy) { if (($p % $k) -eq 0) { $hit++ } }
  $total = $fx.Count + $fy.Count
  if ($total -eq 0) { continue }
  $score = 100.0 * $hit / $total
  $scoreParts += "${k}:$([math]::Round($score,1))%"
  if ($score -gt $bestScore) { $bestScore = $score; $bestK = $k }
}
Write-Output "block score -> $($scoreParts -join '  ')"
Write-Output "auto block guess: ${bestK}x${bestK} (align $([math]::Round($bestScore,1))%, edges $($fx.Count + $fy.Count))"

# 4. 按块降采样到原生分辨率（显式指定优先；纯色块网格常用 4 或 5）
$k = if ($BlockSize -gt 0) { $BlockSize } else { $bestK }
if ($W % $k -ne 0 -or $H % $k -ne 0) {
  throw "降采样倍率 $k 必须整除原画布 ${W}x${H}"
}
if ($maxX -lt $minX -or $maxY -lt $minY) { throw '没有找到非透明角色内容' }
Write-Output "using block: ${k}x${k}"
$cw = [int]([math]::Floor(($maxX - $minX + 1) / $k))
$ch = [int]([math]::Floor(($maxY - $minY + 1) / $k))
if ($cw -lt 1) { $cw = 1 }
if ($ch -lt 1) { $ch = 1 }

$native = New-Object System.Drawing.Bitmap($cw, $ch, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$nativeRect = New-Object System.Drawing.Rectangle(0, 0, $cw, $ch)
$nativeData = $native.LockBits($nativeRect, [System.Drawing.Imaging.ImageLockMode]::ReadWrite, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$nativeStride = $nativeData.Stride
$nativeBytes = New-Object byte[] ($nativeStride * $ch)
# 加权盒式平均：按 alpha 加权，避免透明像素把边缘拉黑
for ($ny = 0; $ny -lt $ch; $ny++) {
  for ($nx = 0; $nx -lt $cw; $nx++) {
    $sumA = 0.0; $sumR = 0.0; $sumG = 0.0; $sumB = 0.0; $n = 0
    for ($dy = 0; $dy -lt $k; $dy++) {
      $sy = $minY + $ny * $k + $dy
      if ($sy -gt $maxY) { continue }
      for ($dx = 0; $dx -lt $k; $dx++) {
        $sx = $minX + $nx * $k + $dx
        if ($sx -gt $maxX) { continue }
        $so = $sy * $stride + $sx * 4
        $a = $bytes[$so + 3] / 255.0
        $sumA += $a
        $sumR += $bytes[$so + 2] * $a
        $sumG += $bytes[$so + 1] * $a
        $sumB += $bytes[$so] * $a
        $n++
      }
    }
    $do = $ny * $nativeStride + $nx * 4
    if ($sumA -gt 0) {
      $nativeBytes[$do + 3] = [byte][math]::Round(255.0 * $sumA / $n)
      $nativeBytes[$do + 2] = [byte][math]::Round($sumR / $sumA)
      $nativeBytes[$do + 1] = [byte][math]::Round($sumG / $sumA)
      $nativeBytes[$do]     = [byte][math]::Round($sumB / $sumA)
    } else {
      $nativeBytes[$do] = 0; $nativeBytes[$do + 1] = 0; $nativeBytes[$do + 2] = 0; $nativeBytes[$do + 3] = 0
    }
  }
}
[System.Runtime.InteropServices.Marshal]::Copy($nativeBytes, 0, $nativeData.Scan0, $nativeBytes.Length)
$native.UnlockBits($nativeData)

Write-Output "native sprite: ${cw}x${ch}"
Write-Output ("downsample: block {0}, full canvas {1}x{2} (= {3} 的 1/{0}), content crop {4}x{5}" -f `
  $k, [int]($W / $k), [int]($H / $k), $W, $cw, $ch)

# 5. 保存
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

[System.Runtime.InteropServices.Marshal]::Copy($bytes, 0, $data.Scan0, $bytes.Length)
$bmp.UnlockBits($data)

$fullPath = Join-Path $OutDir 'sprite-full-transparent.png'
$bmp.Save($fullPath, [System.Drawing.Imaging.ImageFormat]::Png)

$nativePath = Join-Path $OutDir "sprite-native-${cw}x${ch}.png"
$native.Save($nativePath, [System.Drawing.Imaging.ImageFormat]::Png)

$preview = New-Object System.Drawing.Bitmap(($cw * $PreviewScale), ($ch * $PreviewScale), [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$gp = [System.Drawing.Graphics]::FromImage($preview)
$gp.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$gp.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
$gp.DrawImage($native, (New-Object System.Drawing.Rectangle(0, 0, ($cw * $PreviewScale), ($ch * $PreviewScale))))
$gp.Dispose()
$previewPath = Join-Path $OutDir "preview-native-${PreviewScale}x.png"
$preview.Save($previewPath, [System.Drawing.Imaging.ImageFormat]::Png)

[ordered]@{
  source = (Resolve-Path -LiteralPath $In).Path
  sourceSha256 = (Get-FileHash -LiteralPath $In -Algorithm SHA256).Hash.ToLowerInvariant()
  sourceSize = @($W, $H)
  backgroundMode = $BgMode
  blockSize = $k
  fullCanvasSize = @([int]($W / $k), [int]($H / $k))
  contentBounds = @($minX, $minY, ($maxX + 1), ($maxY + 1))
  originOffset = @($minX, $minY)
  sampledBounds = @($minX, $minY, ($minX + $cw * $k), ($minY + $ch * $k))
  croppedSize = @($cw, $ch)
  sampling = 'alpha-weighted-box; floor content dimensions; nearest-neighbor display'
} | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $OutDir 'manifest.json') -Encoding utf8

$bmp.Dispose(); $native.Dispose(); $preview.Dispose()

Write-Output "full transparent: $fullPath"
Write-Output "native sprite:    $nativePath"
Write-Output "preview:          $previewPath"
