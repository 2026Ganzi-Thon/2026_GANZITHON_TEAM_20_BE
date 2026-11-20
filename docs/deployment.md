# 발표 서버 배포 절차

JDK 21, Python 3.11 이상과 PostgreSQL 17을 준비한다. Java와 Python 의존성은 저장소의 Gradle Wrapper와 `AI/requirements.txt`로 설치한다. 이 절차는 프런트와 Spring 프록시를 같은 HTTPS 출처에 두고, Spring과 Python을 같은 서버에서 실행하는 구성을 기준으로 한다.

브라우저는 `https://<발표 도메인>/api/v1`로 요청한다. HTTPS 프록시는 이를 `127.0.0.1:8080`의 Spring으로 전달하고, Spring만 `127.0.0.1:8001`의 Python과 사설 PostgreSQL에 접근한다. 서버 방화벽에서는 HTTPS 접근을 허용하고 Python·Spring·DB 포트의 외부 접근을 차단한다. Python을 별도 컨테이너로 분리할 때는 Spring이 접근 가능한 사설 네트워크 주소로 바꾸고 외부 포트 공개를 막는다.

## PostgreSQL DB와 사용자

관리형 PostgreSQL은 플랫폼의 DB와 사용자 생성 기능을 사용할 수 있다. 직접 설치한 PostgreSQL에서는 관리자로 `psql`에 접속해 다음 순서로 전용 사용자를 만든다. 비밀번호는 `\password`의 입력 프롬프트에 입력한다.

```sql
CREATE ROLE nightvault LOGIN;
\password nightvault
CREATE DATABASE nightvault OWNER nightvault;
```

Spring에는 해당 DB의 JDBC URL, 사용자와 비밀번호를 주입한다. 예시 URL은 `jdbc:postgresql://127.0.0.1:5432/nightvault`이며 관리형 DB는 플랫폼의 사설 주소와 TLS 연결 옵션을 사용한다. Spring 시작 시 Flyway가 해당 DB에 테이블과 적용 이력을 생성한다. 기존 DB에는 데이터와 Flyway 이력을 유지한다.

## 데이터 암호화 키와 내부 토큰

데이터 키는 무작위 32바이트를 Base64로 인코딩한 값이다. 서버를 처음 구성할 때 한 번 생성한다.

```bash
python -c 'import base64,secrets; print(base64.b64encode(secrets.token_bytes(32)).decode())'
```

생성한 값을 배포 플랫폼의 비밀 설정에 `NIGHTVAULT_DATA_KEY`로 저장한다. 기존 암호문이 있는 DB에서는 같은 키를 사용한다. DB 백업과 연결되는 키 버전을 기록하고, 키는 접근 권한이 제한된 비밀 저장소에 따로 백업한다. 복구 시 DB와 원래 키가 함께 필요하다. 키 분실이나 임의 교체는 기존 걱정의 복호화에 영향을 주므로 암호화 데이터가 있는 상태에서 재생성하지 않는다.

Spring과 Python이 공유하는 토큰도 별도로 생성한다.

```bash
python -c 'import secrets; print(secrets.token_urlsafe(32))'
```

같은 값을 두 프로세스의 `NIGHTVAULT_AI_TOKEN`에 주입한다. Python은 토큰 누락·공백·유효하지 않은 문자에서 기동을 거부하며, 요청의 Bearer 토큰이 없거나 다르면 `401`을 반환한다. 키와 토큰은 Git·일반 로그·시연 화면에 포함하지 않는다.

## 프로세스 환경변수

Spring 배포에는 아래 값을 플랫폼의 환경변수나 서비스 관리자의 비밀 설정으로 주입한다.

- `SPRING_PROFILES_ACTIVE=prod`
- `NIGHTVAULT_BIND_ADDRESS=127.0.0.1`과 `SERVER_PORT=8080` (운영 기본 바인딩도 loopback)
- `NIGHTVAULT_DB_URL`, `NIGHTVAULT_DB_USER`, `NIGHTVAULT_DB_PASSWORD`
- `NIGHTVAULT_DATA_KEY`
- `NIGHTVAULT_AI_URL=http://127.0.0.1:8001/internal/v1/analyze`
- `NIGHTVAULT_AI_TOKEN`
- `NIGHTVAULT_AI_TIMEOUT_SECONDS=27`

Python 배포에는 아래 값을 주입한다.

- `NIGHTVAULT_ENV=prod`
- `NIGHTVAULT_AI_MODE=mock`으로 첫 연동을 확인한다.
- Spring과 같은 `NIGHTVAULT_AI_TOKEN`
- `UPSTAGE_TIMEOUT_SECONDS=24`
- 실제 Solar 활성화 시 `NIGHTVAULT_AI_MODE=solar`, `UPSTAGE_API_KEY`, 필요하면 `UPSTAGE_MODEL=solar-mini4`

운영 모드에서 양쪽 서버는 개발용 `.env`를 자동으로 읽지 않는다. Spring은 명시적으로 `prod`를 활성화하고, Python의 기본 환경도 `prod`다. 로컬 Spring 실행은 기본 `local` 프로필에서 `.env`를 읽는다. 로컬 Python에서 `.env`를 읽으려면 셸에 `NIGHTVAULT_ENV=local`을 명시한다. `.env` 안에 이 이름을 넣는 것만으로 로딩이 허용되지는 않는다.

기존 배포 설정에 10초·12초가 지정돼 있다면 24초·27초로 맞추고 두 프로세스를 재시작한다. #14의 분석 변경을 통합한 뒤 첫 문맥 분류·일반 생성 지연을 재시도하고, 분류부터 일반 생성까지 Python의 전체 제한을 공유한다. Spring 대기는 Python 제한보다 길게 둔다.

## 실행

JDK 버전과 의존성을 확인하고 실행 JAR를 만든다.

```bash
java -version
bash ./gradlew bootJar
python -m venv AI/.venv
AI/.venv/Scripts/python.exe -m pip install -r AI/requirements.txt
```

Linux에서는 가상환경 실행 파일 경로를 `AI/.venv/bin/python`으로 바꾼다. 위의 배포 환경변수를 주입한 상태에서 Python과 Spring을 각각 실행한다.

```bash
AI/.venv/Scripts/python.exe -m uvicorn mock_api:app --app-dir AI --host 127.0.0.1 --port 8001 --no-access-log
```

```bash
java -jar build/libs/nightvault-0.1.0-SNAPSHOT.jar
```

## HTTPS 프록시와 브라우저 쿠키

브라우저가 접속할 도메인에 유효한 TLS 인증서를 적용하고 HTTP 접속은 HTTPS로 리다이렉트한다. 프런트 파일과 API를 같은 출처에 제공한다. 예를 들어 Nginx의 HTTPS 서버 블록에서는 API 요청 경로를 유지해 Spring으로 전달한다.

```nginx
location /api/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header Forwarded "";
    proxy_set_header X-Forwarded-Proto $scheme;
    # 단일 외부 프록시 기준: 사용자가 보낸 forwarded 헤더를 덮어쓴다.
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_read_timeout 35s;
}

location / {
    root /srv/nightvault/frontend/dist;
    try_files $uri $uri/ /index.html;
}
```

위 블록은 TLS 인증서가 설정된 HTTPS 서버 안에 둔다. `/api/` 실패를 SPA의 `index.html`로 바꾸지 않는다. Spring은 `forward-headers-strategy: native`로 HTTPS 정보를 인식하며, 기본 신뢰 프록시는 loopback으로 제한한다. 다른 호스트·컨테이너를 사용하면 실제 프록시의 주소만 `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES`에 지정하고 Spring의 외부 접근을 차단한다. 빈 정규식이나 모든 주소를 신뢰하는 값은 사용하지 않는다. 프록시도 외부가 보낸 `Forwarded`를 제거하고 자신이 만든 `X-Forwarded-*`만 전달하도록 구성한다. 다단 프록시는 각 홉의 신뢰 범위를 별도로 검토한다. [Spring Boot 프록시 설정](https://docs.spring.io/spring-boot/how-to/webserver.html), [프런트 연동 가이드](api/frontend-guide.md).

프런트의 API 기본 경로는 `/api/v1`로 맞춘다. 운영 쿠키의 `HttpOnly`, `Secure`, `SameSite=Lax`를 유지하며 기본 host-only 쿠키는 발표 도메인에서 발급·사용한다. 별도 쿠키 도메인 설정은 같은 출처 구성에서 필요하지 않다.

실제 브라우저에서 CSRF 발급 → 게스트 생성 → CSRF 재발급 → 걱정 제출을 확인한다. 일반 HTTP 주소에서 `Secure` 세션 쿠키가 저장·재전송되지 않으면 CSRF 검증이 실패해 게스트 생성 요청이 `403`으로 끝날 수 있다. 이 경우 HTTPS 주소와 브라우저 쿠키 전달 상태를 확인한다. 프록시가 받는 HTTPS와 내부 Spring 연결의 HTTP는 이 구성에서 함께 사용한다.

Nginx의 [요청량 제한과 발표 전환 절차](../deploy/nginx/README.md)는 같은 서버에 배포할 때 사용하는 평시 설정, 발표 후보 설정, 부하 확인과 원복 절차를 제공한다. `429 RATE_LIMITED`는 Nginx에서 반환되며 분석의 접수와 완료는 별도로 확인한다.

## 시연과 Solar 활성화

승인된 발표 환경에 `NIGHTVAULT_DEMO_ENABLED=true`와 `NIGHTVAULT_DEMO_KEY`를 설정한다. 시연 게스트의 직접 제출·재시도는 예시 분석으로 봉인된다. 일반 게스트는 Python을 호출한다. 모의 모드에서 두 경로를 합성 입력으로 확인한다. [시연 API 가이드](api/demo-guide.md)

실제 Solar는 #14 위기 판정 변경의 검토·통합과 S-16 화면 검증 후에 활성화한다. Python에만 Solar API 키를 주입하고 정상·위기 경로를 실제 발표 주소에서 확인한다. 분석 중 15초 지연 안내와 30초 이후 상태 확인은 프런트 정책대로 처리한다. 30초 경과만으로 최종 실패를 표시하지 않는다.

운영에서 시연을 켰는데 `NIGHTVAULT_DEMO_KEY`가 없으면 시작을 거부한다. DB URL·사용자·비밀번호 누락도 데이터소스 초기화 전에 설정 이름으로 안내하며 실제 값은 출력하지 않는다. 외부 `SPRING_DATASOURCE_*`로 설정한 동일 속성도 검증한다.

## Flyway 운영 정책

새 운영 DB는 `out-of-order=false`가 기본이다. 로컬·테스트의 기존 날짜 기반 병합 정책은 유지한다. 이미 BE1·BE2의 서로 다른 날짜 버전을 적용한 DB를 전환할 때에는 백업하고 `flyway_schema_history`와 배포할 마이그레이션 목록을 대조한다. 아직 적용하지 않은 더 이른 버전이 있다면 검토한 배포에 한해 `NIGHTVAULT_FLYWAY_OUT_OF_ORDER=true`를 명시해 적용하고 이력을 다시 확인한다. 이후 기본값으로 복귀한다. 기존 이력을 지우거나 적용된 SQL을 수정해 해결하지 않는다.

## Web Push의 사용 여부

1차 배포 기본 범위는 알림 미사용이다. 다음 세 값을 모두 비워 두면 서버 발송은 비활성이다. 알림을 발표에 포함하기로 결정하면 세 값을 모두 설정한다.

- `NIGHTVAULT_PUSH_PUBLIC_KEY`: VAPID 공개키. 프런트 구독에 사용하는 공개 값이다.
- `NIGHTVAULT_PUSH_PRIVATE_KEY`: 대응하는 비밀키. 서버 비밀 설정에만 보관한다.
- `NIGHTVAULT_PUSH_SUBJECT`: 운영 연락처의 `mailto:` 또는 HTTPS URL.

일부만 설정되면 시작 시 오류가 발생한다. 프런트의 동의 저장만으로 알림 수신이 완성되는 것은 아니다. 서비스워커·브라우저 구독·서버 구독 등록과 실제 모바일 수신을 확인한 뒤 알림이 동작한다고 안내한다. 시연 게스트는 기존 발송 제외 규칙을 유지한다.

## 프로세스 관리와 로그

서비스 관리자로 Python·Spring을 각각 관리하고 오류 시 재시작과 접근 제한된 로그 수집을 설정한다. 아래는 Linux systemd 예시이며 실제 JDK·Python·배포 경로에 맞춘다. 비밀 환경 파일은 서비스 사용자만 읽도록 소유권과 권한을 제한하고 Git에 넣지 않는다. 명령줄 인수에 키를 넣지 않는다.

```ini
# nightvault-ai.service
[Unit]
Description=Backend AI service
After=network.target
[Service]
User=nightvault
WorkingDirectory=/srv/nightvault/backend
EnvironmentFile=/etc/nightvault/ai.env
ExecStart=/srv/nightvault/backend/AI/.venv/bin/python -m uvicorn mock_api:app --app-dir AI --host 127.0.0.1 --port 8001 --no-access-log
Restart=on-failure
RestartSec=5
NoNewPrivileges=true
[Install]
WantedBy=multi-user.target
```

```ini
# nightvault-backend.service
[Unit]
Description=Backend API service
After=network.target nightvault-ai.service
[Service]
User=nightvault
WorkingDirectory=/srv/nightvault/backend
EnvironmentFile=/etc/nightvault/backend.env
ExecStart=/opt/jdk-21/bin/java -jar /srv/nightvault/backend/build/libs/nightvault-0.1.0-SNAPSHOT.jar
Restart=on-failure
RestartSec=5
NoNewPrivileges=true
[Install]
WantedBy=multi-user.target
```

`ai.env`에는 Python 환경변수, `backend.env`에는 Spring 환경변수를 둔다. 운영 `.env` 자동 로드를 다시 켜지 않는다. systemd를 사용하는 서버에서 다음 명령으로 적용·상태·로그를 확인한다.

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now nightvault-ai nightvault-backend
sudo systemctl restart nightvault-ai nightvault-backend
sudo systemctl status nightvault-ai nightvault-backend
sudo journalctl -u nightvault-ai -u nightvault-backend --since '10 minutes ago'
```

컨테이너/관리형 플랫폼은 같은 환경변수·내부 네트워크·재시작 정책을 플랫폼 설정으로 적용한다. 백엔드와 Python 로그에는 원문·응답 본문·Authorization·쿠키를 기록하지 않는다. 프록시도 쿠키·요청 본문·시연 키를 로그 형식에 넣지 않는다. 보존 기간·열람 권한·로그 용량 상한을 정한다.

## 배포 후 점검

최신 develop의 [스모크 테스트 안내](https://github.com/2026Ganzi-Thon/2026_GANZITHON_TEAM_20_BE/blob/8d7a7391c89934a1812161695ffb578272747f80/scripts/dev/README.md)를 따른다. 이 브랜치에 스크립트가 없으면 해당 develop 변경과 통합한 뒤 실행한다. 점검용 게스트와 합성 걱정이 생성되고 걱정은 마지막에 삭제되는 절차다.

Cursor Git Bash에서 Windows PowerShell 스크립트를 호출하는 예시다. 실제 도메인으로 교체한다.

```bash
powershell.exe -NoProfile -File ./scripts/dev/smoke-test.ps1 -BaseUrl 'https://app.example.com' -AnalysisTimeoutSeconds 120
```

브라우저에서도 HSTS·Secure 쿠키·CSRF 재발급·S-16·지연 복귀·시연·삭제를 확인한다. HTTPS 프록시·모바일 브라우저 검증 전에는 배포 완료로 기록하지 않는다. 워커 이름은 local/prod 공통인 `AnalysisWorker`이며, 현재 워커는 인스턴스당 한 번에 한 작업을 처리한다. 동시 제출의 대기 시간과 외부 공개 API 호출량·공급자 비용 한도는 [프런트 연동·보안 점검](frontend-integration-review.md)의 배포 전 항목을 따른다.

## 배포 프로필의 기존 검증

2026년 11월 17일에 별도 PostgreSQL 17에서 기존 Spring 테스트 103개가 통과했다. 로컬 HTTPS 프록시에 `prod` 실행 JAR를 연결해 `Secure` 쿠키, 시연 직접 제출·실패 재시도와 일반 게스트의 Python 호출을 확인했다. 이번 보안 리뷰 반영 결과는 [배포 리뷰 검증 기록](deployment-review-record.md)에 기록한다.
