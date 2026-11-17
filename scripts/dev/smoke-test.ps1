# 주의: 이 파일은 반드시 UTF-8 BOM으로 저장해야 한다.
# Windows PowerShell 5.1은 BOM 없는 UTF-8 스크립트를 시스템 ANSI 코드페이지(CP949)로 읽어 한글 문자열이 깨진다.
# 편집기에서 다시 저장할 때 인코딩이 "UTF-8 with BOM"인지 확인한다.

<#
.SYNOPSIS
    서버 전체 흐름(게스트 세션 → 걱정 제출 → 공개 → 활동 → 기록 → 삭제)을 한 번에 점검한다.

.DESCRIPTION
    단계마다 [통과]/[실패]와 걸린 시간을 표시한다. 실패하면 단계 이름, 요청, 응답 코드, 오류 code를 보여주고 멈춘다.
    걱정 원문·요약·토큰·쿠키·시연 키 값은 출력하지 않는다.
    -DemoKey를 주면 시연 흐름(시연 세션 → 프리셋 → 타임머신 → 회고 → 초기화)도 이어서 점검한다.
    시연 흐름은 별도 세션(새 시연 게스트)에서 돌므로 일반 흐름의 게스트와 섞이지 않는다.

.EXAMPLE
    .\scripts\dev\smoke-test.ps1
    .\scripts\dev\smoke-test.ps1 -DemoKey $env:NIGHTVAULT_DEMO_KEY
    .\scripts\dev\smoke-test.ps1 -BaseUrl https://api.example.com -DemoKey (Read-Host '시연 키')
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$DemoKey,
    [int]$PublishInSeconds = 30,
    [int]$AnalysisTimeoutSeconds = 60,
    [string]$TimeZone = 'Asia/Seoul'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$BaseUrl = $BaseUrl.TrimEnd('/')
$script:session = $null
$script:csrf = @{}
$script:stepNo = 0
$script:passed = 0
$totalWatch = [Diagnostics.Stopwatch]::StartNew()

class SmokeFailure : Exception {
    [string]$Request
    [int]$StatusCode
    [string]$Code
    SmokeFailure([string]$message, [string]$request, [int]$statusCode, [string]$code) : base($message) {
        $this.Request = $request; $this.StatusCode = $statusCode; $this.Code = $code
    }
}

function New-Session {
    $script:session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $script:csrf = @{}
}

# 기대한 응답 코드가 아니면 SmokeFailure를 던진다. 경로의 ID는 출력해도 되지만 본문·헤더 값은 출력하지 않는다.
function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        [int[]]$Expect = @(200),
        $Body,
        [hashtable]$Headers = @{},
        [switch]$Demo
    )
    $sendHeaders = @{}
    foreach ($k in $Headers.Keys) { $sendHeaders[$k] = $Headers[$k] }
    if ($Method -ne 'GET') { foreach ($k in $script:csrf.Keys) { $sendHeaders[$k] = $script:csrf[$k] } }
    if ($Demo) { $sendHeaders['X-Demo-Key'] = $DemoKey }

    $params = @{
        Uri = "$BaseUrl$Path"; Method = $Method; WebSession = $script:session
        Headers = $sendHeaders; UseBasicParsing = $true; TimeoutSec = 30
        # 5.1은 201 + Location 응답도 따라가 GET 결과(200)를 돌려주므로 리다이렉트를 끈다.
        MaximumRedirection = 0
    }
    if ($null -ne $Body) {
        $params.ContentType = 'application/json; charset=utf-8'
        $params.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Compress -Depth 5))
    }

    # 5.1은 -Headers로 넘긴 값을 WebSession에 저장해 다음 요청에도 보낸다.
    # 걱정 제출의 Idempotency-Key가 활동 시작에 붙으면 BE1 호환 경로로 처리되므로 매번 비운다.
    $script:session.Headers.Clear()

    $request = "$Method $Path"
    $status = 0; $content = $null; $code = $null
    try {
        $response = Invoke-WebRequest @params
        $status = [int]$response.StatusCode
        # 5.1은 charset이 없는 JSON을 ISO-8859-1로 읽어 한글이 깨지므로 원본 바이트를 UTF-8로 디코딩한다.
        $content = [Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
    } catch {
        if ($null -eq $_.Exception.Response) {
            throw [SmokeFailure]::new("서버에 연결하지 못했습니다: $($_.Exception.Message)", $request, 0, $null)
        }
        $status = [int]$_.Exception.Response.StatusCode
        $content = $_.ErrorDetails.Message
    }

    $parsed = $null
    if ($content) { try { $parsed = $content | ConvertFrom-Json } catch { $parsed = $null } }
    if ($Expect -notcontains $status) {
        if ($parsed -and $parsed.PSObject.Properties['code']) { $code = $parsed.code }
        throw [SmokeFailure]::new("기대 응답 $($Expect -join '/')", $request, $status, $code)
    }
    [pscustomobject]@{ StatusCode = $status; Body = $parsed }
}

function Update-Csrf {
    $body = (Invoke-Api GET '/api/v1/csrf').Body
    if (-not $body.headerName -or -not $body.token) {
        throw [SmokeFailure]::new('CSRF 응답에 token/headerName이 없습니다', 'GET /api/v1/csrf', 200, $null)
    }
    $script:csrf = @{ $body.headerName = $body.token }
}

# PowerShell 7의 ConvertFrom-Json은 ISO 시각을 DateTime으로 바꾸고, 5.1은 문자열로 둔다. 둘 다 받는다.
function ConvertTo-Instant($Value) {
    if ($Value -is [datetime]) { return [DateTimeOffset]$Value.ToUniversalTime() }
    [DateTimeOffset]::Parse([string]$Value, [Globalization.CultureInfo]::InvariantCulture)
}

function Assert-That([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw [SmokeFailure]::new($Message, $null, 0, $null) }
}

# 단계를 실행하고 [통과]/[실패]와 걸린 시간을 표시한다. 스크립트 블록이 문자열을 반환하면 비고로 붙인다.
function Step([string]$Name, [scriptblock]$Action) {
    $script:stepNo++
    $label = '{0,2}. {1}' -f $script:stepNo, $Name
    $watch = [Diagnostics.Stopwatch]::StartNew()
    try {
        $note = & $Action
    } catch {
        $watch.Stop()
        $err = $_.Exception
        Write-Host ('[실패] {0} ({1:N1}s)' -f $label, $watch.Elapsed.TotalSeconds) -ForegroundColor Red
        if ($err -is [SmokeFailure]) {
            if ($err.Request) { Write-Host "       요청: $($err.Request)" -ForegroundColor Red }
            if ($err.StatusCode) { Write-Host "       응답 코드: $($err.StatusCode)" -ForegroundColor Red }
            if ($err.Code) { Write-Host "       오류 code: $($err.Code)" -ForegroundColor Red }
            Write-Host "       내용: $($err.Message)" -ForegroundColor Red
            if ($err.StatusCode -eq 404 -and $err.Request -like '*/api/v1/demo/*') {
                Write-Host '       확인: 서버의 NIGHTVAULT_DEMO_ENABLED=true, NIGHTVAULT_DEMO_KEY 설정과 -DemoKey 값이 맞는지' -ForegroundColor Yellow
            }
        } else {
            Write-Host "       내용: $($err.Message)" -ForegroundColor Red
        }
        Write-Host ('점검 중단: {0}/{1}단계 통과, 총 {2:N1}s' -f $script:passed, $script:stepNo, $totalWatch.Elapsed.TotalSeconds) -ForegroundColor Red
        exit 1
    }
    $watch.Stop()
    $script:passed++
    $suffix = if ($note) { " — $note" } else { '' }
    Write-Host ('[통과] {0} ({1:N1}s){2}' -f $label, $watch.Elapsed.TotalSeconds, $suffix) -ForegroundColor Green
}

function Wait-Until([scriptblock]$Check, [int]$TimeoutSeconds, [string]$Message) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ($true) {
        $result = & $Check
        if ($result) { return $result }
        if ((Get-Date) -ge $deadline) { throw [SmokeFailure]::new($Message, $null, 0, $null) }
        Start-Sleep -Milliseconds 500
    }
}

Write-Host "NightVault 스모크 테스트 — $BaseUrl" -ForegroundColor Cyan
Write-Host '== 일반 흐름 ==' -ForegroundColor Cyan
New-Session

Step 'CSRF 토큰 조회' { Update-Csrf }

Step '게스트 세션 시작' {
    $r = Invoke-Api POST '/api/v1/guest-sessions' -Body @{ mode = 'START' }
    Assert-That ($r.Body.authenticated -eq $true) 'authenticated가 true가 아닙니다'
}

Step 'CSRF 토큰 재조회' { Update-Csrf }

$context = [guid]::NewGuid().ToString()
Step "걱정 제출 (공개 ${PublishInSeconds}초 뒤)" {
    $publishAt = [DateTimeOffset]::UtcNow.AddSeconds($PublishInSeconds).ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
    $headers = @{ 'Idempotency-Key' = [guid]::NewGuid().ToString(); 'X-Analysis-Context' = $context }
    $body = [ordered]@{ text = '[스모크 테스트] 내일 발표가 잘 될지 걱정돼요.'; anxiety = 4; publishAt = $publishAt }
    $r = Invoke-Api POST '/api/v1/worry-requests' -Expect 202 -Headers $headers -Body $body
    $script:requestId = $r.Body.requestId
    Assert-That ([bool]$script:requestId) 'requestId가 없습니다'
    "requestId=$script:requestId"
}

Step '분석 상태 SUCCEEDED 대기' {
    $status = Wait-Until -TimeoutSeconds $AnalysisTimeoutSeconds -Message "${AnalysisTimeoutSeconds}초 안에 분석이 끝나지 않았습니다" {
        $s = (Invoke-Api GET "/api/v1/worry-requests/$script:requestId" -Headers @{ 'X-Analysis-Context' = $context }).Body
        if ($s.status -ne 'PENDING') { $s }
    }
    Assert-That ($status.status -eq 'SUCCEEDED') "분석 상태가 $($status.status)입니다 (failureCode=$($status.failureCode))"
    $script:worryId = $status.worryId
    $script:publishAt = ConvertTo-Instant $status.publishAt
    "worryId=$script:worryId"
}

Step '공개 시각까지 대기' {
    $wait = ($script:publishAt - [DateTimeOffset]::UtcNow).TotalSeconds + 1
    if ($wait -gt 0) { Start-Sleep -Milliseconds ([int]($wait * 1000)) }
    "$([math]::Max(0, [math]::Round($wait)))초 대기"
}

Step '금고 공개 목록에 포함' {
    $found = Wait-Until -TimeoutSeconds 30 -Message '공개 목록에 걱정이 나타나지 않았습니다' {
        $page = (Invoke-Api GET '/api/v1/worries?visibility=PUBLIC&limit=100').Body
        @($page.items | Where-Object { $_.worryId -eq $script:worryId })[0]
    }
    Assert-That ($found.visibility -eq 'PUBLIC') 'visibility가 PUBLIC이 아닙니다'
}

Step '금고 상세 (공개)' {
    $d = (Invoke-Api GET "/api/v1/worries/$script:worryId").Body
    Assert-That ($d.visibility -eq 'PUBLIC') 'visibility가 PUBLIC이 아닙니다'
    Assert-That ([bool]$d.analysis -and @($d.analysis.actionSteps).Count -eq 3) '분석 결과(액션 3단계)가 없습니다'
    "추천=$($d.analysis.recommendation.type)"
}

Step '열람 기록' {
    $r = (Invoke-Api POST "/api/v1/worries/$script:worryId/view").Body
    Assert-That ([bool]$r.firstViewedAt) 'firstViewedAt이 없습니다'
}

Step '요약 수정' {
    Invoke-Api PUT "/api/v1/worries/$script:worryId/summary" -Expect 204 -Body @{ summary = '[스모크 테스트] 수정한 요약' } | Out-Null
}

Step '불안도 기록' {
    Invoke-Api PUT "/api/v1/worries/$script:worryId/anxiety" -Expect 204 -Body @{ anxietyAfter = 2 } | Out-Null
}

Step '액션 활동 시작' {
    $r = Invoke-Api POST "/api/v1/worries/$script:worryId/activities" -Expect 201 -Body @{ helpType = 'ACTION' }
    $script:activityId = $r.Body.activityId
    Assert-That ([bool]$script:activityId) 'activityId가 없습니다'
    "HTTP $($r.StatusCode)"
}

Step '액션 3단계 체크' {
    foreach ($n in 1..3) {
        $view = (Invoke-Api PUT "/api/v1/activities/$script:activityId/steps/$n" -Body @{ done = $true }).Body
    }
    Assert-That ($view.action.canComplete -eq $true) 'canComplete가 true가 아닙니다'
    "doneCount=$($view.action.doneCount)"
}

Step '활동 완료' {
    $view = (Invoke-Api POST "/api/v1/activities/$script:activityId/complete").Body
    Assert-That ($view.state -eq 'COMPLETED') "state가 $($view.state)입니다"
}

Step '도움 평가' {
    Invoke-Api PUT "/api/v1/activities/$script:activityId/feedback" -Body @{ answer = 'HELPFUL' } | Out-Null
}

Step '기록 목록' {
    $page = (Invoke-Api GET '/api/v1/records?limit=100').Body
    $item = @($page.items | Where-Object { $_.worryId -eq $script:worryId })[0]
    Assert-That ([bool]$item) '기록 목록에 걱정이 없습니다'
    Assert-That ($item.anxietyAfter -eq 2) "anxietyAfter가 $($item.anxietyAfter)입니다"
    Assert-That ($item.latestHelpType -eq 'ACTION') "latestHelpType이 $($item.latestHelpType)입니다"
}

Step '홈 요약·이어하기' {
    $summary = (Invoke-Api GET '/api/v1/vault/summary').Body
    Assert-That ($summary.publicCount -ge 1) 'publicCount가 0입니다'
    # 활동을 완료했으므로 이어할 활동이 없어야 한다(204).
    Invoke-Api GET '/api/v1/activities/current' -Expect 204 | Out-Null
    "publicCount=$($summary.publicCount), 이어하기 없음(204)"
}

Step '걱정 삭제' {
    $r = Invoke-Api DELETE "/api/v1/worries/$script:worryId" -Expect 204, 202
    "HTTP $($r.StatusCode)"
}

Step '삭제 후 404 확인' {
    Wait-Until -TimeoutSeconds 30 -Message '삭제 후에도 404가 아닙니다' {
        $r = Invoke-Api GET "/api/v1/worries/$script:worryId" -Expect 200, 404
        $r.StatusCode -eq 404
    } | Out-Null
    Invoke-Api GET "/api/v1/records/$script:worryId" -Expect 404 | Out-Null
    '금고·기록 모두 404'
}

if ($DemoKey) {
    Write-Host '== 시연 흐름 ==' -ForegroundColor Cyan
    New-Session

    Step 'CSRF 토큰 조회 (시연)' { Update-Csrf }

    Step '시연 세션 시작' {
        Invoke-Api POST '/api/v1/demo/session' -Demo | Out-Null
        Update-Csrf
        $s = (Invoke-Api GET '/api/v1/demo/status' -Demo).Body
        Assert-That ($s.demo -eq $true) 'demo가 true가 아닙니다'
    }

    Step '프리셋 생성' {
        $s = (Invoke-Api POST '/api/v1/demo/presets' -Demo).Body
        Assert-That ($s.lockedCount -ge 1) 'lockedCount가 0입니다'
        $locked = (Invoke-Api GET '/api/v1/worries?visibility=LOCKED&limit=100').Body
        $script:demoWorryId = @($locked.items)[0].worryId
        Assert-That ([bool]$script:demoWorryId) '잠긴 걱정이 없습니다'
        "잠김 $($s.lockedCount) · 공개 $($s.publicCount) · 실패 $($s.failedRequestCount)"
    }

    Step '공개 시각으로 이동' {
        $r = (Invoke-Api POST '/api/v1/demo/time-machine' -Demo -Body @{ preset = 'TO_PUBLISH'; worryId = $script:demoWorryId }).Body
        "$($r.shiftedSeconds)초 이동"
    }

    Step '공개된 걱정 상세·열람' {
        $d = (Invoke-Api GET "/api/v1/worries/$script:demoWorryId").Body
        Assert-That ($d.visibility -eq 'PUBLIC') 'visibility가 PUBLIC이 아닙니다'
        Invoke-Api POST "/api/v1/worries/$script:demoWorryId/view" | Out-Null
    }

    Step '24시간 후로 이동' {
        $r = (Invoke-Api POST '/api/v1/demo/time-machine' -Demo -Body @{ preset = 'PLUS_24H' }).Body
        "$($r.shiftedSeconds)초 이동"
    }

    $tz = [uri]::EscapeDataString($TimeZone)
    Step '회고 후보 조회' {
        $r = Invoke-Api GET "/api/v1/reflections/candidate?currentWorryId=$script:demoWorryId&timeZone=$tz" -Expect 200
        $script:reflectionId = $r.Body.worryId
        Assert-That ([bool]$script:reflectionId) '회고 후보가 없습니다'
        "worryId=$script:reflectionId"
    }

    Step '회고 응답' {
        Invoke-Api PUT "/api/v1/reflections/$($script:reflectionId)?currentWorryId=$script:demoWorryId&timeZone=$tz" -Expect 204 -Body @{ answer = 'BETTER' } | Out-Null
        $r = (Invoke-Api GET "/api/v1/reflections/$script:reflectionId").Body
        Assert-That ($r.answer -eq 'BETTER') "저장된 답이 $($r.answer)입니다"
    }

    Step '시연 초기화' {
        $s = (Invoke-Api POST '/api/v1/demo/reset' -Demo).Body
        Assert-That ($s.lockedCount -eq 0 -and $s.publicCount -eq 0) '초기화 후에도 걱정이 남아 있습니다'
    }
} else {
    Write-Host '시연 흐름은 건너뜀 (-DemoKey 없음)' -ForegroundColor DarkGray
}

Write-Host ('전체 통과: {0}단계, 총 {1:N1}s' -f $script:passed, $totalWatch.Elapsed.TotalSeconds) -ForegroundColor Green
exit 0
