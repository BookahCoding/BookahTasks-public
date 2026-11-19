# BookahTasks Phase 2, Build B gate. ONLY after the owner's yes. Makes two throw-away lists "bookahtest-b1" and "bookahtest-b2", runs LiveBuildBTest
# against them, then deletes both for good (X-NC-CalDAV-No-Trashbin, allowed in this script only). Prints counts and status codes only.
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\live-buildb.ps1
param([string]$Target = "BookahTasks-nextcloud")
$slugs = @("bookahtest-b1", "bookahtest-b2")
. "$PSScriptRoot\..\spike\Dav.ps1" -Target $Target
$u = $script:Cred.User
$q = '<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/></d:prop></d:propfind>'
foreach ($s in $slugs) {
    $r = Invoke-Dav "PROPFIND" "/remote.php/dav/calendars/$u/$s/" $q @{ Depth = "0" }
    if ($r.Status -ne 404) { throw "List $s already exists or unexpected answer (HTTP $($r.Status)); not touching it" }
}
$created = @()
$resultFile = Join-Path $script:PrivDir "live-buildb.txt"
if (Test-Path $resultFile) { Remove-Item $resultFile }
try {
    foreach ($s in $slugs) {
        $mk = '<?xml version="1.0"?><d:mkcol xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/"><d:set><d:prop><d:resourcetype><d:collection/><c:calendar/></d:resourcetype><d:displayname>' + $s + '</d:displayname><a:calendar-color>#2E7D5B</a:calendar-color><c:supported-calendar-component-set><c:comp name="VTODO"/></c:supported-calendar-component-set></d:prop></d:set></d:mkcol>'
        $r = Invoke-Dav "MKCOL" "/remote.php/dav/calendars/$u/$s/" $mk
        Write-Host "MKCOL ${s}: HTTP $($r.Status)"
        if ($r.Status -ne 201) { throw "List not created" }
        $created += $s
    }
    $env:BOOKAH_LIVE_SERVER = $script:Base; $env:BOOKAH_LIVE_USER = $u; $env:BOOKAH_LIVE_PASSWORD = $script:Cred.Secret
    $env:BOOKAH_LIVE_SLUG = $slugs[0]; $env:BOOKAH_LIVE_SLUG2 = $slugs[1]; $env:BOOKAH_PRIVATE_DIR = $script:PrivDir
    $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
    Push-Location "$PSScriptRoot\..\android"
    & .\gradlew.bat --offline --no-daemon -q :core-data:cleanTestDebugUnitTest :core-data:testDebugUnitTest --tests "*LiveBuildBTest" | Out-Null
    Write-Host "gradle exit code: $LASTEXITCODE"
    Pop-Location
} finally {
    foreach ($n in "BOOKAH_LIVE_SERVER", "BOOKAH_LIVE_USER", "BOOKAH_LIVE_PASSWORD", "BOOKAH_LIVE_SLUG", "BOOKAH_LIVE_SLUG2") { Remove-Item "Env:$n" -ErrorAction SilentlyContinue }
    foreach ($s in $created) {
        $r = Invoke-Dav "DELETE" "/remote.php/dav/calendars/$u/$s/" $null @{ "X-NC-CalDAV-No-Trashbin" = "1" }
        Write-Host "DELETE throw-away list $s (permanent): HTTP $($r.Status)"
    }
    $r = Invoke-Dav "PROPFIND" "/remote.php/dav/calendars/$u/" $q @{ Depth = "1" }
    Write-Host "mentions of bookahtest- left: $(([regex]::Matches($r.Body, 'bookahtest-')).Count) (expected 0)"
}
if (Test-Path $resultFile) { Get-Content $resultFile } else { Write-Host "No result file: the test did not run" }
