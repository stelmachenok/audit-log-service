$ErrorActionPreference = "Stop"

function Write-HookJson {
    param(
        [string] $Decision = $null,
        [string] $Reason = $null,
        [string] $SystemMessage = $null
    )

    $output = [ordered]@{}
    if ($Decision) {
        $output.decision = $Decision
    }
    if ($Reason) {
        $output.reason = $Reason
    }
    if ($SystemMessage) {
        $output.systemMessage = $SystemMessage
    }

    if ($output.Count -gt 0) {
        $output | ConvertTo-Json -Depth 8 -Compress
    }
}

function Get-RepoRoot {
    param([object] $Payload)

    if ($Payload.cwd -and (Test-Path -LiteralPath $Payload.cwd)) {
        return (Resolve-Path -LiteralPath $Payload.cwd).Path
    }

    $scriptDir = Split-Path -Parent $PSCommandPath
    return (Resolve-Path -LiteralPath (Join-Path $scriptDir "..\..")).Path
}

function Get-CurrentTurnText {
    param([string] $TranscriptPath)

    if (-not $TranscriptPath -or -not (Test-Path -LiteralPath $TranscriptPath)) {
        return ""
    }

    $records = Get-Content -LiteralPath $TranscriptPath | ForEach-Object {
        try {
            $_ | ConvertFrom-Json
        } catch {
            $null
        }
    } | Where-Object { $null -ne $_ }

    $lastUserIndex = -1
    for ($i = $records.Count - 1; $i -ge 0; $i--) {
        $payload = $records[$i].payload
        if ($records[$i].type -eq "response_item" -and $payload.type -eq "message" -and $payload.role -eq "user") {
            $lastUserIndex = $i
            break
        }
    }

    if ($lastUserIndex -lt 0) {
        return ""
    }

    $builder = [System.Text.StringBuilder]::new()
    for ($i = $lastUserIndex + 1; $i -lt $records.Count; $i++) {
        $payload = $records[$i].payload
        if ($records[$i].type -ne "response_item" -or $payload.type -ne "function_call") {
            continue
        }

        $name = [string]$payload.name
        $arguments = [string]$payload.arguments

        if ($name -eq "apply_patch") {
            [void]$builder.AppendLine($arguments)
            continue
        }

        if ($name -eq "shell_command" -and (Test-IsWriteShellCommand -ArgumentsJson $arguments)) {
            [void]$builder.AppendLine($arguments)
        }
    }

    return $builder.ToString()
}

function Test-IsWriteShellCommand {
    param([string] $ArgumentsJson)

    try {
        $args = $ArgumentsJson | ConvertFrom-Json
        $command = [string]$args.command
    } catch {
        return $false
    }

    if ($command -notmatch "(?i)(^|[^A-Za-z0-9_.-])\.specs[\\/]") {
        return $false
    }

    return $command -match "(?i)\b(Set-Content|Add-Content|Out-File|Tee-Object|New-Item|Remove-Item|Move-Item|Copy-Item|Rename-Item|apply_patch|git\s+apply|mvn\s+.*spotless:apply)\b|>>|>"
}

function Get-TouchedSpecFeatures {
    param([string] $TurnText)

    $features = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    $matches = [regex]::Matches($TurnText, "(?i)(?:^|[^A-Za-z0-9_.-])\.specs[\\/]+([^\\/\s`"']+)[\\/]+")
    foreach ($match in $matches) {
        $feature = $match.Groups[1].Value
        if ($feature -and -not $feature.StartsWith("_")) {
            [void]$features.Add($feature)
        }
    }

    return @($features)
}

function Test-TasksHasEvalAnchors {
    param([string] $TasksPath)

    if (-not (Test-Path -LiteralPath $TasksPath -PathType Leaf)) {
        return $false
    }

    $content = Get-Content -LiteralPath $TasksPath -Raw
    return $content -match "(?i)\bRefs\b|\bDoD\b|Definition of Done"
}

function Invoke-SpecSelfEval {
    param(
        [string] $RepoRoot,
        [string] $Feature
    )

    $featureDir = Join-Path $RepoRoot ".specs\$Feature"
    if (-not (Test-Path -LiteralPath $featureDir -PathType Container)) {
        return [pscustomobject]@{
            Feature = $Feature
            Report = $null
            FailedItems = @("Feature folder does not exist: .specs/$Feature")
        }
    }

    $tasksPath = Join-Path $featureDir "tasks.md"
    if (-not (Test-TasksHasEvalAnchors -TasksPath $tasksPath)) {
        return [pscustomobject]@{
            Feature = $Feature
            Report = $null
            FailedItems = @("tasks.md must contain at least one of: Refs, DoD, or Definition of Done.")
        }
    }

    $before = @{}
    Get-ChildItem -LiteralPath $featureDir -Filter "eval-report-*.md" -File -ErrorAction SilentlyContinue | ForEach-Object {
        $before[$_.FullName] = $_.LastWriteTimeUtc
    }

    $prompt = @"
Use the spec-self-eval skill to evaluate the $Feature feature.

Run from the repository root. Evaluate .specs/$Feature/requirements.md, .specs/$Feature/design.md, and .specs/$Feature/tasks.md against the repo checklist. Write the report under .specs/$Feature/ using the skill's required report format. Do not modify requirements.md, design.md, or tasks.md.
"@

    $codex = Get-Command codex.cmd -ErrorAction SilentlyContinue
    if (-not $codex) {
        $codex = Get-Command codex -ErrorAction SilentlyContinue
    }
    if (-not $codex) {
        return [pscustomobject]@{
            Feature = $Feature
            Report = $null
            FailedItems = @("Could not find codex.cmd/codex on PATH, so spec-self-eval could not run.")
        }
    }

    $promptFile = [System.IO.Path]::GetTempFileName()
    $stdoutFile = [System.IO.Path]::GetTempFileName()
    $stderrFile = [System.IO.Path]::GetTempFileName()
    try {
        Set-Content -LiteralPath $promptFile -Value $prompt -NoNewline -Encoding UTF8

        $process = Start-Process `
            -FilePath $codex.Source `
            -ArgumentList @("exec", "--disable", "codex_hooks", "--sandbox", "workspace-write", "-C", $RepoRoot, "-") `
            -RedirectStandardInput $promptFile `
            -RedirectStandardOutput $stdoutFile `
            -RedirectStandardError $stderrFile `
            -NoNewWindow `
            -Wait `
            -PassThru

        $execOutput = @()
        if (Test-Path -LiteralPath $stdoutFile) {
            $execOutput += Get-Content -LiteralPath $stdoutFile -ErrorAction SilentlyContinue
        }
        if (Test-Path -LiteralPath $stderrFile) {
            $execOutput += Get-Content -LiteralPath $stderrFile -ErrorAction SilentlyContinue
        }

        if ($process.ExitCode -ne 0) {
            return [pscustomobject]@{
                Feature = $Feature
                Report = $null
                FailedItems = @("spec-self-eval Codex exec failed for .specs/${Feature}: $($execOutput -join ' ')")
            }
        }
    } finally {
        Remove-Item -LiteralPath $promptFile, $stdoutFile, $stderrFile -Force -ErrorAction SilentlyContinue
    }

    $report = Get-ChildItem -LiteralPath $featureDir -Filter "eval-report-*.md" -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1

    if (-not $report) {
        return [pscustomobject]@{
            Feature = $Feature
            Report = $null
            FailedItems = @("spec-self-eval completed but no eval-report-*.md was found under .specs/$Feature.")
        }
    }

    $failedItems = Get-FailedItems -ReportPath $report.FullName
    return [pscustomobject]@{
        Feature = $Feature
        Report = $report.FullName
        FailedItems = $failedItems
    }
}

function Get-FailedItems {
    param([string] $ReportPath)

    $lines = Get-Content -LiteralPath $ReportPath
    $failed = [System.Collections.Generic.List[string]]::new()

    foreach ($line in $lines) {
        if ($line -match "(?i)^\s*Overall status:\s*FAIL\s*$") {
            $failed.Add("Overall status is FAIL")
            continue
        }

        if ($line -match "\[FAIL\]\s*(.+)$") {
            $failed.Add($matches[1].Trim())
            continue
        }

        if ($line -match "^\|\s*(?<item>[^|]+?)\s*\|\s*FAIL\s*\|") {
            $failed.Add($matches["item"].Trim())
        }
    }

    return @($failed | Select-Object -Unique)
}

try {
    $stdin = [Console]::In.ReadToEnd()
    if ([string]::IsNullOrWhiteSpace($stdin)) {
        exit 0
    }

    $payload = $stdin | ConvertFrom-Json
    if ($payload.hook_event_name -ne "Stop") {
        exit 0
    }

    $repoRoot = Get-RepoRoot -Payload $payload
    $turnText = Get-CurrentTurnText -TranscriptPath ([string]$payload.transcript_path)
    $features = Get-TouchedSpecFeatures -TurnText $turnText

    if ($features.Count -eq 0) {
        exit 0
    }

    $results = foreach ($feature in $features) {
        Invoke-SpecSelfEval -RepoRoot $repoRoot -Feature $feature
    }

    $failedResults = @($results | Where-Object { $_.FailedItems.Count -gt 0 })
    if ($failedResults.Count -eq 0) {
        Write-HookJson -SystemMessage "spec-self-eval passed for touched spec feature(s): $($features -join ', ')."
        exit 0
    }

    $reasonLines = [System.Collections.Generic.List[string]]::new()
    $reasonLines.Add("spec-self-eval found FAIL items for touched spec folder(s). Fix the spec before closing the turn.")
    foreach ($result in $failedResults) {
        $reportPath = if ($result.Report) {
            Resolve-Path -LiteralPath $result.Report -Relative
        } else {
            ".specs/$($result.Feature)"
        }
        $reasonLines.Add("")
        $reasonLines.Add("Feature: $($result.Feature)")
        $reasonLines.Add("Report: $reportPath")
        foreach ($item in $result.FailedItems) {
            $reasonLines.Add("[FAIL] $item")
        }
    }

    Write-HookJson -Decision "block" -Reason ($reasonLines -join "`n")
    exit 0
} catch {
    Write-HookJson -Decision "block" -Reason "spec-self-eval Stop hook failed: $($_.Exception.Message)"
    exit 0
}
