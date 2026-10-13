<#
运行桌宠。
用法：
  pwsh -File run.ps1            正常启动
  pwsh -File run.ps1 --demo     启动后自动展开菜单（用于截图验收）
#>
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)

$root = $PSScriptRoot
$classpath = Join-Path $root 'out'
Push-Location $root
try {
    # uiScale=1 : 关闭 Java 的 UI 缩放，让绘制坐标等于物理像素，精灵图按整数倍放大才清晰
    & java '-Dsun.java2d.uiScale=1' '-Dsun.java2d.dpiaware=true' -cp $classpath pet.Main @Args
} finally {
    Pop-Location
}
