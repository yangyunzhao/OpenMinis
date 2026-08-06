[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+(?:\.\d+)?-preview$')]
    [string]$UpstreamTag,

    [switch]$SkipFetch,

    [switch]$AllowSourceVersionMismatch
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-Git {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    $output = & git @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') failed:`n$($output -join "`n")"
    }
    return $output
}

function Assert-Command {
    param([string]$Name)

    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command not found: $Name"
    }
}

Assert-Command git
Assert-Command gh

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$actualRoot = (Invoke-Git -C $repoRoot rev-parse --show-toplevel) -join ''
if ([IO.Path]::GetFullPath($actualRoot) -ne $repoRoot) {
    throw "Repository root mismatch. Expected '$repoRoot', got '$actualRoot'."
}

$expectedUpstream = 'https://github.com/OpenMinis/OpenMinis.git'
$upstreamFetchUrl = (Invoke-Git -C $repoRoot remote get-url upstream) -join ''
$upstreamPushUrl = (Invoke-Git -C $repoRoot remote get-url --push upstream) -join ''
if ($upstreamFetchUrl -ne $expectedUpstream) {
    throw "Unexpected upstream fetch URL: $upstreamFetchUrl"
}
if ($upstreamPushUrl -ne 'DISABLED') {
    throw "upstream push is not disabled: $upstreamPushUrl"
}

if (-not $SkipFetch) {
    Invoke-Git -C $repoRoot fetch --prune --tags upstream | Out-Null
}

$releaseJson = & gh release view $UpstreamTag --repo OpenMinis/OpenMinis --json tagName,publishedAt,url 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "Official GitHub Release '$UpstreamTag' was not found:`n$($releaseJson -join "`n")"
}
$release = ($releaseJson -join "`n") | ConvertFrom-Json
if ($release.tagName -ne $UpstreamTag) {
    throw "Official Release tag mismatch: expected '$UpstreamTag', got '$($release.tagName)'."
}

$tagCommit = (Invoke-Git -C $repoRoot rev-parse "$UpstreamTag^{}") -join ''
$remoteTagLines = Invoke-Git -C $repoRoot ls-remote --tags upstream "refs/tags/$UpstreamTag" "refs/tags/$UpstreamTag^{}"
$remotePeeledLine = $remoteTagLines | Where-Object { $_ -match '\^\{\}$' } | Select-Object -First 1
if (-not $remotePeeledLine) {
    $remotePeeledLine = $remoteTagLines | Where-Object { $_ -match "refs/tags/$([regex]::Escape($UpstreamTag))$" } | Select-Object -First 1
}
if (-not $remotePeeledLine) {
    throw "Tag '$UpstreamTag' was not found on upstream."
}
$remoteTagCommit = ($remotePeeledLine -split '\s+')[0]
if ($remoteTagCommit -ne $tagCommit) {
    throw "Local and remote tag commits differ: local=$tagCommit remote=$remoteTagCommit"
}

$buildFile = (Invoke-Git -C $repoRoot show "${tagCommit}:src/android/app/build.gradle.kts") -join "`n"
$versionCodeMatch = [regex]::Match($buildFile, 'versionCode\s*=\s*(\d+)')
$versionNameMatch = [regex]::Match($buildFile, 'versionName\s*=\s*"([^"]+)"')
if (-not $versionCodeMatch.Success -or -not $versionNameMatch.Success) {
    throw 'Could not read versionCode/versionName from the tagged Android build file.'
}
$sourceVersionCode = $versionCodeMatch.Groups[1].Value
$sourceVersionName = $versionNameMatch.Groups[1].Value
$tagVersion = ([regex]::Match($UpstreamTag, '^\d+\.\d+(?:\.\d+)?')).Value
$sourceVersion = ([regex]::Match($sourceVersionName, '^\d+\.\d+(?:\.\d+)?')).Value
if ($tagVersion -ne $sourceVersion -and -not $AllowSourceVersionMismatch) {
    throw @"
Official tag/source version mismatch detected.
Tag:                $UpstreamTag
Tagged source name: $sourceVersionName
Tagged source code: $sourceVersionCode

Stop and inspect the official Release and APK. If the mismatch is confirmed and documented,
run again with -AllowSourceVersionMismatch. Do not derive a personal release name from the
source versionName alone.
"@
}

& git -C $repoRoot merge-base --is-ancestor $tagCommit HEAD
if ($LASTEXITCODE -ne 0) {
    throw "HEAD does not contain official tag commit $tagCommit. Merge that exact commit, not the latest upstream/main."
}

$headCommit = (Invoke-Git -C $repoRoot rev-parse HEAD) -join ''
$counts = ((Invoke-Git -C $repoRoot rev-list --left-right --count "$tagCommit...HEAD") -join '') -split '\s+'

Write-Host 'Official release baseline verified:'
Write-Host "  Release:      $($release.url)"
Write-Host "  Published:    $($release.publishedAt)"
Write-Host "  Tag:          $UpstreamTag"
Write-Host "  Peeled commit: $tagCommit"
Write-Host "  Source name:  $sourceVersionName"
Write-Host "  Source code:  $sourceVersionCode"
Write-Host "  HEAD:         $headCommit"
Write-Host "  Behind/Ahead: $($counts[0])/$($counts[1])"
if ($tagVersion -ne $sourceVersion) {
    Write-Warning "Allowed documented mismatch: tag '$UpstreamTag', source versionName '$sourceVersionName'."
}
