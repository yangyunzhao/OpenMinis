[CmdletBinding()]
param(
    [ValidateRange(1, 10)]
    [int]$RetryCount = 3,

    [ValidateRange(0, 300)]
    [int]$RetryDelaySeconds = 5,

    [switch]$SkipSubmodules,

    [switch]$NoPush
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Repository-specific configuration.
$ExpectedOrigin = 'https://github.com/yangyunzhao/OpenMinis.git'
$ExpectedUpstream = 'https://github.com/OpenMinis/OpenMinis.git'
$MainBranch = 'main'
$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))

function Invoke-Git {
    param(
        [Parameter(Mandatory)]
        [string[]]$Arguments
    )

    & git @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Git command failed with exit code ${LASTEXITCODE}: git $($Arguments -join ' ')"
    }
}

function Invoke-GitWithRetry {
    param(
        [Parameter(Mandatory)]
        [string[]]$Arguments
    )

    for ($attempt = 1; $attempt -le $RetryCount; $attempt++) {
        & git @Arguments
        if ($LASTEXITCODE -eq 0) {
            return
        }

        if ($attempt -eq $RetryCount) {
            throw "Git command failed after $RetryCount attempts: git $($Arguments -join ' ')"
        }

        Write-Warning "Attempt $attempt failed. Retrying in $RetryDelaySeconds second(s)..."
        Start-Sleep -Seconds $RetryDelaySeconds
    }
}

if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    throw 'Git was not found in PATH.'
}

Push-Location -LiteralPath $RepoRoot
try {
    $actualRoot = (& git rev-parse --show-toplevel 2>$null)
    if ($LASTEXITCODE -ne 0) {
        throw "$RepoRoot is not a Git repository."
    }

    $actualRoot = [System.IO.Path]::GetFullPath(($actualRoot | Select-Object -First 1).Trim())
    if (-not $actualRoot.Equals($RepoRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Unexpected repository root: $actualRoot"
    }

    $dirtyPaths = @(& git status --porcelain)
    if ($dirtyPaths.Count -gt 0) {
        Write-Host 'The working tree contains uncommitted changes:' -ForegroundColor Yellow
        $dirtyPaths | ForEach-Object { Write-Host "  $_" }
        throw 'Commit or stash the changes before synchronizing upstream.'
    }

    $currentBranch = (& git branch --show-current).Trim()
    if ($LASTEXITCODE -ne 0 -or $currentBranch -ne $MainBranch) {
        throw "Switch to '$MainBranch' before running this script. Current branch: '$currentBranch'"
    }

    $originUrl = (& git remote get-url origin 2>$null)
    if ($LASTEXITCODE -ne 0) {
        throw "The 'origin' remote is missing."
    }

    $originUrl = ($originUrl | Select-Object -First 1).Trim()
    if ($originUrl -ne $ExpectedOrigin) {
        throw "Unexpected origin URL: $originUrl`nExpected: $ExpectedOrigin"
    }

    $remoteNames = @(& git remote)
    if ($remoteNames -notcontains 'upstream') {
        Invoke-Git -Arguments @('remote', 'add', 'upstream', $ExpectedUpstream)
    }
    else {
        Invoke-Git -Arguments @('remote', 'set-url', 'upstream', $ExpectedUpstream)
    }

    # Prevent accidental pushes to the official mirror.
    Invoke-Git -Arguments @('config', 'remote.upstream.pushurl', 'DISABLED')
    Invoke-Git -Arguments @('config', 'remote.pushDefault', 'origin')
    Invoke-Git -Arguments @('config', 'fetch.prune', 'true')

    Write-Host 'Fetching the personal fork...' -ForegroundColor Cyan
    Invoke-GitWithRetry -Arguments @(
        '-c', 'http.version=HTTP/1.1',
        'fetch', '--prune', 'origin',
        "refs/heads/${MainBranch}:refs/remotes/origin/${MainBranch}"
    )
    Invoke-Git -Arguments @('merge', '--ff-only', "origin/$MainBranch")

    Write-Host 'Fetching the official upstream...' -ForegroundColor Cyan
    Invoke-GitWithRetry -Arguments @(
        '-c', 'http.version=HTTP/1.1',
        'fetch', '--prune', 'upstream',
        "refs/heads/${MainBranch}:refs/remotes/upstream/${MainBranch}"
    )

    & git merge --no-edit "upstream/$MainBranch"
    if ($LASTEXITCODE -ne 0) {
        $conflicts = @(& git diff --name-only --diff-filter=U)
        if ($conflicts.Count -gt 0) {
            Write-Host 'Automatic merge stopped because these files conflict:' -ForegroundColor Yellow
            $conflicts | ForEach-Object { Write-Host "  $_" }
            Write-Host 'Resolve them, run tests, git add the resolved files, then git commit.' -ForegroundColor Yellow
            Write-Host 'To cancel instead, run: git merge --abort' -ForegroundColor Yellow
            throw 'Upstream merge requires manual conflict resolution.'
        }

        throw "Failed to merge upstream/$MainBranch."
    }

    if (-not $SkipSubmodules) {
        Write-Host 'Synchronizing submodules...' -ForegroundColor Cyan
        Invoke-Git -Arguments @('submodule', 'sync', '--recursive')
        Invoke-GitWithRetry -Arguments @(
            '-c', 'http.version=HTTP/1.1',
            'submodule', 'update', '--init', '--recursive', '--depth', '1'
        )
    }

    if (-not $NoPush) {
        Write-Host 'Pushing the synchronized main branch to the personal fork...' -ForegroundColor Cyan
        Invoke-GitWithRetry -Arguments @(
            '-c', 'http.version=HTTP/1.1',
            'push', 'origin', $MainBranch
        )
    }

    Write-Host 'Synchronization completed successfully.' -ForegroundColor Green
    Invoke-Git -Arguments @('status', '--short', '--branch')
}
finally {
    Pop-Location
}
