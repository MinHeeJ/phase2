앱 사용 확인 로그인 403 수정 검증

원인
- aiops.runtime-check.json은 createLogin을 JSON POST로 직접 호출하고 success=true 및 data.status=AUTHENTICATED를 요구한다.
- SecurityConfiguration은 이 공개 로그인에도 CSRF 사전 요청을 강제하여 컨트롤러·인증 서비스 진입 전에 403을 반환했다.
- SmokeAccountInitializer와 로그인 Mapper·서비스를 먼저 검토했다. 시드 비밀번호는 원문을 변경하지 않고 해싱하며, 이번 실패를 해결하기 위해 계정 정책이나 시드 권한을 완화하지 않았다.
- 기존 로컬 검증기는 별도 CSRF 사전 요청을 수행하여 Quality Agent의 직접 로그인 호출과 달랐다.

수정
- POST /api/auth/login의 application/json 요청 중 Origin과 Sec-Fetch-Site가 모두 없는 비브라우저 요청만 CSRF 사전 요청 없이 처리한다. JSON 매개변수(charset)는 허용하며 다른 경로·메서드·미디어 타입에는 확장하지 않는다.
- 브라우저 로그인은 기존 CSRF 검사를 유지한다. 교차 출처·opaque origin 거부, 인증된 쓰기의 CSRF, 세션 검증, MFA, 비밀번호 정책, 인가 및 속도 제한은 유지한다.
- 로컬 검증기도 runtime-check의 로그인 연산을 사전 요청 없이 직접 실행하도록 수정했다.
- 로그인 회귀 시험의 MockMvc 본문·헤더 자동 출력을 비활성화했다. 검증 실행에서는 HTTP 데이터와 계정·쿠키·토큰을 출력하거나 결과 기록에 저장하지 않았다.

실제 실행 결과
- 수정 전 새 로그인 회귀 시험: 19개 중 1개 실패, 기대 HTTP 200 / 실제 403 재현.
- 수정 후 로그인 API 시험: 19개 통과.
- 인증·시드·초기 관리자·durable OpenAPI·인가·세션·익명 접근·운영 보안·보호 API·업로드 계약 시험: 92개 통과, 실패·오류·건너뜀 없음.
- 백엔드 및 프런트 Docker 빌드와 두 런타임 이미지 빌드: 통과. 프런트 빌드는 TypeScript 검사도 포함한다.
- Vitest/jsdom AuthenticationFlow, AppShell, SystemAdministration: 3개 파일, 22개 시험 통과. 역할별 메뉴, 권한별 버튼, 폼 입력 동작을 렌더링으로 검사했다.
- 인프라 unittest discover: 14개 통과. 직접 로그인·쿠키 기반 사용자 식별과 민감정보 미출력 회귀 시험을 포함한다.
- Compose 빌드 컨텍스트 제외 규칙 및 git diff --check: 통과.
- 격리된 실제 Compose 앱: db/backend/frontend healthy, JSON health, 사전 요청 없는 로그인, 역할 포함 identity, 첫 업무 목록, nginx API 프록시, 화면 경로 모두 통과.
- 동일 DB에서 확인 계정 비밀번호를 교체하고 backend를 재생성한 후 같은 앱 사용 확인을 다시 통과했다.
- 두 런타임 서비스의 비루트 실행을 실제 id -u로 확인했다. Dockerfile, nginx 포트·pid·캐시 경로와 Compose 헬스체크는 변경하지 않았다.
- 검증 전용 Compose 프로젝트와 볼륨을 정리했다. 검증 컨테이너가 남아 있지 않음을 확인했다.

전체 품질 게이트의 남은 차단 사항
- 전체 Maven verify는 실패했다. 실행 집계는 4742개, 실패 299개, 오류 2412개, 건너뜀 696개다. 기존 문서에도 기록된 MVC 슬라이스의 AuthorizationService 빈 부재 등 ApplicationContext 초기화 실패가 포함된다. 이번 로그인 수정 범위에서 시험을 삭제하거나 인증·인가를 우회하지 않았다.
- 전체 Vitest는 첫 실행이 도구 시간 제한에 걸렸고, 제한된 worker 재실행도 완료되지 않아 해당 시험 컨테이너를 종료했다. 전체 시험 통과로 간주하지 않는다.
- npm audit는 critical 2, high 6, moderate 2, 총 10개를 보고했다. 이번 변경은 frontend package.json 및 package-lock.json을 수정하지 않았다. 보안 검사를 제거하거나 취약점을 통과로 숨기지 않았다.
- 따라서 APP_LOGIN_FAILED의 직접 로그인 경로는 실제 재검증을 통과했으나 전체 품질 게이트 통과를 의미하지 않는다.

계약 경계
- Java OpenAPI 시험은 backend/src/test/resources/contracts/openapi.yaml을 ClassPathResource("contracts/openapi.yaml")로 읽는 기존 방식을 유지한다.
- 생성·런타임·시험 코드에 .aiops-spec 참조를 추가하지 않았으며 해당 디렉터리를 재생성하지 않았다.
- 프런트 시험에서 소스 파일 읽기나 raw import에 의존하는 문자열 비교 시험을 추가하지 않았다.
