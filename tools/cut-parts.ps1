<#
桌宠拆件工具：把一张精灵图切成可绑骨的分层部件，并报告"转起来会露出来的透明缺口"。

为什么要有缺口报告：部件是按矩形裁的，转动时会露出原来被别的部件盖住、
或者立绘上根本没画过的地方。这些像素要交给 ComfyUI 补（第七组第二部分），
所以这里先把"每个关节要补多少、补在哪里"量出来，产出的是补全任务的输入清单。

用法：
  pwsh -File tools\cut-parts.ps1                       # 默认处理 assets\sprites\pet-base.png
  pwsh -File tools\cut-parts.ps1 -In <图> -OutDir <目录> -Overlap 3
#>
param(
  [string]$In = 'assets\sprites\pet-base.png',
  [string]$OutDir = '_tmp\parts',
  # 关节两侧的重叠余量（原生像素）：部件往外多留这么多，转起来才不容易露缝
  [int]$Overlap = 3
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

# 部件表：矩形按原生像素量；带 pivot 的部件会被转动做缺口检查
# 坐标取自 BodyRig 已经量好的那套（105×123）
$parts = @(
  @{ name = 'legNear'; x = 33; y = 88; w = 21; h = 35; pivotX = 44; pivotY = 89; maxDeg = 13 },
  @{ name = 'legFar';  x = 51; y = 88; w = 22; h = 35; pivotX = 61; pivotY = 89; maxDeg = 13 },
  @{ name = 'staff';   x = 0;  y = 5;  w = 33; h = 118; pivotX = 19; pivotY = 66; maxDeg = 35 },
  @{ name = 'body';    x = 0;  y = 0;  w = 105; h = 123 }
)

function New-Canvas([int]$w, [int]$h) {
  New-Object System.Drawing.Bitmap($w, $h, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
}

function Get-Alpha([System.Drawing.Bitmap]$bmp, [int]$x, [int]$y) {
  if ($x -lt 0 -or $y -lt 0 -or $x -ge $bmp.Width -or $y -ge $bmp.Height) { return 0 }
  return $bmp.GetPixel($x, $y).A
}

$src = New-Object System.Drawing.Bitmap($In)
$W = $src.Width
$H = $src.Height
Write-Output "input: $In  ${W}x${H}"

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# 1. 裁剪：每个部件往外放宽 Overlap，并把躯干挖掉别的部件占的格子
$cut = @{}
$nonBody = $parts | Where-Object { $_.name -ne 'body' }
foreach ($p in $parts) {
  $x0 = [math]::Max(0, $p.x - $Overlap)
  $y0 = [math]::Max(0, $p.y - $Overlap)
  $x1 = [math]::Min($W - 1, $p.x + $p.w - 1 + $Overlap)
  $y1 = [math]::Min($H - 1, $p.y + $p.h - 1 + $Overlap)
  $bmp = New-Canvas ($x1 - $x0 + 1) ($y1 - $y0 + 1)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.DrawImage($src, (New-Object System.Drawing.Rectangle(0, 0, $bmp.Width, $bmp.Height)),
    (New-Object System.Drawing.Rectangle($x0, $y0, $bmp.Width, $bmp.Height)),
    [System.Drawing.GraphicsUnit]::Pixel)
  $g.Dispose()
  if ($p.name -eq 'body') {
    # 躯干层把别的部件在原矩形范围内挖空，避免转动时看到两层重叠
    foreach ($q in $nonBody) {
      for ($y = $q.y; $y -lt $q.y + $q.h; $y++) {
        for ($x = $q.x; $x -lt $q.x + $q.w; $x++) {
          if ($x -ge $x0 -and $y -ge $y0 -and $x -le $x1 -and $y -le $y1) {
            $bmp.SetPixel($x - $x0, $y - $y0, [System.Drawing.Color]::FromArgb(0, 0, 0, 0))
          }
        }
      }
    }
  }
  $cut[$p.name] = @{ bmp = $bmp; x0 = $x0; y0 = $y0; spec = $p }
  $bmp.Save((Join-Path $OutDir "part-$($p.name).png"), [System.Drawing.Imaging.ImageFormat]::Png)
}
Write-Output "parts: $($parts.Count) -> $OutDir"

# 2. 拼装自检：所有部件按原位置贴回一张空画布，应与原图逐像素一致
$rebuilt = New-Canvas $W $H
$g2 = [System.Drawing.Graphics]::FromImage($rebuilt)
# 用 SourceOver：SourceCopy 会让后画的部件用透明区把先画的盖掉，
# 那是拼装方式的问题、不是坐标错。半透明边缘在重叠余量里会被叠两次，所以下面分开统计。
$g2.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceOver
foreach ($k in $cut.Keys) {
  $c = $cut[$k]
  $g2.DrawImage($c.bmp, $c.x0, $c.y0)
}
$g2.Dispose()
$mismatch = 0
$mismatchSolid = 0
for ($y = 0; $y -lt $H; $y++) {
  for ($x = 0; $x -lt $W; $x++) {
    $a = $src.GetPixel($x, $y)
    $b = $rebuilt.GetPixel($x, $y)
    if ($a.ToArgb() -ne $b.ToArgb()) {
      $mismatch++
      # 完全不透明的像素必须逐像素一致；接近不透明的边缘在重叠余量里被叠两次会差 ±1，单独计入总数
      if ($a.A -eq 255) { $mismatchSolid++ }
    }
  }
}
Write-Output "reassembly: 全不透明像素不匹配 $mismatchSolid，含边缘过渡共 $mismatch / $($W * $H)"
$rebuilt.Save((Join-Path $OutDir 'reassembled.png'), [System.Drawing.Imaging.ImageFormat]::Png)

# 3. 缺口报告：逐个关节把部件转到最大角度，数"原本有像素、转完变透明"的点
$summary = @()
foreach ($p in $nonBody) {
  $worst = 0
  $worstPng = $null
  foreach ($deg in @($p.maxDeg, -$p.maxDeg)) {
    $pose = New-Canvas $W $H
    $g3 = [System.Drawing.Graphics]::FromImage($pose)
    # 同样用 SourceOver：SourceCopy 下结果取决于绘制顺序，会统计出假的缺口
    $g3.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceOver
    foreach ($k in $cut.Keys) {
      $c = $cut[$k]
      if ($k -eq $p.name) {
        $g3.TranslateTransform([single]$p.pivotX, [single]$p.pivotY)
        $g3.RotateTransform([single]$deg)
        $g3.TranslateTransform([single](-$p.pivotX), [single](-$p.pivotY))
        $g3.DrawImage($c.bmp, $c.x0, $c.y0)
        $g3.ResetTransform()
      } else {
        $g3.DrawImage($c.bmp, $c.x0, $c.y0)
      }
    }
    $g3.Dispose()
    $gap = 0
    for ($y = 0; $y -lt $H; $y++) {
      for ($x = 0; $x -lt $W; $x++) {
        if ((Get-Alpha $src $x $y) -gt 8 -and (Get-Alpha $pose $x $y) -le 8) {
          $gap++
          $pose.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(255, 255, 40, 40))
        }
      }
    }
    if ($gap -gt $worst) {
      $worst = $gap
      $worstPng = Join-Path $OutDir "gap-$($p.name)-$deg.png"
      $pose.Save($worstPng, [System.Drawing.Imaging.ImageFormat]::Png)
    }
    $pose.Dispose()
  }
  $summary += [pscustomobject]@{ 部件 = $p.name; 极限角度 = $p.maxDeg; 缺口像素 = $worst; 报告图 = $worstPng }
}

Write-Output 'gap report (转起来会露出来的透明缺口):'
$summary | ForEach-Object { Write-Output ("  {0,-8} +-{1,3} 度 -> {2,5} 像素  {3}" -f $_.部件, $_.极限角度, $_.缺口像素, $_.报告图) }
$src.Dispose()
