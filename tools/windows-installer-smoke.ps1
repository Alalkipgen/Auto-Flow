<#
.SYNOPSIS
  CI only (Q-01 / D-65 / D-66): silently install the real, unsigned Auto Flow EXE installer on a disposable
  GitHub-hosted Windows runner, launch the INSTALLED app, close it, uninstall it, and check that the app is gone
  while user data (~/.autoflow) is untouched. Writes report.json, msiexec logs and a screenshot to -OutDir.

  The EXE is jpackage's MSI wrapper: it extracts its embedded MSI and runs `msiexec /i <msi> <args>`, forwarding
  every argument (OpenJDK src/jdk.jpackage/windows/native/msiwrapper/MsiWrapper.cpp), so standard Windows Installer
  options (/qn /norestart /l*v) are used here.

  It refuses to run anywhere but a GitHub-hosted runner with AUTOFLOW_DISPOSABLE_RUNNER=1, because it installs and
  removes software for the current user. Never run it on the owner's PC.
.EXAMPLE
  pwsh -File tools/windows-installer-smoke.ps1 -Exe AutoFlow-0.4.2-abc1234-unsigned-setup.exe -OutDir out -ExpectedVersion 0.4.2
#>
param(
    [Parameter(Mandatory = $true)] [string] $Exe,
    [Parameter(Mandatory = $true)] [string] $OutDir,
    [Parameter(Mandatory = $true)] [string] $ExpectedVersion,
    [int] $AliveSeconds = 25,
    [int] $WindowTimeoutSeconds = 120,
    [int] $MsiTimeoutSeconds = 600
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($env:GITHUB_ACTIONS -ne 'true' -or $env:RUNNER_ENVIRONMENT -ne 'github-hosted' -or $env:AUTOFLOW_DISPOSABLE_RUNNER -ne '1' -or -not $IsWindows) {
    [Console]::Error.WriteLine('Refusing to run: this script installs and uninstalls Auto Flow and is only for disposable GitHub-hosted Windows CI runners.')
    exit 2
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$OutDir = (Resolve-Path $OutDir).Path
$Exe = (Resolve-Path $Exe).Path
$report = [ordered]@{ result = 'FAILED'; failure = $null; sourceSha = $env:GITHUB_SHA; runUrl = "$env:GITHUB_SERVER_URL/$env:GITHUB_REPOSITORY/actions/runs/$env:GITHUB_RUN_ID"; steps = [ordered]@{} }
$WindowTitle = 'Auto Flow'
$state = @{ productCode = $null; installAttempted = $false; uninstalled = $false; proc = $null; installDir = $null }

function Prop($obj, [string] $name) {
    if ($null -eq $obj) { return $null }
    $p = $obj.PSObject.Properties[$name]
    if ($null -eq $p) { return $null } else { return $p.Value }
}

function Save-Report {
    $report | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 (Join-Path $OutDir 'report.json')
}

function Get-AutoFlowUninstallEntries {
    $roots = @(
        'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall',
        'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall',
        'HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall'
    )
    $found = @()
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        foreach ($key in Get-ChildItem $root -ErrorAction SilentlyContinue) {
            $p = Get-ItemProperty -Path $key.PSPath -ErrorAction SilentlyContinue
            $name = Prop $p 'DisplayName'
            if ($name -and ($name -like 'AutoFlow*' -or $name -like 'Auto Flow*')) {
                $found += [pscustomobject][ordered]@{
                    hive = $root; key = $key.PSChildName; displayName = $name
                    displayVersion = Prop $p 'DisplayVersion'; publisher = Prop $p 'Publisher'
                    installLocation = Prop $p 'InstallLocation'; uninstallString = Prop $p 'UninstallString'
                    windowsInstaller = Prop $p 'WindowsInstaller'
                }
            }
        }
    }
    return $found # unrolled; every caller wraps the call in @(...)
}

function Invoke-Msi([string] $file, [string[]] $arguments, [string] $what) {
    $p = Start-Process -FilePath $file -ArgumentList $arguments -PassThru -WindowStyle Hidden
    $null = $p.Handle # cache the handle, or ExitCode can read as $null after the wait
    if (-not $p.WaitForExit($MsiTimeoutSeconds * 1000)) {
        Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
        throw "$what did not finish within $MsiTimeoutSeconds s"
    }
    $code = $p.ExitCode
    # 0 = success, 3010 = success but a reboot is required (recorded, never forced).
    if ($code -ne 0 -and $code -ne 3010) { throw "$what failed with exit code $code" }
    return $code
}

Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;
public static class AfWindows {
    delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);
    [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc f, IntPtr l);
    [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("user32.dll")] static extern bool PostMessage(IntPtr h, uint msg, IntPtr w, IntPtr l);
    public static List<string> Visible() {
        var result = new List<string>();
        EnumWindows((h, l) => {
            if (!IsWindowVisible(h)) return true;
            var sb = new StringBuilder(512);
            GetWindowText(h, sb, 512);
            if (sb.Length == 0) return true;
            uint pid; GetWindowThreadProcessId(h, out pid);
            result.Add(pid + "\t" + sb.ToString());
            return true;
        }, IntPtr.Zero);
        return result;
    }
    // WM_CLOSE to every visible top-level window of `pid` titled `title` (what the title-bar X sends).
    public static int Close(uint pid, string title) {
        int sent = 0;
        EnumWindows((h, l) => {
            if (!IsWindowVisible(h)) return true;
            uint owner; GetWindowThreadProcessId(h, out owner);
            if (owner != pid) return true;
            var sb = new StringBuilder(512);
            GetWindowText(h, sb, 512);
            if (sb.ToString() == title && PostMessage(h, 0x0010, IntPtr.Zero, IntPtr.Zero)) sent++;
            return true;
        }, IntPtr.Zero);
        return sent;
    }
}
'@

function Get-VisibleWindows {
    return @([AfWindows]::Visible() | ForEach-Object { $pid_, $title = $_ -split "`t", 2; [pscustomobject]@{ pid = [int]$pid_; title = $title } })
}

function Save-Screenshot([string] $path) {
    try {
        Add-Type -AssemblyName System.Windows.Forms, System.Drawing
        $b = [System.Windows.Forms.SystemInformation]::VirtualScreen
        $bmp = New-Object System.Drawing.Bitmap $b.Width, $b.Height
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.CopyFromScreen($b.Left, $b.Top, 0, 0, $bmp.Size)
        $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
        $g.Dispose(); $bmp.Dispose()
        return "$($b.Width)x$($b.Height)"
    } catch {
        return "screenshot failed: $($_.Exception.Message)"
    }
}

function Get-ZipEntries([string] $jar) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $z = [System.IO.Compression.ZipFile]::OpenRead($jar)
    try { return @($z.Entries | ForEach-Object { $_.FullName }) } finally { $z.Dispose() }
}

$dataDir = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.autoflow'
$sentinel = Join-Path $dataDir 'installer-smoke-sentinel.txt'
$shortcutRoots = @('Programs', 'CommonPrograms', 'Desktop', 'CommonDesktopDirectory') | ForEach-Object { [Environment]::GetFolderPath($_) } | Where-Object { $_ -and (Test-Path $_) }

function Get-AutoFlowShortcuts {
    return @(Get-ChildItem -Path $shortcutRoots -Filter '*.lnk' -Recurse -Depth 2 -ErrorAction SilentlyContinue |
        Where-Object { $_.BaseName -like 'AutoFlow*' -or $_.BaseName -like 'Auto Flow*' } | ForEach-Object { $_.FullName })
}

try {
    # 1. The artifact itself: a PE file, its SHA-256, and its (absent) Authenticode signature.
    $bytes = [System.IO.File]::ReadAllBytes($Exe)
    if ($bytes.Length -lt 1MB -or $bytes[0] -ne 0x4D -or $bytes[1] -ne 0x5A) { throw "not a plausible PE installer: $Exe ($($bytes.Length) bytes)" }
    $installerSha = (Get-FileHash $Exe -Algorithm SHA256).Hash.ToLowerInvariant()
    $sig = Get-AuthenticodeSignature $Exe
    $report.steps.artifact = [ordered]@{ file = (Split-Path $Exe -Leaf); bytes = $bytes.Length; sha256 = $installerSha; authenticode = "$($sig.Status)" }
    $bytes = $null

    # 2. Clean starting point, plus a user-data sentinel that uninstall must not touch.
    $before = @(Get-AutoFlowUninstallEntries)
    if ($before.Count -ne 0) { throw "Auto Flow is already installed on this runner: $($before | ConvertTo-Json -Compress)" }
    New-Item -ItemType Directory -Force -Path $dataDir | Out-Null
    Set-Content -Path $sentinel -Value "installer smoke $([guid]::NewGuid())" -Encoding utf8
    $sentinelSha = (Get-FileHash $sentinel -Algorithm SHA256).Hash

    # 3. Silent per-user install of the EXE (no UAC prompt with perUserInstall).
    $installLog = Join-Path $OutDir 'install.log'
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $state.installAttempted = $true
    $code = Invoke-Msi $Exe @('/qn', '/norestart', '/l*v', "`"$installLog`"") 'install'
    $report.steps.install = [ordered]@{ exitCode = $code; rebootRequired = ($code -eq 3010); seconds = [math]::Round($sw.Elapsed.TotalSeconds, 1) }

    # 4. Windows registered exactly one product with the expected version.
    $entries = @(Get-AutoFlowUninstallEntries)
    if ($entries.Count -ne 1) { throw "expected one Auto Flow uninstall entry after install, found $($entries.Count)" }
    $entry = $entries[0]
    if ($entry.key -notmatch '^\{[0-9A-Fa-f-]{36}\}$') { throw "uninstall entry key is not an MSI product code: $($entry.key)" }
    $state.productCode = $entry.key
    if ($entry.displayVersion -ne $ExpectedVersion) { throw "installed version '$($entry.displayVersion)' != expected '$ExpectedVersion'" }
    $report.steps.registry = $entry

    # 5. The installed files: launcher, app config, bundled runtime, jars and bundled resources.
    $candidates = @()
    if ($entry.installLocation) { $candidates += $entry.installLocation.TrimEnd('\') }
    $candidates += (Join-Path $env:LOCALAPPDATA 'AutoFlow')
    $installDir = $candidates | Where-Object { Test-Path (Join-Path $_ 'AutoFlow.exe') } | Select-Object -First 1
    $foundBy = if ($installDir -and $entry.installLocation) { 'registry InstallLocation' } elseif ($installDir) { 'LocalAppData default' } else { $null }
    if (-not $installDir) {
        $installDir = Get-ChildItem -Path @($env:LOCALAPPDATA, $env:ProgramFiles) -Filter 'AutoFlow.exe' -Recurse -Depth 3 -ErrorAction SilentlyContinue |
            Where-Object { Test-Path (Join-Path $_.DirectoryName 'app\AutoFlow.cfg') } | Select-Object -First 1 | ForEach-Object { $_.DirectoryName }
        $foundBy = 'search'
    }
    if (-not $installDir) { throw "installed AutoFlow.exe not found (tried $($candidates -join ', ') and a search of LocalAppData/Program Files)" }
    $state.installDir = $installDir
    $launcher = Join-Path $installDir 'AutoFlow.exe'
    $launcherSha = (Get-FileHash $launcher -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($launcherSha -eq $installerSha) { throw 'the installed launcher is the installer itself' }
    $cfgPath = Join-Path $installDir 'app\AutoFlow.cfg'
    if (-not (Test-Path $cfgPath)) { throw "missing $cfgPath" }
    $cfg = Get-Content $cfgPath -Raw
    if ($cfg -notmatch 'app\.mainclass=io\.github\.alalkipgen\.autoflow\.desktop\.MainKt') { throw 'AutoFlow.cfg has the wrong main class' }
    $releaseFile = Join-Path $installDir 'runtime\release'
    if (-not (Test-Path $releaseFile) -or -not (Test-Path (Join-Path $installDir 'runtime\lib\modules'))) { throw 'the bundled Java runtime is missing' }
    $javaVersion = (Select-String -Path $releaseFile -Pattern '^JAVA_VERSION=' | Select-Object -First 1).Line
    $jars = @(Get-ChildItem (Join-Path $installDir 'app') -Filter *.jar)
    $swingJar = $jars | Where-Object { $_.Name -like 'kotlinx-coroutines-swing*' } | Select-Object -First 1
    if (-not $swingJar) { throw 'kotlinx-coroutines-swing (Dispatchers.Main, D-61) is not installed' }
    $mainJar = $null; $resources = @()
    foreach ($j in $jars) {
        $names = Get-ZipEntries $j.FullName
        if ($names -contains 'io/github/alalkipgen/autoflow/desktop/MainKt.class') { $mainJar = $j.Name }
        $resources += @($names | Where-Object { $_ -eq 'config/models.json' -or $_ -eq 'config/features.json' -or $_ -like 'config/fonts/*.ttf' })
    }
    if (-not $mainJar) { throw 'no installed jar contains the desktop MainKt class' }
    foreach ($r in 'config/models.json', 'config/features.json') { if ($resources -notcontains $r) { throw "bundled resource missing: $r" } }
    $fonts = @($resources | Where-Object { $_ -like 'config/fonts/*.ttf' })
    if ($fonts.Count -eq 0) { throw 'no built-in Burmese font (config/fonts/*.ttf, D-49) is installed' }
    $report.steps.files = [ordered]@{
        installDir = $installDir; launcherSha256 = $launcherSha; runtime = $javaVersion; jarCount = $jars.Count
        foundBy = $foundBy; mainJar = $mainJar; coroutinesSwing = $swingJar.Name; fonts = $fonts; shortcuts = @(Get-AutoFlowShortcuts)
    }
    if (-not (Get-AutoFlowShortcuts | Where-Object { $_ -like "$([Environment]::GetFolderPath('Programs'))*" })) { throw 'no per-user Start menu shortcut was created' }

    # 6. Launch the INSTALLED app (fresh user data only has the sentinel) and require its window to stay up.
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $appOut = Join-Path $OutDir 'app-stdout.log'; $appErr = Join-Path $OutDir 'app-stderr.log'
    $proc = Start-Process -FilePath $launcher -WorkingDirectory $installDir -PassThru -RedirectStandardOutput $appOut -RedirectStandardError $appErr
    $null = $proc.Handle
    $state.proc = $proc
    $windowPid = $null
    while ($sw.Elapsed.TotalSeconds -lt $WindowTimeoutSeconds) {
        Start-Sleep -Milliseconds 500
        if ($proc.HasExited) { throw "the installed app exited early with code $($proc.ExitCode)" }
        $w = Get-VisibleWindows
        $errorWindow = $w | Where-Object { $_.title -eq 'Error' }
        if ($errorWindow) { throw 'an "Error" window appeared' }
        $main = $w | Where-Object { $_.title -eq $WindowTitle } | Select-Object -First 1
        if ($main) { $windowPid = $main.pid; break }
    }
    if (-not $windowPid) { throw "the '$WindowTitle' window did not appear within $WindowTimeoutSeconds s; visible: $((Get-VisibleWindows | ForEach-Object { $_.title }) -join ' | ')" }
    $windowSeconds = [math]::Round($sw.Elapsed.TotalSeconds, 1)
    $windowExe = (Get-Process -Id $windowPid).Path
    if ($windowExe -ne $launcher) { throw "the '$WindowTitle' window belongs to $windowExe, not the installed launcher" }
    for ($i = 0; $i -lt $AliveSeconds; $i++) {
        Start-Sleep -Seconds 1
        if ($proc.HasExited) { throw "the installed app exited after its window appeared (code $($proc.ExitCode))" }
        if (Get-VisibleWindows | Where-Object { $_.title -eq 'Error' }) { throw 'an "Error" window appeared after start' }
    }
    $screen = Save-Screenshot (Join-Path $OutDir 'installed-app.png')
    $report.steps.launch = [ordered]@{
        pid = $proc.Id; windowTitle = $WindowTitle; windowAfterSeconds = $windowSeconds; aliveSeconds = $AliveSeconds
        windowOwner = $windowExe; screenshot = $screen; visibleWindows = @(Get-VisibleWindows | ForEach-Object { $_.title })
        userDataEntries = @(Get-ChildItem $dataDir -Force | ForEach-Object { $_.Name })
    }

    # 7. Close it like a user would (WM_CLOSE to the titled window, as the title-bar X does), then look for
    #    startup/shutdown exceptions. Process.CloseMainWindow() is not used: its "main window" guess is not
    #    guaranteed to be the Compose frame (the first CI run, 6980992, timed out with it).
    $closeSent = [AfWindows]::Close([uint32]$proc.Id, $WindowTitle)
    $graceful = $proc.WaitForExit(30000)
    if (-not $graceful) { Stop-Process -Id $proc.Id -Force; $proc.WaitForExit(10000) | Out-Null }
    $report.steps.close = [ordered]@{
        wmCloseSentTo = $closeSent; graceful = $graceful
        exitCode = $(if ($proc.HasExited) { $proc.ExitCode } else { $null })
    }
    if (-not $graceful) { Write-Output '::warning title=Windows installer smoke::the installed app did not exit within 30 s of closing its window' }
    $appLog = (@($appOut, $appErr) | Where-Object { Test-Path $_ } | ForEach-Object { Get-Content $_ -Raw }) -join "`n"
    if ($appLog -match 'Main dispatcher is missing|Exception in thread|Could not create instance') { throw 'the installed app logged a startup exception (see app-stderr.log)' }

    # 8. Uninstall by product code (what Settings > Apps runs) and check what is left.
    $uninstallLog = Join-Path $OutDir 'uninstall.log'
    $code = Invoke-Msi 'msiexec.exe' @('/x', $state.productCode, '/qn', '/norestart', '/l*v', "`"$uninstallLog`"") 'uninstall'
    $state.uninstalled = $true
    $after = @(Get-AutoFlowUninstallEntries)
    # @(...) at the call site: PowerShell unrolls a returned empty/one-item array to $null/a scalar, and
    # StrictMode then rejects .Count on it (the first CI run failed exactly here, after a clean uninstall).
    $shortcutsLeft = @(Get-AutoFlowShortcuts)
    $left = @()
    if (Test-Path $installDir) { $left = @(Get-ChildItem $installDir -Recurse -Force | ForEach-Object { $_.FullName }) }
    $sentinelAfter = if (Test-Path $sentinel) { (Get-FileHash $sentinel -Algorithm SHA256).Hash } else { $null }
    $report.steps.uninstall = [ordered]@{
        exitCode = $code; rebootRequired = ($code -eq 3010); registryEntriesLeft = $after.Count
        launcherLeft = (Test-Path $launcher); filesLeftInInstallDir = $left.Count
        shortcutsLeft = $shortcutsLeft
        userDataKept = (Test-Path $dataDir); sentinelUnchanged = ($sentinelAfter -eq $sentinelSha)
    }
    if ($after.Count -ne 0) { throw 'the uninstall entry is still registered' }
    if (Test-Path $launcher) { throw 'the installed launcher is still present after uninstall' }
    if ($left | Where-Object { $_ -like '*\runtime\*' -or $_ -like '*\app\*.jar' }) { throw "installed files remain: $($left -join ', ')" }
    if ($shortcutsLeft.Count -ne 0) { throw "shortcuts are still present after uninstall: $($shortcutsLeft -join ', ')" }
    if ($sentinelAfter -ne $sentinelSha) { throw 'uninstall changed or removed user data (~/.autoflow sentinel)' }

    $report.result = 'PASSED'
} catch {
    $report.failure = "$($_.Exception.Message)"
    Write-Output "::error title=Windows installer smoke::$($report.failure)"
} finally {
    # Never leave the app running or installed on the runner, whatever failed above (user data is not touched).
    if ($state.proc -and -not $state.proc.HasExited) { Stop-Process -Id $state.proc.Id -Force -ErrorAction SilentlyContinue }
    if ($state.installAttempted -and -not $state.uninstalled) {
        $code = $state.productCode
        if (-not $code) { $e = @(Get-AutoFlowUninstallEntries); if ($e.Count -eq 1) { $code = $e[0].key } }
        if ($code) {
            try { $report.cleanupUninstallExit = Invoke-Msi 'msiexec.exe' @('/x', $code, '/qn', '/norestart', '/l*v', "`"$(Join-Path $OutDir 'cleanup-uninstall.log')`"") 'cleanup uninstall' }
            catch { $report.cleanupUninstallExit = "$($_.Exception.Message)" }
        }
    }
    Save-Report
}
Get-Content (Join-Path $OutDir 'report.json')
if ($report.result -ne 'PASSED') { exit 1 }
Write-Output "Windows installer smoke passed: installed $ExpectedVersion, launched the installed app ($AliveSeconds s with its window), uninstalled cleanly; user data kept."
