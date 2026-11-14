# 주의: 이 파일은 반드시 UTF-8 BOM으로 저장해야 한다.
# Windows PowerShell 5.1은 BOM 없는 UTF-8 스크립트를 시스템 ANSI 코드페이지(CP949)로 읽어 한글 문자열이 깨진다.
# 편집기에서 다시 저장할 때 인코딩이 "UTF-8 with BOM"인지 확인한다.

<#
.SYNOPSIS
    로컬 서버에 게스트 세션을 만들고, 곧 공개되는 걱정을 1건 제출한다 (테스트 데이터용).

.DESCRIPTION
    CSRF 조회 → 게스트 세션 START → CSRF 재조회 → 걱정 제출 → 상태 조회(SUCCEEDED까지 폴링).
    WebSession으로 세션 쿠키와 게스트 쿠키를 유지한다. 결과 객체를 파이프라인으로 반환한다.

.EXAMPLE
    .\scripts\dev\create-public-worry.ps1
    .\scripts\dev\create-public-worry.ps1 -PublishInSeconds 60 -Text '[mock:failure] 실패 테스트' -ExpectFailure
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [int]$PublishInSeconds = 30,
    [string]$Text = '내일 발표가 걱정돼요.',
    [ValidateRange(1, 5)][int]$Anxiety = 3,
    [string]$Memo,
    [int]$TimeoutSeconds = 60,
    [switch]$ExpectFailure
)

$ErrorActionPreference = 'Stop'
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

function Invoke-Api {
    param([string]$Method, [string]$Path, [hashtable]$Headers = @{}, $Body)
    $params = @{
        Uri = "$BaseUrl$Path"; Method = $Method; WebSession = $session
        Headers = $Headers; UseBasicParsing = $true
    }
    if ($null -ne $Body) {
        $params.ContentType = 'application/json; charset=utf-8'
        $params.Body = [System.Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Compress))
    }
    try {
        $response = Invoke-WebRequest @params
    } catch {
        $detail = $_.ErrorDetails.Message
        throw "$Method $Path 실패: $($_.Exception.Message) $detail"
    }
    $content = $response.Content
    if ($content -is [byte[]]) { $content = [System.Text.Encoding]::UTF8.GetString($content) }
    [pscustomobject]@{
        StatusCode = [int]$response.StatusCode
        Body = if ($content) { $content | ConvertFrom-Json } else { $null }
    }
}

function Get-CsrfHeader {
    $csrf = (Invoke-Api GET '/api/v1/csrf').Body
    @{ $csrf.headerName = $csrf.token }
}

# 1. CSRF 조회
$csrfHeader = Get-CsrfHeader
Write-Host '[1/5] CSRF 토큰 조회 완료'

# 2. 게스트 세션 START
$guest = Invoke-Api POST '/api/v1/guest-sessions' $csrfHeader @{ mode = 'START' }
Write-Host "[2/5] 게스트 세션 시작 (expiresAt=$($guest.Body.expiresAt))"

# 3. 세션 발급 후 CSRF 토큰이 바뀌므로 재조회
$csrfHeader = Get-CsrfHeader
Write-Host '[3/5] CSRF 토큰 재조회 완료'

# 4. 걱정 제출
$context = [guid]::NewGuid().ToString()
$headers = $csrfHeader.Clone()
$headers['Idempotency-Key'] = [guid]::NewGuid().ToString()
$headers['X-Analysis-Context'] = $context
$publishAt = [DateTimeOffset]::UtcNow.AddSeconds($PublishInSeconds).ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
$body = [ordered]@{ text = $Text; anxiety = $Anxiety; publishAt = $publishAt }
if ($Memo) { $body.memo = $Memo }
$submit = Invoke-Api POST '/api/v1/worry-requests' $headers $body
$requestId = $submit.Body.requestId
Write-Host "[4/5] 걱정 제출 HTTP $($submit.StatusCode) requestId=$requestId publishAt=$publishAt"

# 5. 상태 조회 (PENDING이면 폴링)
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
do {
    $status = (Invoke-Api GET "/api/v1/worry-requests/$requestId" @{ 'X-Analysis-Context' = $context }).Body
    if ($status.status -ne 'PENDING') { break }
    Start-Sleep -Seconds 1
} while ((Get-Date) -lt $deadline)
Write-Host "[5/5] 상태=$($status.status) worryId=$($status.worryId)"

if (-not $ExpectFailure -and $status.status -ne 'SUCCEEDED') {
    throw "SUCCEEDED가 아닙니다: $($status | ConvertTo-Json -Compress)"
}

[pscustomobject]@{
    RequestId = $requestId
    Status    = $status.status
    WorryId   = $status.worryId
    SealedAt  = $status.sealedAt
    PublishAt = $status.publishAt
    Context   = $context
    Session   = $session
}
