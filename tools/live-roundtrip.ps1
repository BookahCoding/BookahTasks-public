# BookahTasks Phase 2, Build A gate. ONLY after the owner's yes. Makes the throw-away list "bookahtest-a1", runs LiveRoundTripTest against it,
# then deletes the list for good (X-NC-CalDAV-No-Trashbin, allowed in this script only). Prints counts and status codes only.
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\live-roundtrip.ps1
param([string]$Slug = "bookahtest-a1", [string]$Target = "BookahTasks-nextcloud")
if ($Slug -notlike "bookahtest-*") { throw "Refusing: slug must start with bookahtest-" }
. "$PSScriptRoot\..\spike\Dav.ps1" -Target $Target
$u = $script:Cred.User
$listHref = "/remote.php/dav/calendars/$u/$Slug/"
$q = '<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/></d:prop></d:propfind>'
$r = Invoke-Dav "PROPFIND" $listHref $q @{ Depth = "0" }
if ($r.Status -ne 404) { throw "List already exists or unexpected answer (HTTP $($r.Status)); not touching it" }
$mk = '<?xml version="1.0"?><d:mkcol xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/"><d:set><d:prop><d:resourcetype><d:collection/><c:calendar/></d:resourcetype><d:displayname>' + $Slug + '</d:displayname><a:calendar-color>#2E7D5B</a:calendar-color><c:supported-calendar-component-set><c:comp name="VTODO"/></c:supported-calendar-component-set></d:prop></d:set></d:mkcol>'
$r = Invoke-Dav "MKCOL" $listHref $mk
Write-Host "MKCOL list: HTTP $($r.Status)"
if ($r.Status -ne 201) { throw "List not created" }
$resultFile = Join-Path $script:PrivDir "live-roundtrip.txt"
if (Test-Path $resultFile) { Remove-Item $resultFile }
try {
    $env:BOOKAH_LIVE_SERVER = $script:Base; $env:BOOKAH_LIVE_USER = $u; $env:BOOKAH_LIVE_PASSWORD = $script:Cred.Secret
    $env:BOOKAH_LIVE_SLUG = $Slug; $env:BOOKAH_PRIVATE_DIR = $script:PrivDir
    $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
    Push-Location "$PSScriptRoot\..\android"
    & .\gradlew.bat --offline --no-daemon -q :core-data:cleanTestDebugUnitTest :core-data:testDebugUnitTest --tests "*LiveRoundTripTest" | Out-Null
    Write-Host "gradle exit code: $LASTEXITCODE"
    Pop-Location
} finally {
    foreach ($n in "BOOKAH_LIVE_SERVER", "BOOKAH_LIVE_USER", "BOOKAH_LIVE_PASSWORD", "BOOKAH_LIVE_SLUG") { Remove-Item "Env:$n" -ErrorAction SilentlyContinue }
    $r = Invoke-Dav "DELETE" $listHref $null @{ "X-NC-CalDAV-No-Trashbin" = "1" }
    Write-Host "DELETE throw-away list (permanent): HTTP $($r.Status)"
    $r = Invoke-Dav "PROPFIND" "/remote.php/dav/calendars/$u/" '<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/></d:prop></d:propfind>' @{ Depth = "1" }
    Write-Host "mentions of bookahtest- left: $(([regex]::Matches($r.Body, 'bookahtest-')).Count) (expected 0)"
}
if (Test-Path $resultFile) { Get-Content $resultFile } else { Write-Host "No result file: the test did not run" }
