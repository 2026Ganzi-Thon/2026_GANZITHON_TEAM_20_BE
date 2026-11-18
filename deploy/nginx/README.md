# Nginx 요청량 제한

공개 HTTPS의 `/api/`는 같은 서버의 Spring(`127.0.0.1:8080`)으로 전달한다. 게스트 시작·재시작은 한도를 공유하고, 걱정 제출과 분석 재시도도 별도의 한도를 공유한다. 상태 조회와 CSRF 발급 등 다른 요청에는 이 제한을 적용하지 않는다. 분당 값은 평균 처리율이고 `burst`는 Nginx가 평균을 넘은 요청을 순간적으로 받아들이는 범위다. IP와 서버 전체 중 어느 한쪽의 한도를 초과하면 `429`를 반환한다. [Nginx 요청량 제한 문서](https://nginx.org/en/docs/http/ngx_http_limit_req_module.html)

- 게스트 발급은 IP당 평균 12회/분, 서버 전체 평균 90회/분을 적용한다. IP·전체 `burst`는 평시 6·20, 발표 시 220·250이다.
- 걱정 제출·재시도는 IP당 평균 6회/분, 서버 전체 평균 24회/분을 공유한다. IP·전체 `burst`는 평시 5·12, 발표 시 24·36이다.

평시 값은 2026-11-19 Lightsail의 기존 설정을 기준으로 했다. 발표 설정은 공유 IP에서 최대 200명의 첫 접속과 한 그룹당 약 20명의 분석 제출을 기준으로 했다. `nodelay`를 사용하므로 통과한 요청은 바로 Spring에 도달한다. 직전 요청으로 한도를 사용했다면 다음 그룹은 회복을 기다려야 한다. 평균 처리율은 두 프로필에서 같다. 제한 판단에는 Nginx가 직접 연결을 받은 주소를 사용하며, 클라이언트가 보낸 `X-Forwarded-For`는 신뢰하지 않는다.

## 새 서버에서 구성하기

1. `rate-zones.conf`를 배포 서버의 `/etc/nginx/conf.d/nightvault-rate-zones.conf`에 설치한다. `conf.d/*.conf`가 `http` 문맥에서 한 번만 로딩되는지 확인한다.
2. `api-proxy.conf`, `rate-limit-response.conf`, `rate-limits.conf`, `rate-limits.presentation.conf`를 `/etc/nginx/nightvault/`에 설치한다. `site.conf.example`을 참고해 TLS 사이트의 도메인과 인증서 경로를 넣고 평시 `rate-limits.conf`를 활성화한다.
3. 기존 사이트에는 실제 `server`와 `location /api/`를 유지하면서 지시문을 통합한다. `location /api/`에 `^~`가 있으면 경로별 제한을 적용하는 정규식 위치가 선택되지 않으므로 예시처럼 일반 접두사 위치를 사용한다. 같은 이름의 zone, 프록시 위치, 에러 위치를 중복 선언하지 않는다. 릴리스 전 `sudo nginx -t`를 통과하면 `sudo systemctl reload nginx`로 반영한다.
4. HTTPS에서 CSRF 발급, 일반 게스트 생성 및 걱정 제출과 상태 조회를 검증한다. 저장소에는 인증서 개인키, 실제 환경 파일과 토큰을 넣지 않는다.

Nginx는 64 KiB를 초과하는 API 요청 본문을 `413`으로 거절한다. 이 설정이 만든 `429`는 JSON `RATE_LIMITED`, `Cache-Control: no-store`, `Retry-After: 10`을 반환한다. 프런트는 대기 시간을 표시한 뒤 사용자가 같은 동작을 다시 누르게 한다. 새 걱정 제출과 새 분석 재시도는 각각 키를 만들고, 그중 같은 동작을 재전송할 때만 기존 `Idempotency-Key`를 유지한다. [프런트 연동 가이드](../../docs/api/frontend-guide.md)

## 발표 적용과 원복

1. 서버의 현재 사이트·제한 설정을 활성 include 경로 밖에 보관한다. 행사 기간에는 `rate-limits.presentation.conf`를 선택한다. 이미 배포된 사이트를 패치할 때는 현재 파일의 네 `burst` 숫자만 각각 220·250·24·36으로 바꾼다.
2. 변경할 때마다 `sudo nginx -t`가 성공하면 `sudo systemctl reload nginx`를 실행한다. TLS·게스트 발급·걱정 제출/조회·429 응답 계약을 다시 확인한다. 시연 키 없이 일반 게스트를 사용한다. 개별 흐름은 `pwsh -File ./scripts/dev/smoke-test.ps1 -BaseUrl https://vault.example.com`으로 점검할 수 있다.
3. 발표 환경과 같은 Wi-Fi에서 리허설하고 일반 게스트의 처음 접속과 첫 제출을 나눠 검증한다. 429·5xx, 202 접수율, 접수부터 완료까지 걸린 시간(p50·p95·최대), 미완료 건수를 기록한다. IP 분석 평균 6회/분을 모두 사용하면 그룹당 20건 기준 회복에 약 3분 20초가 걸릴 수 있다. 분석 상태 조회는 계속할 수 있다.
4. 발표가 끝나거나 승인 기준을 넘으면 평시 프로필을 선택한다. 기존 사이트를 패치했다면 네 `burst`를 각각 6·20·5·12로 되돌린다. 행사 중 프런트 설정이 추가된 파일은 해당 숫자만 원복한다. `sudo nginx -t` 성공 후 reload하고 HTTPS와 평시 429 응답을 확인한다.

격리한 Nginx·Spring prod·PostgreSQL 17·Python mock으로 시험했다. 이 발표 설정에서 일반 분석 20건이 모두 접수·완료됐고, 가장 늦은 완료는 10.15초에 확인했다. 대기열 확인을 위해 분석 `burst`를 임시로 늘렸을 때는 50건이 최대 26.25초, 200건이 최대 103.21초에 완료됐다. 합동 리허설은 20명 단위로 진행한다. 실제 Solar의 처리 시간과 비용은 별도로 측정한다. 일반 게스트 기반 검증과 앱 시연 모드는 별개다.
