# BookahTasks Phase 5 gate. ONLY after the owner's yes at that moment. Written in Phase 5 and NOT run yet.
# (a) Makes one throw-away list "bookahtest-p5", runs LivePhase5Test.writesOnAThrowAwayList against it (move all overdue tasks to
#     Tomorrow and undo, Waiting for and ending it, a shared link in URL), then deletes the list for good (X-NC-CalDAV-No-Trashbin,
#     allowed in this script only).
# (b) READ ONLY against the real calendars: LivePhase5Test.eventCalendarsAreOnlyRead counts event calendars, events and today's
#     instances through an API whose writes throw. Nothing in (b) can change a calendar.
# Prints counts and status codes only (never the server address, a password, a task's or an event's text).
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\live-phase5.ps1            (both parts)
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\live-phase5.ps1 -EventsOnly (part b only, read only)
param([string]$Target = "BookahTasks-nextcloud", [switch]$EventsOnly)
$slug = "bookahtest-p5"
. "$PSScriptRoot\..\spike\Dav.ps1" -Target $Target
$u = $script:Cred.User
$q = '<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/></d:prop></d:propfind>'
$created = $false
foreach ($f in "live-phase5.txt", "live-phase5-events.txt") { $p = Join-Path $script:PrivDir $f; if (Test-Path $p) { Remove-Item $p } }
try {
    if (-not $EventsOnly) {
        $r = Invoke-Dav "PROPFIND" "/remote.php/dav/calendars/$u/$slug/" $q @{ Depth = "0" }
        if ($r.Status -ne 404) { throw "List $slug already exists or unexpected answer (HTTP $($r.Status)); not touching it" }
        $mk = '<?xml version="1.0"?><d:mkcol xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/"><d:set><d:prop><d:resourcetype><d:collection/><c:calendar/></d:resourcetype><d:displayname>' + $slug + '</d:displayname><a:calendar-color>#2E7D5B</a:calendar-color><c:supported-calendar-component-set><c:comp name="VTODO"/></c:supported-calendar-component-set></d:prop></d:set></d:mkcol>'
        $r = Invoke-Dav "MKCOL" "/remote.php/dav/calendars/$u/$slug/" $mk
        Write-Host "MKCOL ${slug}: HTTP $($r.Status)"
        if ($r.Status -ne 201) { throw "List not created" }
        $created = $true
        $env:BOOKAH_LIVE_SLUG = $slug
    }
    $env:BOOKAH_LIVE_EVENTS = "1"
    $env:BOOKAH_LIVE_SERVER = $script:Base; $env:BOOKAH_LIVE_USER = $u; $env:BOOKAH_LIVE_PASSWORD = $script:Cred.Secret
    $env:BOOKAH_PRIVATE_DIR = $script:PrivDir
    $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
    Push-Location "$PSScriptRoot\..\android"
    & .\gradlew.bat --offline --no-daemon -q :core-data:cleanTestDebugUnitTest :core-data:testDebugUnitTest --tests "*LivePhase5Test" | Out-Null
    Write-Host "gradle exit code: $LASTEXITCODE"
    Pop-Location
} finally {
    foreach ($n in "BOOKAH_LIVE_SERVER", "BOOKAH_LIVE_USER", "BOOKAH_LIVE_PASSWORD", "BOOKAH_LIVE_SLUG", "BOOKAH_LIVE_EVENTS") { Remove-Item "Env:$n" -ErrorAction SilentlyContinue }
    if ($created) {
        $r = Invoke-Dav "DELETE" "/remote.php/dav/calendars/$u/$slug/" $null @{ "X-NC-CalDAV-No-Trashbin" = "1" }
        Write-Host "DELETE throw-away list $slug (permanent): HTTP $($r.Status)"
    }
    $r = Invoke-Dav "PROPFIND" "/remote.php/dav/calendars/$u/" $q @{ Depth = "1" }
    Write-Host "mentions of bookahtest- left: $(([regex]::Matches($r.Body, 'bookahtest-')).Count) (expected 0)"
}
foreach ($f in "live-phase5.txt", "live-phase5-events.txt") {
    $p = Join-Path $script:PrivDir $f
    if (Test-Path $p) { Write-Host "--- $f"; Get-Content $p } else { Write-Host "No $f : that part did not run" }
}
