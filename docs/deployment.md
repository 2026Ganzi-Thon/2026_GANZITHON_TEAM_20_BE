# 발표 서버 배포 절차

공개 HTTPS 요청은 프런트와 같은 출처의 `/api/v1`에서 받아 Spring으로 전달한다. Spring만 비공개 Python 분석 API와 PostgreSQL에 접근한다. Spring과 Python에는 외부에서 직접 접근할 필요가 없는 포트를 공개하지 않는다.

## Spring 환경변수

- `SPRING_PROFILES_ACTIVE=prod` — 배포용 데이터소스와 분석 워커를 켠다.
- `NIGHTVAULT_DB_URL`, `NIGHTVAULT_DB_USER`, `NIGHTVAULT_DB_PASSWORD` — 배포 DB의 JDBC 주소와 자격 증명. URL 형태는 `jdbc:postgresql://<DB 내부 주소>:5432/<DB 이름>`이다. Spring 시작 시 Flyway가 마이그레이션을 적용한다.
- `NIGHTVAULT_DATA_KEY` — 32바이트 무작위 키의 Base64 값. 기존 데이터가 있는 DB에서는 기존 키를 유지한다.
- `NIGHTVAULT_AI_URL` — 비공개 Python 서비스의 `/internal/v1/analyze` 전체 주소.
- `NIGHTVAULT_AI_TOKEN` — Python과 공유하는 내부 서비스 토큰. 배포 플랫폼의 비밀 설정으로 주입한다.
- `SERVER_PORT` — 플랫폼이 지정한 앱 포트가 있는 경우 설정한다. `NIGHTVAULT_BIND_ADDRESS`의 기본값은 `0.0.0.0`이다. 같은 호스트에서 프록시를 실행하면 루프백에 한정할 수 있다.
- `NIGHTVAULT_AI_TIMEOUT_SECONDS` — 분석 요청 전체 제한 시간. 기본값은 배포에서 12초, 로컬에서 3초다.

운영 쿠키는 HTTPS에서 `Secure`를 유지한다. 필수 분석 설정이 비어 있거나 쿠키 보안이 꺼져 있으면 Spring 시작이 실패한다. 배포 환경에는 개발용 루트 `.env`의 로컬 설정값을 복사하지 않는다.

## Python 시작과 연동

Python은 내부 서비스 주소에서만 접근할 수 있게 배치한다. 두 서비스에 같은 `NIGHTVAULT_AI_TOKEN`을 주입한다. 우선 `NIGHTVAULT_AI_MODE=mock`으로 Python을 띄워 게스트 인증부터 봉인까지 확인한다.

```bash
python -m pip install -r AI/requirements.txt
python -m uvicorn mock_api:app --app-dir AI --host 0.0.0.0 --port 8001 --no-access-log
```

Spring은 아래 빌드에서 만든 실행 JAR로 시작한다.

```bash
bash ./gradlew bootJar
java -jar build/libs/nightvault-0.1.0-SNAPSHOT.jar
```

시연 서버에서는 승인된 시연 환경에만 `NIGHTVAULT_DEMO_ENABLED=true`, `NIGHTVAULT_DEMO_KEY`를 설정한다. 시연 게스트의 제출·재시도는 기존 봉인 처리와 함께 예시 분석을 사용한다. 일반 게스트는 배포 프로필에서도 Python을 거쳐 처리된다. 합성 입력으로 두 경로를 확인한다. [시연 API 가이드](api/demo-guide.md)

실제 Solar 활성화는 위기 판정 수정의 검토와 S-16 화면 연동 검증 후에 진행한다. 이후 Python에만 `UPSTAGE_API_KEY`를 주입하고 `NIGHTVAULT_AI_MODE=solar`로 전환한다. 앱 프로필에서 사용하는 Spring 작업 제한은 Python 분석 제한보다 길게 잡는다.

## 배포 프로필 검증 기록

2026년 11월 17일에 별도 PostgreSQL 17에서 Spring 테스트 103개가 통과했다. 그중 5개는 배포 프로필, 분석 설정 누락, 쿠키 보안, 프로필 혼용, 기존 로컬 동작을 검사한다. 테스트 컨테이너 연결 대신 격리된 PostgreSQL을 사용했다.

`prod` 실행 JAR을 로컬 HTTPS 역방향 프록시에 연결해 `Secure` 게스트 쿠키를 확인했다. 시연 게스트 발급 → 직접 제출 → 봉인 → 타임머신 공개에서 예시 결과가 보였고, 실패 재시도도 같은 요청으로 봉인됐다. 시연 직접 제출과 재시도의 Python HTTP 호출은 각각 0회였고, 일반 제출은 테스트 대역을 1회 호출해 봉인됐다.
