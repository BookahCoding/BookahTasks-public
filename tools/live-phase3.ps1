# BookahTasks Phase 3 gate (repeats and several reminders). ONLY after the owner's yes at that moment. Makes one throw-away list
# "bookahtest-p3", runs LivePhase3Test against it (one task: weekly rule, our reminders plus one "from another app", the second kind,
# a reminder edit, a Kind 2 tick, a Kind 1 tick with COUNT, the last tick), then deletes the list for good (X-NC-CalDAV-No-Trashbin,
# allowed in this script only). Prints counts and status codes only. It checks the STORED lines, not what the web app shows or rings.
# Written in Phase 3 and NOT run yet.
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\live-phase3.ps1
param([string]$Target = "BookahTasks-nextcloud")
$slug = "bookahtest-p3"
. "$PSScriptRoot\..\spike\Dav.ps1" -Target $Target
$u = $script:Cred.User
$q = '<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/></d:prop></d:propfind>'
$r = Invoke-Dav "PROPFIND" "/remote.php/dav/calendars/$u/$slug/" $q @{ Depth = "0" }
if ($r.Status -ne 404) { throw "List $slug already exists or unexpected answer (HTTP $($r.Status)); not touching it" }
$created = $false
$resultFile = Join-Path $script:PrivDir "live-phase3.txt"
if (Test-Path $resultFile) { Remove-Item $resultFile }
try {
    $mk = '<?xml version="1.0"?><d:mkcol xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/"><d:set><d:prop><d:resourcetype><d:collection/><c:calendar/></d:resourcetype><d:displayname>' + $slug + '</d:displayname><a:calendar-color>#2E7D5B</a:calendar-color><c:supported-calendar-component-set><c:comp name="VTODO"/></c:supported-calendar-component-set></d:prop></d:set></d:mkcol>'
    $r = Invoke-Dav "MKCOL" "/remote.php/dav/calendars/$u/$slug/" $mk
    Write-Host "MKCOL ${slug}: HTTP $($r.Status)"
    if ($r.Status -ne 201) { throw "List not created" }
    $created = $true
    $env:BOOKAH_LIVE_SERVER = $script:Base; $env:BOOKAH_LIVE_USER = $u; $env:BOOKAH_LIVE_PASSWORD = $script:Cred.Secret
    $env:BOOKAH_LIVE_SLUG = $slug; $env:BOOKAH_PRIVATE_DIR = $script:PrivDir
    $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
    Push-Location "$PSScriptRoot\..\android"
    & .\gradlew.bat --offline --no-daemon -q :core-data:cleanTestDebugUnitTest :core-data:testDebugUnitTest --tests "*LivePhase3Test" | Out-Null
    Write-Host "gradle exit code: $LASTEXITCODE"
    Pop-Location
} finally {
    foreach ($n in "BOOKAH_LIVE_SERVER", "BOOKAH_LIVE_USER", "BOOKAH_LIVE_PASSWORD", "BOOKAH_LIVE_SLUG") { Remove-Item "Env:$n" -ErrorAction SilentlyContinue }
    if ($created) {
        $r = Invoke-Dav "DELETE" "/remote.php/dav/calendars/$u/$slug/" $null @{ "X-NC-CalDAV-No-Trashbin" = "1" }
        Write-Host "DELETE throw-away list $slug (permanent): HTTP $($r.Status)"
    }
    $r = Invoke-Dav "PROPFIND" "/remote.php/dav/calendars/$u/" $q @{ Depth = "1" }
    Write-Host "mentions of bookahtest- left: $(([regex]::Matches($r.Body, 'bookahtest-')).Count) (expected 0)"
}
if (Test-Path $resultFile) { Get-Content $resultFile } else { Write-Host "No result file: the test did not run" }
