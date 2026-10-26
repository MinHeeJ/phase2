# UI 개선 계약 체크리스트

## 구현 전 대조

- 모든 API 메뉴 leaf /UI-*: 사용자 목적은 메뉴 탐색과 현재 위치 확인. 메뉴명/부모/아이콘/mobile은 GET /api/menus 응답만 사용한다. React Router와 보존 탭 구조를 유지한다. 즐겨찾기, 최근 화면, 검색, 접힘, 현재 route 강조를 제공한다. 인증/역할별 제한은 현재 계약에 없으므로 만들지 않는다.
- YEAR /UI-01, /UI-03, /UI-04, /UI-05, /UI-10, /UI-11, /UI-12, /UI-13, /UI-17, /UI-21, /UI-28: 기간 확인용 화면. 이전/다음 연도와 즐겨찾기만 제공하며 업무 입력/저장 payload는 없다. 화면별 기간 유지, 메뉴 loading/error/empty 상태를 제공한다.
- YEAR_CUM /UI-07, /UI-08, /UI-09, /UI-18: 연도와 누계월을 선택하고 1~선택월 문구를 함께 갱신한다. 서버 쓰기는 없다.
- MONTH /UI-14, /UI-19, /UI-20: 선택월로 GET /api/month-close 조회. 월 선택 유지, CLOSED/OPEN 배지와 잠금 안내, 독립 loading/error/retry. 이전 요청의 상태를 다른 월로 표시하지 않는다.
- NONE /UI-16, /UI-22, /UI-23, /UI-24, /UI-25 및 나머지 실제 DB 메뉴: 읽기 전용 다음 단계 안내만 제공한다. 대시보드 KPI/업무 폼을 합성하지 않는다. /UI-22는 메뉴 없는 직접 진입도 유지한다.
- /UI-27 코드: 목록/인라인 편집. GET code-groups와 group/codes의 모든 페이지 수집, 첫 그룹 선택. BIZ 신규 코드만 식별자 입력; 기존 식별자 readonly; SYSTEM은 이름/순서만 편집. 동적 추가값 열 유지. 사용 체크는 draft에 반영하고 PUT 전체 저장 시 확정, 물리 삭제 없음. 취소는 원본 복원, 실패는 draft/field 오류 유지, 성공은 그룹/코드 재조회. 그룹 추가는 POST에 ID/이름/추가값 이름만 포함한다.
- /UI-27 대응: 전체 폭 목록/편집. GET excel-maps, POST 생성, PATCH는 변경된 map_role_cd/rmrk_desc/use_yn만 포함. 표기/대상은 기존 행에서 readonly. 생성의 그룹/활성 코드 선택은 실제 API 연동, 주/보조는 사용자 명시 선택. 실패는 창과 입력 유지, 성공은 재조회. 기존 추천 결정의 명시 저장/취소 방식 유지.
- /UI-27 기준값: 현재값과 적용 이력 분리. GET params/versions, PUT 선택 key/month에 새 값 하나만 포함. 이름/단위/쓰는 곳은 readonly. 전체 CLOSED 경계 확보 전 저장 차단, 마지막 마감월 이하 금지, 수치/비율/문자 길이 검증. 성공은 현재목록/이력 갱신, 미래 적용값을 현재값으로 오인하지 않는다.
- /UI-27 변경 이력: 읽기 전용 조회. 그룹 필터/Enter/조회, 초기화는 필터만 해제. 서버 total 기반 기본50/20·50·100 페이지, 처음/이전/번호/다음/마지막과 조회 범위 제공. 편집/삭제 없음.
- 공통: 비인증 누구나 접근, SYSTEM/마감/모바일 제약은 역할 차단과 구분. mobile=false 휴대폰 직접 진입은 PC 안내/대시보드로만. 대화상자 pending 중 재전송 방지, 미저장 이탈 확인, 오류 상단 띠/문의번호/재시도, 성공 후 재조회. 데이터가 없기 전에 빈 결과를 먼저 표시하지 않는다.

## 레퍼런스 및 근거 한계

요청 URL을 기본 프록시와 직접 접속으로 시도했으나 각각 curl 52(빈 응답), 6(DNS 실패). Docker Desktop 내부 주소의 동일 Host 요청도 연결 거절. ui-style.md 역시 미검증 차단 기록이므로 사이트를 보았다고 주장하지 않는다. 이 변경의 디자인 기준은 ui-design.md의 명시된 색/크기/밀도/계층과 기존 CSS Modules이다. 참조 사이트의 픽셀 일치는 검증 불가다.

.aiops-spec/code-context/implementation-map.json과 .aiops-spec/contracts/openapi.yaml은 파일 도구에서 찾지 못했다. backend/src/test/resources/contracts/openapi.yaml의 CodeSaveRow/ExcelMapCreate/ExcelMapPatch/ParamValueSave, 실제 controller/service/DTO와 기존 API client를 대조했다. 읽기 전용 사본은 수정하지 않는다.

## 구현 후 대조 및 실행 결과

- npm run build: TypeScript 검사와 Vite 프로덕션 빌드 성공. dist 산출물 생성.
- npm test -- --run: Vitest + jsdom 12개 파일, 183개 시험 통과. 소스를 읽어 문자열을 대조하는 시험은 추가하지 않았다.
- 기존 route 시험과 자리 화면 시험 유지: DB 메뉴 leaf와 UI-22 직접 진입, 기간/마감/모바일 제한, 보존 탭과 미저장 보호를 렌더링으로 검증한다. 임의 메뉴/업무 화면을 합성하지 않았다.
- UI-27 시험: SYSTEM readonly/사용 비활성/추가 제한, 전체 페이지 PUT, 실패 draft/필드 focus, 중복 클릭 방지, 대응 immutable 필드 제외 PATCH, 명시 주/보조 선택, 마감월 차단/기준값 이력, 이력 조회와 조건 초기화를 확인했다.
- 신규 공통 시험: 최초 loading/error를 성공한 empty와 구분, 재조회 행 유지, 좁게 보기 저장과 실제 표 반영, 서버 total 범위/첫·끝 페이지, 검색 빈 결과, 태블릿 접기/펼치기, 제목줄 action 배치 확인.
- 기존 선택인 CSS Modules/로컬 Pretendard/lucide-react만 사용. 새 UI 의존성/인증/역할 정책/서버 API/DB 변경 없음.
- 현재 권한은 Can=true인 비인증 계약이므로 임의 역할을 추가해 가짜 역할별 노출을 만들지 않았다. 서버 SYSTEM/마감 제한과 휴대폰 메뉴 제한은 별도로 시험했다.
- 변경 파일 줄 끝 공백 검사: 위반 없음. React Router v7 future-flag 안내 경고는 남아 있으며 시험 실패는 없다.

미완료 검증: 레퍼런스 직접 관찰·픽셀 비교는 네트워크 오류로 수행하지 못했다. Playwright Chromium 설치도 scratch 파일시스템 용량 제한(ENOSPC)으로 실패했다. 따라서 실제 브라우저에서 360/375/1024/1280px의 픽셀·overflow 측정은 완료 주장하지 않는다. Docker daemon 연결도 불가능하여 실제 DB 연동 E2E는 이번 변경에서 재실행하지 못했다. API 연결에 관한 이번 실행 증거는 실제 DTO/서비스 대조 및 렌더링·클라이언트 시험이며, 실DB 저장 성공으로 대체하지 않는다.
