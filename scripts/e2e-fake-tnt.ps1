#requires -Version 7
<#
  Live-client end-to-end test for PrankCraft.

  Starts Paper, connects a real mineflayer client as the target, then exercises:
    * the fake-TNT world through the manual staff path (show / fire),
    * the force-fire path on the six non-TNT effects,
    * the impersonation guardrail: a fake-chat line containing "[Admin]" must reach the
      client with the bracket prefix stripped,
    * the effect-exclusion rule: a fake death/leave broadcast must never be sent to the
      player it is about,
    * the safety claim measured from the client side: health and position never change.

  Requires E:\...\mc-auto-player\node_modules (mineflayer). Exits non-zero on failure.
#>
param(
    # Workspace root, i.e. the directory that CONTAINS the repository. Empty means "derive it
    # from this script's location" - see the layout note below.
    [string]$Root = '',
    [int]$Port = 25599,
    [string]$BotName = 'PrankTarget',
    # Path to a Paper server jar. Optional - see Resolve-PaperJar below.
    [string]$PaperJar = '',
    # Optional: an explicit directory that has node_modules/mineflayer.
    [string]$BotDir = ''
)

$ErrorActionPreference = 'Continue'

# Layout is derived from this script's own location, so the harness runs from any clone:
#   RepoRoot      = <repo>            (this script lives in <repo>/scripts/)
#   WorkspaceRoot = parent of <repo>  ($Root is accepted as an alias for this)
# The test server and the mineflayer probe client normally live OUTSIDE the repository, so that a
# clone stays small and no server state is ever committed. Explicit paths always win.
$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $repoRoot) { throw 'Could not determine the repository root from $PSScriptRoot.' }
$repoRoot = (Resolve-Path $repoRoot).Path
$workspaceRoot = Split-Path -Parent $repoRoot
if ($Root) { $workspaceRoot = (Resolve-Path $Root).Path }

$pluginJar = Join-Path $repoRoot 'paper\target\PrankCraft-1.0.0.jar'
$failures = [System.Collections.Generic.List[string]]::new()

if (-not $ServerDir) {
    $ServerDir = Join-Path $workspaceRoot 'prankcraft-test-server'
}
$probeReport = Join-Path $ServerDir 'probe-report.json'

if (-not $BotDir) {
    # The probe client needs mineflayer; prefer a checkout outside the repo, then inside it.
    foreach ($candidate in @((Join-Path $workspaceRoot 'mc-auto-player'), (Join-Path $repoRoot 'mc-auto-player'))) {
        if (Test-Path (Join-Path $candidate 'node_modules\mineflayer')) { $BotDir = $candidate; break }
    }
    if (-not $BotDir) { $BotDir = Join-Path $workspaceRoot 'mc-auto-player' }
}

if (-not (Test-Path $pluginJar)) {
    throw "Built plugin not found at $pluginJar - run 'mvn clean install' first."
}

<#
  Locates the Paper server jar. The jar is deliberately NOT in this repository: it is tens of
  megabytes and belongs to PaperMC. A fresh clone supplies its own, via -PaperJar,
  $env:PRANKCRAFT_PAPER_JAR, <ServerDir>\server.jar, or any paper-*.jar under build-cache/.
#>
function Resolve-PaperJar {
    param([string]$Explicit)

    if ($Explicit) {
        if (Test-Path $Explicit) { return (Resolve-Path $Explicit).Path }
        throw "Paper jar not found at the path given: $Explicit"
    }
    if ($env:PRANKCRAFT_PAPER_JAR) {
        if (Test-Path $env:PRANKCRAFT_PAPER_JAR) { return (Resolve-Path $env:PRANKCRAFT_PAPER_JAR).Path }
        throw "PRANKCRAFT_PAPER_JAR is set but does not exist: $env:PRANKCRAFT_PAPER_JAR"
    }
    $inServerDir = Join-Path $ServerDir 'server.jar'
    if (Test-Path $inServerDir) { return (Resolve-Path $inServerDir).Path }
    foreach ($dir in @((Join-Path $workspaceRoot 'build-cache'), (Join-Path $repoRoot 'build-cache'))) {
        if (-not (Test-Path $dir)) { continue }
        $found = @(Get-ChildItem $dir -Filter 'paper-*.jar' -ErrorAction SilentlyContinue |
                   Sort-Object LastWriteTime -Descending)
        if ($found.Count -gt 0) { return $found[0].FullName }
    }
    throw (@(
        'Could not find a Paper server jar. Provide one of:',
        '  -PaperJar <path>                        (this script''s parameter)',
        '  $env:PRANKCRAFT_PAPER_JAR = <path>      (environment variable)',
        "  $inServerDir                            (copy one in yourself)",
        'Download the current build from https://papermc.io/downloads/paper'
    ) -join [Environment]::NewLine)
}

$paperJar = Resolve-PaperJar -Explicit $PaperJar
Write-Host "using Paper jar: $paperJar"

if (-not (Test-Path (Join-Path $BotDir 'node_modules\mineflayer'))) {
    throw (@(
        'The live-client probe needs mineflayer, which is not installed at:',
        "  $BotDir\node_modules\mineflayer",
        'Point -BotDir at a directory that has it, or run the smoke test instead:',
        '  pwsh -File scripts/smoke-test-paper.ps1'
    ) -join [Environment]::NewLine)
}

function Fail([string]$m) { $script:failures.Add($m); Write-Host "FAIL: $m" -ForegroundColor Red }
function Pass([string]$m) { Write-Host "ok  : $m" -ForegroundColor Green }

# ---------------------------------------------------------------- stage
# Stop a previous run of THIS harness only, identified by a marker file it writes into its own
# server directory. Deliberately not a blanket "kill java.exe running server.jar": that would
# take down an unrelated Minecraft server the operator happens to be running.
$markerFile = Join-Path $ServerDir '.prankcraft-harness.pid'
if (Test-Path $markerFile) {
    $stale = (Get-Content $markerFile -ErrorAction SilentlyContinue | Select-Object -First 1)
    if ($stale -and $stale -match '^\d+$') {
        $staleProc = Get-Process -Id ([int]$stale) -ErrorAction SilentlyContinue
        if ($staleProc -and $staleProc.ProcessName -eq 'java') {
            Write-Host "stopping stale harness server pid=$stale"
            Stop-Process -Id ([int]$stale) -Force -ErrorAction SilentlyContinue
            Start-Sleep -Seconds 2
        }
    }
    Remove-Item $markerFile -Force -ErrorAction SilentlyContinue
}
Start-Sleep -Seconds 1

# Stage the jar as <ServerDir>\server.jar. When the resolved jar IS already that file, copying it
# onto itself would fail, so the copy is skipped in that case.
$serverJar = Join-Path $ServerDir 'server.jar'
if ((Resolve-Path $paperJar).Path -ne (Join-Path (Resolve-Path $ServerDir).Path 'server.jar')) {
    Copy-Item $paperJar $serverJar -Force
}
Get-ChildItem (Join-Path $ServerDir 'plugins') -Filter '*.jar' | Remove-Item -Force
Copy-Item $pluginJar (Join-Path $ServerDir 'plugins\') -Force
Remove-Item $probeReport -Force -ErrorAction SilentlyContinue

(Get-Content (Join-Path $ServerDir 'server.properties')) `
    -replace '^server-port=.*', "server-port=$Port" `
    -replace '^online-mode=.*', 'online-mode=false' `
    -replace '^pause-when-empty-seconds=.*', 'pause-when-empty-seconds=0' `
    -replace '^spawn-protection=.*', 'spawn-protection=0' `
    | Set-Content (Join-Path $ServerDir 'server.properties')

$log = Join-Path $ServerDir 'logs\latest.log'
Remove-Item $log -Force -ErrorAction SilentlyContinue

# A hand-written config makes the run deterministic and lets the test assert on the
# impersonation guardrail: the [Admin] prefix must be stripped before broadcast.
$configDir = Join-Path $ServerDir 'plugins\PrankCraft'
New-Item -ItemType Directory -Force -Path $configDir | Out-Null
@'
consent:
  require-consent: true
  require-per-target-consent: true
audit:
  enabled: true
  console: true
  file: true
messages:
  broadcast-to-staff: true
prank-tnt:
  count: 5
  radius: 3
  fuse-ticks: 40
  prime-entity: true
  auto-detonate: false
  restore-blocks-on-detonate: true
  guard-period-ticks: 2
  tick-period-ticks: 2
pranks:
  fake-chat:
    enabled: true
    messages:
      - "[Admin] give me op now"
  fake-death:
    enabled: true
    broadcast: "&7{player} &7was blown up by a creeper"
'@ | Set-Content (Join-Path $configDir 'config.yml')

# ---------------------------------------------------------------- server
$psi = [System.Diagnostics.ProcessStartInfo]::new()
$psi.FileName = 'java'
$psi.Arguments = '-Xmx2G -Xms1G -jar server.jar nogui'
$psi.WorkingDirectory = $ServerDir
$psi.RedirectStandardInput = $true
$psi.UseShellExecute = $false
$psi.CreateNoWindow = $true
$server = [System.Diagnostics.Process]::Start($psi)
Write-Host "server pid=$($server.Id)"
# Record the pid so a later run can clean up after a crash, without guessing which java.exe is ours.
Set-Content -Path $markerFile -Value $server.Id -ErrorAction SilentlyContinue

$deadline = (Get-Date).AddSeconds(240)
$ready = $false
while ((Get-Date) -lt $deadline) {
    if ($server.HasExited) { break }
    if ((Test-Path $log) -and ((Get-Content $log -Raw -ErrorAction SilentlyContinue) -match 'Done \(')) { $ready = $true; break }
    Start-Sleep -Milliseconds 800
}
if (-not $ready) { Fail 'server did not start'; if (-not $server.HasExited) { $server.Kill() }; exit 1 }
Pass "server ready on port $Port"
Start-Sleep -Seconds 4

function Console([string]$command) {
    Write-Host "console> $command" -ForegroundColor Cyan
    $server.StandardInput.WriteLine($command)
    $server.StandardInput.Flush()
    Start-Sleep -Seconds 3
}

function New-LogCursor { if (Test-Path $log) { return (Get-Content $log).Count } else { return 0 } }
function Read-Since([int]$cursor) { return (@(Get-Content $log | Select-Object -Skip $cursor) -join "`n") }

# ---------------------------------------------------------------- client
# The probe is staged INSIDE mc-auto-player on purpose: Node resolves modules by walking up
# from the script's own directory, so a probe in prankcraft\scripts would never find mineflayer.
$nodeScript = Join-Path $BotDir 'prankcraft-live-probe.js'
Copy-Item (Join-Path $PSScriptRoot 'live-client-probe.js') $nodeScript -Force

Write-Host "connecting probe client '$BotName'..."
$botOut = Join-Path $ServerDir 'probe-stdout.txt'
$botErr = Join-Path $ServerDir 'probe-stderr.txt'
Remove-Item $botOut, $botErr -Force -ErrorAction SilentlyContinue
$bot = Start-Process -FilePath 'node' `
    -ArgumentList $nodeScript, '127.0.0.1', "$Port", '1.21.11', $BotName, '110000', $probeReport `
    -WorkingDirectory $BotDir -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput $botOut -RedirectStandardError $botErr

$deadline = (Get-Date).AddSeconds(90)
$joined = $false
while ((Get-Date) -lt $deadline) {
    if ($bot.HasExited) { break }
    if ((Get-Content $log -Raw -ErrorAction SilentlyContinue) -match "$BotName joined the game") { $joined = $true; break }
    Start-Sleep -Milliseconds 700
}
if (-not $joined) {
    Fail "probe client never joined (bot exited=$($bot.HasExited))"
    Get-Content $botErr -ErrorAction SilentlyContinue | Select-Object -First 25 | ForEach-Object { Write-Host "      $_" -ForegroundColor DarkGray }
    if (-not $server.HasExited) { $server.Kill() }
    exit 1
}
Pass "probe client joined as $BotName"
Start-Sleep -Seconds 3

# Creative + resistance removes world hazards, so any health change can only come from the
# prank. Nothing in PrankCraft forces or reads gamemode; this is the harness controlling its
# own test subject.
Console 'gamemode creative PrankTarget'
Console 'effect give PrankTarget minecraft:resistance 300 4 true'
Console '@sample before-any-effect'
Start-Sleep -Seconds 2

# ---------------------------------------------------------------- fake TNT (manual path)
$c = New-LogCursor
Console "/prankcraft tnt show $BotName"
$text = Read-Since $c
if ($text -match 'Showing (\d+) fake TNT block') { Pass "fake TNT placed client-side: $($Matches[1]) block(s)" }
else { Fail "fake TNT was not shown: $text" }

Start-Sleep -Seconds 2
$c = New-LogCursor
Console "/prankcraft tnt fire $BotName"
$text = Read-Since $c
if ($text -match 'Detonated the fake TNT') { Pass 'fake TNT detonated on command' } else { Fail "detonate failed: $text" }

Start-Sleep -Seconds 2
$c = New-LogCursor
Console '/prankcraft status'
if ((Read-Since $c) -match 'Active fake-TNT sessions: 0') { Pass 'no fake-TNT session leaked' } else { Fail 'a fake-TNT session is still active' }
Console '@sample after-fake-tnt'

# ---------------------------------------------------------------- other effects, force path
$effects = @(
    @{ id = 'jumpscare';        expect = 'Prank fired at' },
    @{ id = 'phantom-footsteps'; expect = 'Prank fired at' },
    @{ id = 'screen-shake';     expect = 'Prank fired at' },
    @{ id = 'fake-weather';     expect = 'Prank fired at' },
    @{ id = 'hotbar-shuffle';   expect = 'Prank fired at' },
    @{ id = 'arrow-rain';       expect = 'Prank fired at' },
    @{ id = 'wrong-block';      expect = 'Prank fired at' },
    @{ id = 'fake-chat';        expect = 'Prank fired at' },
    @{ id = 'fake-death';       expect = 'Prank fired at' }
)

foreach ($effect in $effects) {
    $c = New-LogCursor
    Console "/prankconsent force $BotName $($effect.id)"
    $text = Read-Since $c
    if ($text -match [regex]::Escape($effect.expect)) {
        Pass "effect '$($effect.id)' fired and was audited"
    } else {
        Fail "effect '$($effect.id)' did not fire: $text"
    }
    Start-Sleep -Seconds 2
}

Console '@sample after-all-effects'

# the impersonation guardrail: the [Admin] prefix must not survive sanitisation
$c = New-LogCursor
Console "/prankconsent force $BotName fake-chat"
Start-Sleep -Seconds 2

# ---------------------------------------------------------------- collect client report
Write-Host "waiting for the probe client to finish..."
$deadline = (Get-Date).AddSeconds(180)
while (-not $bot.HasExited -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 800 }
if (-not $bot.HasExited) { $bot.Kill() }

Get-Content $botOut -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "      [probe] $_" -ForegroundColor DarkGray }
Get-Content $botErr -ErrorAction SilentlyContinue | Select-Object -First 8 | ForEach-Object { Write-Host "      [probe-err] $_" -ForegroundColor DarkGray }

if (-not (Test-Path $probeReport)) {
    Fail 'probe client produced no report'
} else {
    $r = Get-Content $probeReport -Raw | ConvertFrom-Json
    if ($r.connected) { Pass 'probe client stayed connected' } else { Fail "probe client never connected: $($r.connectError)" }
    if ($r.peakTnt -ge 1) { Pass "client observed primed TNT entities (peak $($r.peakTnt))" } else { Fail 'client never observed a primed TNT entity' }
    if ($r.kicked) { Fail "client was kicked: $($r.endReason)" } else { Pass 'client was not kicked' }

    $chats = @($r.chat | ForEach-Object { $_.message })
    $joinedChats = $chats -join "`n"

    if ($joinedChats -match 'give me op now' -and $joinedChats -notmatch '\[Admin\]') {
        Pass 'fake-chat reached the client with the [Admin] prefix stripped'
    } elseif ($joinedChats -match '\[Admin\]') {
        Fail 'fake-chat leaked a [Admin] prefix to other players'
    } else {
        Fail "fake-chat line never reached the client: $joinedChats"
    }

    if ($joinedChats -match 'was blown up by a creeper') {
        Fail 'the fake death broadcast was shown to the player it was about'
    } else {
        Pass 'fake death broadcast excluded its own subject'
    }

    if ($r.pre) {
        $dist = [math]::Sqrt(
            [math]::Pow([double]$r.pre.x - [double]$r.post.x, 2) +
            [math]::Pow([double]$r.pre.y - [double]$r.post.y, 2) +
            [math]::Pow([double]$r.pre.z - [double]$r.post.z, 2))
        if ($null -eq $r.minHealth) {
            Fail 'probe never sampled health'
        } elseif ([double]$r.minHealth -ge 19.99) {
            Pass "target health never dropped across every effect (min $($r.minHealth) of 20)"
        } else {
            Fail "target health dropped to $($r.minHealth) during the effects"
        }
        if ($dist -lt 1.0) { Pass ("target never moved across every effect ({0:N3} blocks)" -f $dist) }
        else { Fail ("target was moved {0:N2} blocks" -f $dist) }
    } else {
        Fail 'probe report is missing the initial snapshot'
    }

    if ($r.samples) {
        $r.samples.PSObject.Properties | ForEach-Object {
            Write-Host ("      sample {0}: health={1} pos=({2},{3},{4})" -f `
                $_.Name, $_.Value.health, $_.Value.x, $_.Value.y, $_.Value.z) -ForegroundColor DarkGray
        }
    }
}

# ---------------------------------------------------------------- log assertions
$logText = Get-Content $log -Raw
if ($logText -match '(?s)\[PrankCraft\].{0,400}(Exception|Caused by)') { Fail 'PrankCraft threw during the live test' } else { Pass 'no PrankCraft exceptions during the live test' }
if ($logText -match 'was blown up by a creeper') { Pass 'the fake death message was broadcast to the server' } else { Fail 'the fake death message never appeared' }
$auditCount = ([regex]::Matches($logText, '\[audit\]')).Count
if ($auditCount -ge 10) { Pass "every effect reached the audit log ($auditCount entries)" } else { Fail "only $auditCount audit entries were written" }

# ---------------------------------------------------------------- shutdown
Write-Host ""
Console 'stop'
$deadline = (Get-Date).AddSeconds(90)
while (-not $server.HasExited -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500 }
if ($server.HasExited) { Pass 'server stopped cleanly' } else { $server.Kill(); Fail 'server did not stop' }
Remove-Item $markerFile -Force -ErrorAction SilentlyContinue

Write-Host ""
if ($failures.Count -gt 0) {
    Write-Host "LIVE E2E TEST FAILED ($($failures.Count) failure(s))" -ForegroundColor Red
    $failures | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    exit 1
}
Write-Host "LIVE E2E TEST PASSED" -ForegroundColor Green
exit 0
