#requires -Version 7
<#
  End-to-end smoke test for the PrankCraft Paper plugin.

  Starts a real Paper server against the freshly built jar, drives the console over stdin, and
  asserts on the server log. Two details matter and both bit the first version of this script:

    * Paper pauses itself after 60s with nobody online, and a paused server stops servicing its
      console, so pause-when-empty-seconds is forced to 0.
    * command feedback is asserted by polling logs/latest.log rather than by capturing the
      process streams, which deadlocks against the Minecraft console.

  Exits non-zero on any failed assertion, so it is usable as a release gate.
#>
param(
    [string]$Root = 'E:\Documents\deepseek-harness\default-workspace',
    [string]$ServerDir = '',
    [int]$Port = 25599
)

$ErrorActionPreference = 'Continue'
if (-not $ServerDir) { $ServerDir = Join-Path $Root 'prankcraft-test-server' }
$pluginJar = Join-Path $Root 'prankcraft\paper\target\PrankCraft-1.0.0.jar'
$paperJar  = Join-Path $Root 'build-cache\paper-1.21.11-132.jar'
$failures  = [System.Collections.Generic.List[string]]::new()

function Fail([string]$m) { $script:failures.Add($m); Write-Host "FAIL: $m" -ForegroundColor Red }
function Pass([string]$m) { Write-Host "ok  : $m" -ForegroundColor Green }

# ---------------------------------------------------------------- stage server
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -and $_.CommandLine -like '*server.jar nogui*' -and $_.ProcessId -notin @(16100, 11724) } |
    ForEach-Object { Write-Host "stopping stale server pid=$($_.ProcessId)"; Stop-Process -Id $_.ProcessId -Force }
Start-Sleep -Seconds 3

Copy-Item $paperJar (Join-Path $ServerDir 'server.jar') -Force
New-Item -ItemType Directory -Force -Path (Join-Path $ServerDir 'plugins') | Out-Null
Get-ChildItem (Join-Path $ServerDir 'plugins') -Filter '*.jar' | Remove-Item -Force
Copy-Item $pluginJar (Join-Path $ServerDir 'plugins\') -Force

$propsPath = Join-Path $ServerDir 'server.properties'
(Get-Content $propsPath) `
    -replace '^server-port=.*', "server-port=$Port" `
    -replace '^online-mode=.*', 'online-mode=false' `
    -replace '^pause-when-empty-seconds=.*', 'pause-when-empty-seconds=0' `
    | Set-Content $propsPath

$log = Join-Path $ServerDir 'logs\latest.log'
Remove-Item $log -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $ServerDir 'plugins\PrankCraft') -Recurse -Force -ErrorAction SilentlyContinue

# ---------------------------------------------------------------- start server
$psi = [System.Diagnostics.ProcessStartInfo]::new()
$psi.FileName = 'java'
$psi.Arguments = '-Xmx2G -Xms1G -jar server.jar nogui'
$psi.WorkingDirectory = $ServerDir
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $false
$psi.RedirectStandardError = $false
$psi.UseShellExecute = $false
$psi.CreateNoWindow = $true

$proc = [System.Diagnostics.Process]::Start($psi)
Write-Host "server pid=$($proc.Id), waiting for startup..."

$deadline = (Get-Date).AddSeconds(240)
$ready = $false
while ((Get-Date) -lt $deadline) {
    if ($proc.HasExited) { break }
    if ((Test-Path $log) -and ((Get-Content $log -Raw -ErrorAction SilentlyContinue) -match 'Done \(')) { $ready = $true; break }
    Start-Sleep -Milliseconds 800
}
if (-not $ready) {
    Fail "server did not reach 'Done' within 240s"
    if (-not $proc.HasExited) { $proc.Kill() }
    exit 1
}
Pass "server reached 'Done'"
Start-Sleep -Seconds 6

function Send-Console([string]$command) {
    Write-Host "console> $command" -ForegroundColor Cyan
    $script:logCursor = 0
    if (Test-Path $log) { $script:logCursor = (Get-Content $log).Count }
    $proc.StandardInput.WriteLine($command)
    $proc.StandardInput.Flush()
    Start-Sleep -Seconds 3
}

function Get-LogText { Get-Content $log -Raw -ErrorAction SilentlyContinue }

# Only the lines produced by the command we just sent, so assertions cannot match stale output.
function Get-NewLogLines {
    if (-not (Test-Path $log)) { return @() }
    return @(Get-Content $log | Select-Object -Skip $script:logCursor)
}

function Assert-Log([string]$label, [string]$pattern, [string]$command) {
    Send-Console $command
    $lines = Get-NewLogLines
    $joined = ($lines -join "`n")
    if ($joined -match $pattern) {
        Pass $label
        $m = [regex]::Match($joined, $pattern)
        Write-Host "      matched: $($m.Value.Trim())" -ForegroundColor DarkGray
    } else {
        Fail "$label (pattern '$pattern' not found in the output of '$command')"
        $lines | Select-Object -Last 12 | ForEach-Object { Write-Host "      $_" -ForegroundColor DarkGray }
    }
}

# ---------------------------------------------------------------- assertions
# Vanilla's /help prints a "Help: /<command>" header for a registered command. Assert on that
# header (per command, so all three are proven registered) rather than on the wrapped text.
Assert-Log '/prank is a registered command' `
    'Help: /prank' '/help prank'

Assert-Log '/prank list renders the effect table' `
    'Prank effects' '/prank list'

Assert-Log '/prankcraft is a registered command and runs' `
    'Consent gate: true' '/prankcraft status'

Assert-Log '/prankconsent is a registered command and runs' `
    'not opted in' '/prankconsent info DefinitelyNotARealPlayer'

Assert-Log '/prankcraft tnt rejects an offline target' `
    'No online player named' '/prankcraft tnt show DefinitelyNotARealPlayer'

Assert-Log '/prankcraft audit runs' `
    'No prank activity recorded|Recent prank activity' '/prankcraft audit 5'

Assert-Log '/prankcraft reload completes' `
    'Reloaded config, consent data' '/prankcraft reload'

$logText = Get-LogText

# The enable banner is printed during startup, before any command is sent, so it is asserted
# against the whole log rather than against per-command output.
$banner = [regex]::Match($logText, 'PrankCraft enabled - (\d+) prank effect\(s\) loaded')
if (-not $banner.Success) {
    Fail 'the enable banner is missing from the log'
} elseif ([int]$banner.Groups[1].Value -ne 11) {
    Fail "expected 11 loaded effects, the banner says $($banner.Groups[1].Value)"
} else {
    Pass 'all 11 configured effects are registered'
}
if ($logText -match 'No implementation found') {
    Fail 'a configured effect is missing an implementation'
} else {
    Pass 'no missing effect implementations'
}
if ($logText -match '\$\{project\.version\}') {
    Fail 'plugin.yml still contains an unfiltered ${project.version}'
} else {
    Pass 'plugin version is resolved'
}
if ($logText -match '(?s)\[PrankCraft\].{0,400}(Exception|Caused by)') {
    Fail 'the server log contains a PrankCraft stack trace'
} else {
    Pass 'no PrankCraft stack traces'
}

$configPath = Join-Path $ServerDir 'plugins\PrankCraft\config.yml'
if ((Test-Path $configPath) -and (Get-Item $configPath).Length -gt 500) {
    Pass "config.yml generated ($((Get-Item $configPath).Length) bytes)"
} else {
    Fail 'config.yml was not generated correctly'
}

# ---------------------------------------------------------------- shutdown
Write-Host ""
Send-Console 'stop'
$deadline = (Get-Date).AddSeconds(90)
while (-not $proc.HasExited -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500 }
if ($proc.HasExited) { Pass 'server stopped cleanly' } else { $proc.Kill(); Fail 'server did not stop within 90s' }

Write-Host ""
if ($failures.Count -gt 0) {
    Write-Host "SMOKE TEST FAILED ($($failures.Count) failure(s))" -ForegroundColor Red
    $failures | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    exit 1
}
Write-Host "SMOKE TEST PASSED" -ForegroundColor Green
exit 0
