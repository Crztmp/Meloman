<#
.SYNOPSIS
    Пересоздаёт Git-репозиторий и отправляет на GitHub.
    versionName и applicationId берутся из app/build.gradle (или .kts).
    URL удалённого репозитория строится как https://github.com/Crztmp/<AppName>.git,
    где <AppName> - последний сегмент applicationId.

.EXAMPLE
    .\init-repo.ps1
#>

$ErrorActionPreference = "Stop"

# ---------- 1. Поиск файла build.gradle получение versionName, AppName ----------
$gradleFile = $null
foreach ($candidate in @("app\build.gradle", "app\build.gradle.kts")) {
    if (Test-Path $candidate) {
        $gradleFile = $candidate
        break
    }
}

if (-not $gradleFile) {
    Write-Error "Не найден app\build.gradle или app\build.gradle.kts в каталоге $(Get-Location)"
    exit 1
}

Write-Host "==> Использую: $gradleFile" -ForegroundColor Cyan
$content = Get-Content -Raw -Path $gradleFile

$versionMatch = [regex]::Match($content, 'versionName\s*=?\s*["'']([^"'']+)["'']')
if (-not $versionMatch.Success) {
    Write-Error "Не удалось найти versionName в $gradleFile"
    exit 1
}
$versionName = $versionMatch.Groups[1].Value

$appIdMatch = [regex]::Match($content, 'applicationId\s*=?\s*["'']([^"'']+)["'']')
if (-not $appIdMatch.Success) {
    Write-Error "Не удалось найти applicationId в $gradleFile"
    exit 1
}
$applicationId = $appIdMatch.Groups[1].Value
$appName = ($applicationId -split '\.')[-1]
$remoteUrl = "https://github.com/Crztmp/$appName.git"

# ---------- 5. Параметры ----------
$commitMessage = $versionName
$branch = "main"

Write-Host ""
Write-Host "==> applicationId : $applicationId" -ForegroundColor Cyan
Write-Host "==> AppName       : $appName" -ForegroundColor Cyan
Write-Host "==> versionName   : $versionName" -ForegroundColor Cyan
Write-Host "==> Remote URL    : $remoteUrl" -ForegroundColor Cyan
Write-Host "==> Branch        : $branch" -ForegroundColor Cyan
Write-Host "==> Commit        : $commitMessage" -ForegroundColor Cyan
Write-Host ""

# ---------- 7. Пересоздание репозитория ----------
if (Test-Path ".git") {
    Write-Host "==> Удаляю .git" -ForegroundColor Yellow
    Remove-Item -Recurse -Force ".git"
}

Write-Host "==> git init" -ForegroundColor Green
git init | Out-Null

Write-Host "==> git checkout -b $branch" -ForegroundColor Green
git checkout -b $branch | Out-Null

Write-Host "==> git add -A" -ForegroundColor Green
git add -A

Write-Host "==> git commit -m `"$commitMessage`"" -ForegroundColor Green
git commit -m "$commitMessage"

# ---------- 8. Remote + push ----------
$existing = git remote
if ($existing -contains "origin") {
    Write-Host "==> Удаляю существующий remote origin" -ForegroundColor Yellow
    git remote remove origin
}

Write-Host "==> git remote add origin $remoteUrl" -ForegroundColor Green
git remote add origin $remoteUrl

Write-Host "==> git push --force --set-upstream origin $branch" -ForegroundColor Green
git push --force --set-upstream origin $branch

# ---------- 9. Test ----------
Write-Host ""
Write-Host "==> Готово. Проверка:" -ForegroundColor Cyan
git log --oneline -3
git remote -v
git status