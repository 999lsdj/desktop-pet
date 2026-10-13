<#
全屏检测器：每隔一段时间检查前台窗口是否铺满整个屏幕，状态变化时输出一行。
Java 端把它作为子进程启动，读它的标准输出。
输出：FULLSCREEN 或 NORMAL
#>
Add-Type -MemberDefinition @'
[DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
[DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
[DllImport("user32.dll")] public static extern int GetClassName(IntPtr h, System.Text.StringBuilder s, int n);
[DllImport("user32.dll")] public static extern IntPtr FindWindow(string cls, string win);
[DllImport("user32.dll")] public static extern int GetSystemMetrics(int index);
[DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
[StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
'@ -Name W -Namespace Fs

[void][Fs.W]::SetProcessDPIAware()
$screenW = [Fs.W]::GetSystemMetrics(0)
$screenH = [Fs.W]::GetSystemMetrics(1)
$shellClasses = @('Progman', 'WorkerW', 'Shell_TrayWnd', 'Shell_SecondaryTrayWnd', 'Windows.UI.Core.CoreWindow')
$last = ''
$lastGround = -1
$taskbar = [Fs.W]::FindWindow('Shell_TrayWnd', $null)

while ($true) {
  $handle = [Fs.W]::GetForegroundWindow()
  $rect = New-Object Fs.W+RECT
  [void][Fs.W]::GetWindowRect($handle, [ref]$rect)
  $sb = New-Object System.Text.StringBuilder 256
  [void][Fs.W]::GetClassName($handle, $sb, 256)
  $cls = $sb.ToString()

  # 真全屏窗口的矩形正好等于屏幕；被"最大化"的窗口会各边多出约 9 像素的隐形边框，
  # 所以这里用精确匹配（容差 2 像素），否则会把最大化的普通窗口误判成全屏。
  $covers = ([Math]::Abs($rect.Left) -le 2) -and ([Math]::Abs($rect.Top) -le 2) -and
            ([Math]::Abs($rect.Right - $screenW) -le 2) -and ([Math]::Abs($rect.Bottom - $screenH) -le 2)
  if ($shellClasses -contains $cls) { $covers = $false }
  $state = if ($covers) { 'FULLSCREEN' } else { 'NORMAL' }

  if ($state -ne $last) {
    Write-Output $state
    [Console]::Out.Flush()
    $last = $state
  }

  # 地面线：任务栏收起时用屏幕底边，弹出时用任务栏上沿（她就站在任务栏上）
  $ground = $screenH
  if ($taskbar -ne 0) {
    $tb = New-Object Fs.W+RECT
    [void][Fs.W]::GetWindowRect($taskbar, [ref]$tb)
    if ($tb.Top -lt ($screenH - 4)) { $ground = $tb.Top + 1 }
  }
  if ($ground -ne $lastGround) {
    Write-Output "GROUND $ground"
    [Console]::Out.Flush()
    $lastGround = $ground
  }
  Start-Sleep -Seconds 2
}
