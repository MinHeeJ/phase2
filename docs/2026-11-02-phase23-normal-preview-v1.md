# Phase 23 정상 마감 미리보기

`infra/phase23-normal-preview.sh`는 전용 Compose 프로젝트와 PostgreSQL 볼륨을 새로 만들고, 2026-10을 실제 앱 API로 마감한 뒤 2026-11을 OPEN으로 남긴다. 기존 phase-23 미리보기 DB, V1/V2 마이그레이션, 과거 마감 데이터는 변경하지 않는다.

저장소 루트에서 실행한다.

```bash
bash infra/phase23-normal-preview.sh
```

기본 화면은 `http://127.0.0.1:33123/UI-14`다. 포트나 프로젝트명을 바꾸려면 `PHASE23_NORMAL_FRONTEND_PORT`, `PHASE23_NORMAL_BACKEND_PORT`, `PHASE23_NORMAL_PROJECT`를 지정한다. 이미 같은 프로젝트의 DB 볼륨이 있으면 스크립트는 기존 데이터를 덮어쓰지 않고 중단한다.

전용 DB에서만 `infra/fixtures/phase23-normal.sql`이 V2의 예시 CLOSED 상태 1~9월을 제거한다. 이 행에는 스냅샷과 마감 이력이 없으므로 정상 마감의 근거로 쓰지 않는다. 이후 `infra/phase23-normal-seed.py`가 법인세 계획, 10월 기초 잔액과 재무 항목을 저장하고 공개 API로 10월 점검·미리보기·확정을 실행한다. 10월의 스냅샷 조회와 CLOSE 이력 1건을 검증한 다음 11월 재무 항목·9종 점검·마감 미리보기를 준비한다.

검증할 수 있는 상태:

- 2026-10: CLOSED, 저장된 스냅샷과 마감 이력 조회 가능, 최신 월 해제 버튼 표시.
- 2026-11: OPEN, 9종 저장 점검과 마감 미리보기 가능, 10월을 원월로 하는 전월 조정 입력 가능.

컨테이너를 멈출 때는 아래 명령을 사용한다. 볼륨은 유지된다.

```bash
docker compose -p sicost-phase23-normal -f infra/docker-compose.yml -f infra/phase23-normal-compose.yml down
```

이 프로젝트의 시험 데이터만 완전히 지우려면 같은 명령에 `-v`를 추가한다.
