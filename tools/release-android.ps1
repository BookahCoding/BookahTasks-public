<#
.SYNOPSIS
  Builds, checks and (only when told to) publishes a BookahTasks test or release build.

.DESCRIPTION
  Run on the build PC in Windows PowerShell 5.1 from anywhere:
    powershell -File D:\Projects\BookahTasks\tools\release-android.ps1 -Version 0.1.0

  Without -Publish it only builds and checks (safe to run any time):
    1. runs the JVM tests of the three modules,
    2. builds the minified release APK with the version number (versionCode = major*10000 + minor*100 + patch),
    3. checks that the APK is signed with the release key (its certificate SHA-256 must equal android\release-signing-cert.sha256),
    4. copies it to BookahTasks-<version>.apk next to the build output and writes its SHA-256.

  With -Publish -OwnerApproved it also pushes the commits and creates the GitHub pre-release v<version> with the APK.
  Pushing and publishing need the owner's explicit yes every time (or a standing yes he has given): use -OwnerApproved only after he has said it.

  The signing key is outside Git: the private folder (BOOKAH_PRIVATE_DIR)\signing\ (and android\keystore.properties, which is not committed).
  Creating that key needs the owner's yes, and he must back the folder up. The certificate fingerprint (not secret) is committed in
  android\release-signing-cert.sha256.
#>
param(
    [Parameter(Mandatory = $true)][ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version,
    [string]$Notes = "",
    [switch]$Publish,
    [switch]$OwnerApproved,
    [switch]$SkipTests,
    # a real release (not marked as a pre-release); without it every release is a test pre-release
    [switch]$Final,
    # the GitHub repository whose releases the app looks at for updates (owner/name); empty means the app never looks for updates
    # (it must stay empty while the repository is private)
    [string]$ReleasesRepo = ""
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$android = Join-Path $repo "android"
$jbr = "C:\Program Files\Android\Android Studio\jbr"
$buildToolsRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk\build-tools"
$buildTools = (Get-ChildItem $buildToolsRoot -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
if (-not (Test-Path (Join-Path $android "keystore.properties"))) { throw "android\keystore.properties is missing: the release key is not set up on this PC (the owner must say yes to creating it first)." }
if (-not (Test-Path (Join-Path $android "release-signing-cert.sha256"))) { throw "android\release-signing-cert.sha256 is missing." }
$env:JAVA_HOME = $jbr
# the test seeder and the all-ABI emulator build must never end up in a build that is handed to the phone
foreach ($bad in @("ORG_GRADLE_PROJECT_seedRelease", "ORG_GRADLE_PROJECT_allAbis")) {
    if (Test-Path "Env:$bad") { throw "$bad is set in this PowerShell session: remove it (Remove-Item Env:$bad) before making a release build." }
}

Push-Location $android
try {
    if (-not $SkipTests) {
        Write-Host "== unit tests"
        & .\gradlew.bat --offline --no-daemon :core-data:testDebugUnitTest :design:testDebugUnitTest :app:testDebugUnitTest
        if ($LASTEXITCODE -ne 0) { throw "unit tests failed" }
    }
    Write-Host "== building $Version"
    # handed over as an environment variable: the "=" in a command-line argument is split by cmd.exe when gradlew.bat is started
    if ($ReleasesRepo) { $env:ORG_GRADLE_PROJECT_releasesRepo = $ReleasesRepo } else { Remove-Item Env:ORG_GRADLE_PROJECT_releasesRepo -ErrorAction SilentlyContinue }
    # --offline: a missing cached dependency fails loudly instead of downloading anything without the owner's yes
    & .\gradlew.bat --offline --no-daemon :app:assembleRelease "-PappVersion=$Version"
    if ($LASTEXITCODE -ne 0) { throw "build failed" }

    $built = Join-Path $android "app\build\outputs\apk\release\app-release.apk"
    $apk = Join-Path $android "app\build\outputs\apk\release\BookahTasks-$Version.apk"
    Copy-Item $built $apk -Force

    Write-Host "== checking the signature"
    $certs = & "$buildTools\apksigner.bat" verify --print-certs $apk 2>&1 | Out-String
    $expected = (Get-Content (Join-Path $android "release-signing-cert.sha256") -Raw).Trim().ToLower()
    if ($certs -notmatch [regex]::Escape($expected)) { throw "the APK is NOT signed with the release key (expected certificate $expected)" }
    $hash = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()
    Set-Content -Path "$apk.sha256" -Value "$hash  BookahTasks-$Version.apk"
    Write-Host ("OK: {0} ({1:N1} MB), sha256 {2}" -f $apk, ((Get-Item $apk).Length / 1MB), $hash)

    if (-not $Publish) { Write-Host "Not published (no -Publish)."; return }
    if (-not $OwnerApproved) { throw "Publishing needs the owner's explicit yes: run again with -Publish -OwnerApproved after he has said it." }
    $dirty = git -C $repo status --porcelain
    if ($dirty) { throw "Uncommitted changes: commit first, or the release tag would not contain what the APK was built from.`n$dirty" }

    Write-Host "== pushing and publishing v$Version"
    Push-Location $repo
    try {
        git push
        if ($LASTEXITCODE -ne 0) { throw "git push failed" }
        $cert = $expected
        $body = if ($Notes) { "$Notes Signing certificate SHA-256: $cert" } else { "Test build $Version. Signing certificate SHA-256: $cert" }
        $kind = if ($Final) { "--latest" } else { "--prerelease" }
        gh release create "v$Version" $apk $kind --target main --title "BookahTasks $Version" --notes $body
        if ($LASTEXITCODE -ne 0) { throw "gh release create failed" }
    } finally { Pop-Location }
} finally { Pop-Location }
