import { useCallback, useEffect, useState, type ReactNode } from "react";
import { readData } from "../../shared/api";
import {
  Button,
  Badge,
  Tabs,
  DataTable,
  Pagination,
  Skeleton,
} from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ScreenHeading } from "../shared/ScreenHeading";
import { useCodes } from "../shared/useCodes";
import { useAdminRead } from "../admin/useAdminResource";
import {
  DeveloperDisableDialog,
  DeveloperEnableButton,
  DeveloperExportButton,
} from "./DeveloperActions";
import {
  DeveloperAssignments,
  DeveloperMonthlyCosts,
  DeveloperHistory,
} from "./DeveloperReadPanels";
import {
  developerQuery,
  type DeveloperPage,
  type DeveloperRow,
  type DeveloperDetail,
  type DeveloperFilter,
} from "./client";
import styles from "./DeveloperLifecycle.module.css";

// SCR-UI-02, /UI-02. Host supplies the registration/edit/upload/work-status surfaces.
export default function DeveloperLifecycleScreen({
  actions,
  onCreate,
  onEdit,
  basicPanel,
  workStatusPanel,
}: {
  actions?: ReactNode;
  onCreate?: () => void;
  onEdit?: (id: number) => void;
  basicPanel?: (row: DeveloperDetail) => ReactNode;
  workStatusPanel?: (id: number) => ReactNode;
}) {
  const [filter, setFilter] = useState<DeveloperFilter>({ use_yn: "Y" }),
    [draft, setDraft] = useState<DeveloperFilter>({ use_yn: "Y" });
  const [page, setPage] = useState(0),
    [size, setSize] = useState<20 | 50 | 100>(50),
    [selected, setSelected] = useState<DeveloperRow[]>([]);
  const [detailId, setDetailId] = useState<number | null>(null),
    [tab, setTab] = useState("basic"),
    [disable, setDisable] = useState<DeveloperRow[] | null>(null),
    [message, setMessage] = useState("");
  const emp = useCodes("EMP_TYPE"),
    jobs = useCodes("JOB");
  const list = useAdminRead(
    useCallback(
      () =>
        readData<DeveloperPage<DeveloperRow>>(
          `/api/developers?${developerQuery(filter)}&page=${page}&size=${size}`,
        ),
      [filter, page, size],
    ),
  );
  const detail = useAdminRead(
    useCallback(
      () =>
        detailId === null
          ? Promise.resolve(null)
          : readData<DeveloperDetail>(`/api/developers/${detailId}`),
      [detailId],
    ),
  );
  const refresh = async () => {
    await list.reload();
    if (detailId !== null) await detail.reload();
    setSelected([]);
  };
  useEffect(() => {
    const close = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !disable) setDetailId(null);
    };
    document.addEventListener("keydown", close);
    return () => document.removeEventListener("keydown", close);
  }, [disable]);
  const reset = () => {
    setDraft({ use_yn: "Y" });
    setFilter({ use_yn: "Y" });
    setPage(0);
    setSelected([]);
  };
  const row = detail.data;
  return (
    <section
      data-testid="developer-lifecycle-screen"
      data-screen-id="SCR-UI-02"
    >
      <ScreenHeading
        screenId="UI-02"
        name="개발자 풀"
        groupName="기준정보"
        actions={
          <div className={styles.mutate}>
            {actions}
            {onCreate && (
              <Can perm="UI-02.create">
                <Button
                  data-testid="developer-create"
                  variant="primary"
                  onClick={onCreate}
                >
                  + 등록
                </Button>
              </Can>
            )}
          </div>
        }
      />
      {message && <p role="status">{message}</p>}
      <form
        className={styles.filters}
        data-testid="developer-search-form"
        onSubmit={(e) => {
          e.preventDefault();
          setFilter({ ...draft });
          setPage(0);
          setSelected([]);
        }}
      >
        <label>
          직원구분
          <select
            data-testid="developer-employee-filter"
            value={draft.empType ?? ""}
            onChange={(e) => setDraft({ ...draft, empType: e.target.value })}
          >
            <option value="">전체</option>
            {emp.codes.map((c) => (
              <option key={c.code} value={c.code}>
                {c.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          직무
          <select
            data-testid="developer-job-filter"
            value={draft.job ?? ""}
            onChange={(e) => setDraft({ ...draft, job: e.target.value })}
          >
            <option value="">전체</option>
            {jobs.codes.map((c) => (
              <option key={c.code} value={c.code}>
                {c.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          상태
          <select
            data-testid="developer-status-filter"
            value={draft.status ?? ""}
            onChange={(e) => setDraft({ ...draft, status: e.target.value })}
          >
            <option value="">전체</option>
            {["투입중", "철수예정", "대기", "휴직", "퇴사"].map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
        </label>
        <label>
          이름·관리번호
          <input
            data-testid="developer-query"
            placeholder="이름 또는 관리번호"
            value={draft.q ?? ""}
            onChange={(e) => setDraft({ ...draft, q: e.target.value })}
          />
        </label>
        <label>
          조회 범위
          <select
            data-testid="developer-use-filter"
            value={draft.use_yn}
            onChange={(e) => setDraft({ ...draft, use_yn: e.target.value })}
          >
            <option value="Y">사용 중</option>
            <option value="N">사용 중지자</option>
          </select>
        </label>
        <Button data-testid="developer-reset" type="button" onClick={reset}>
          초기화
        </Button>
        <Button data-testid="developer-search" type="submit" variant="primary">
          조회
        </Button>
      </form>
      <div className={styles.toolbar}>
        <span>{selected.length}건 선택</span>
        {selected.length > 0 && (
          <Can perm="UI-02.delete">
            <Button
              data-testid="developer-disable-selected"
              className={styles.mutate}
              onClick={() => setDisable(selected)}
            >
              사용 중지
            </Button>
          </Can>
        )}
        <DeveloperExportButton filter={filter} />
      </div>
      <ErrorBanner
        error={list.error}
        onRetry={() => {
          void list.reload().catch(() => {});
        }}
      />
      {list.loading ? (
        <Skeleton />
      ) : (
        !list.error && (
          <>
            <DataTable
              rows={list.data?.items ?? []}
              total={list.data?.total ?? 0}
              rowKey={(r) => String(r.dev_id)}
              showEmpty={false}
              columns={[
                {
                  key: "selected",
                  label: "선택",
                  render: (r) => (
                    <input
                      data-testid={`developer-select-${r.dev_id}`}
                      className={styles.mutate}
                      type="checkbox"
                      aria-label={`${r.dev_nm} 선택`}
                      checked={selected.some((s) => s.dev_id === r.dev_id)}
                      onChange={(e) =>
                        setSelected(
                          e.target.checked
                            ? [...selected, r]
                            : selected.filter((s) => s.dev_id !== r.dev_id),
                        )
                      }
                    />
                  ),
                },
                {
                  key: "dev_nm",
                  label: "이름",
                  render: (r) => (
                    <Button
                      data-testid={`developer-open-${r.dev_id}`}
                      variant="text"
                      onClick={() => {
                        setDetailId(r.dev_id);
                        setTab("basic");
                      }}
                    >
                      {r.dev_nm}
                    </Button>
                  ),
                },
                { key: "mng_no", label: "관리번호", render: (r) => r.mng_no },
                {
                  key: "affiliation_name",
                  label: "소속",
                  render: (r) => r.affiliation_name,
                },
                {
                  key: "emp_type_name",
                  label: "직원구분",
                  render: (r) => (
                    <Badge>{r.emp_type_name ?? r.emp_se_cd}</Badge>
                  ),
                },
                {
                  key: "grade_name",
                  label: "등급",
                  render: (r) => r.grade_name ?? "–",
                },
                {
                  key: "main_job_name",
                  label: "주 직무",
                  render: (r) => r.main_job_name ?? "–",
                },
                {
                  key: "status",
                  label: "상태",
                  render: (r) => <DeveloperStatus status={r.status} />,
                },
                {
                  key: "current_project_name",
                  label: "현재 프로젝트",
                  render: (r) =>
                    r.current_project_name
                      ? `${r.current_project_name}${r.current_assignment_count > 1 ? ` 외 ${r.current_assignment_count - 1}건` : ""}`
                      : "–",
                },
                {
                  key: "current_month_mm",
                  label: "이번 달 MM",
                  kind: "number",
                  render: (r) => (
                    <>
                      {Number(r.current_month_mm).toFixed(2)}{" "}
                      {r.over_one_mm && <Badge tone="warning">1.0 초과</Badge>}
                    </>
                  ),
                },
                {
                  key: "latest_assignment_end_dt",
                  label: "철수 예정일",
                  render: (r) => r.latest_assignment_end_dt ?? "–",
                },
                {
                  key: "dflt_untprc_amt",
                  label: "월 단가",
                  kind: "number",
                  render: (r) =>
                    r.dflt_untprc_amt?.toLocaleString("ko-KR") ?? "–",
                },
              ]}
            />
            {!list.data?.items.length && (
              <div className={styles.empty}>
                <p>조건에 맞는 결과가 없습니다.</p>
                <Button data-testid="developer-empty-reset" onClick={reset}>
                  조건 초기화
                </Button>
              </div>
            )}
            <Pagination
              page={page + 1}
              size={size}
              total={list.data?.total ?? 0}
              onPage={(n) => {
                setPage(n - 1);
                setSelected([]);
              }}
              onSize={(n) => {
                setPage(0);
                setSize(n);
                setSelected([]);
              }}
            />
          </>
        )
      )}
      {detailId !== null && (
        <div className={styles.scrim}>
          <aside
            className={styles.drawer}
            role="dialog"
            aria-label="개발자 상세"
            data-testid="developer-drawer"
          >
            <header>
              <h2>{row?.dev_nm ?? "개발자 상세"}</h2>
              <Button
                data-testid="developer-detail-close"
                aria-label="상세 닫기"
                onClick={() => setDetailId(null)}
              >
                ×
              </Button>
            </header>
            <ErrorBanner
              error={detail.error}
              onRetry={() => {
                void detail.reload().catch(() => {});
              }}
            />
            {detail.loading ? (
              <Skeleton />
            ) : (
              row && (
                <>
                  <p>
                    {row.mng_no} · {row.affiliation_name} ·{" "}
                    {row.emp_type_name ?? row.emp_se_cd}
                  </p>
                  {row.status === "퇴사" && (
                    <p className={styles.locked}>
                      퇴사한 사람입니다(퇴사일 {row.rsgn_dt}). 새 투입에 고를 수
                      없습니다.
                    </p>
                  )}
                  {row.use_yn === "N" && (
                    <p className={styles.locked}>사용을 중지한 사람입니다.</p>
                  )}
                  <div className={styles.mutate}>
                    {onEdit && (
                      <Can perm="UI-02.update">
                        <Button
                          data-testid="developer-edit"
                          onClick={() => onEdit(row.dev_id)}
                        >
                          수정
                        </Button>
                      </Can>
                    )}
                    {row.use_yn === "N" ? (
                      <DeveloperEnableButton
                        id={row.dev_id}
                        reload={refresh}
                        onComplete={setMessage}
                      />
                    ) : (
                      <Can perm="UI-02.delete">
                        <Button
                          data-testid="developer-detail-disable"
                          onClick={() => setDisable([row])}
                        >
                          사용 중지
                        </Button>
                      </Can>
                    )}
                  </div>
                  <Tabs
                    selected={tab}
                    onChange={setTab}
                    items={[
                      { key: "basic", label: "기본 정보" },
                      { key: "assignments", label: "투입 이력" },
                      { key: "work", label: "근무 상태 이력" },
                      { key: "costs", label: "월별 원가" },
                      { key: "history", label: "변경 이력" },
                    ]}
                  />
                  {tab === "basic" && (
                    <section
                      role="tabpanel"
                      data-testid="developer-basic-panel"
                    >
                      {basicPanel ? (
                        basicPanel(row)
                      ) : (
                        <dl>
                          {[
                            ["관리번호", row.mng_no],
                            ["이름", row.dev_nm],
                            ["소속", row.affiliation_name],
                            ["직원구분", row.emp_type_name ?? row.emp_se_cd],
                            ["등급", row.grade_name],
                            ["주 직무", row.main_job_name],
                            ["보조 직무", row.sub_job?.join(", ")],
                            ["스킬", row.skill?.join(", ")],
                            ["연락처", row.tel_no],
                            ["이메일", row.email_addr],
                            ["비고", row.rmrk_desc],
                            [
                              "월 단가",
                              row.dflt_untprc_amt?.toLocaleString("ko-KR"),
                            ],
                          ].map(([label, value]) => (
                            <div key={label}>
                              <dt>{label}</dt>
                              <dd>{value ?? "–"}</dd>
                            </div>
                          ))}
                          <div>
                            <dt>현재 상태</dt>
                            <dd>
                              <DeveloperStatus status={row.status} />
                            </dd>
                          </div>
                          <div data-sensitive="true">
                            <dt>최근 월 급여</dt>
                            <dd>
                              {row.latest_salary
                                ? `${row.latest_salary.month} ${row.latest_salary.slry_amt.toLocaleString("ko-KR")} 원`
                                : "–"}
                            </dd>
                          </div>
                        </dl>
                      )}
                    </section>
                  )}
                  {tab === "assignments" && (
                    <DeveloperAssignments id={row.dev_id} />
                  )}
                  {tab === "costs" && <DeveloperMonthlyCosts id={row.dev_id} />}
                  {tab === "history" && <DeveloperHistory id={row.dev_id} />}
                  {tab === "work" &&
                    (workStatusPanel ? (
                      workStatusPanel(row.dev_id)
                    ) : (
                      <DeveloperWorkStatusRead id={row.dev_id} />
                    ))}
                </>
              )
            )}
          </aside>
        </div>
      )}
      {disable && (
        <DeveloperDisableDialog
          rows={disable}
          onClose={() => setDisable(null)}
          reload={refresh}
          onComplete={setMessage}
        />
      )}
    </section>
  );
}
export function DeveloperStatus({ status }: { status: string }) {
  return (
    <Badge
      tone={
        status === "투입중"
          ? "info"
          : status === "철수예정"
            ? "warning"
            : status === "대기"
              ? "danger"
              : "locked"
      }
    >
      {status}
    </Badge>
  );
}
function DeveloperWorkStatusRead({ id }: { id: number }) {
  const state = useAdminRead(
    useCallback(
      () =>
        readData<
          DeveloperPage<{
            work_stts_hist_id: number;
            work_stts_cd: string;
            bgn_dt: string;
            end_dt: string | null;
            paid_yn: string;
            rmrk_desc: string | null;
          }>
        >(`/api/developers/${id}/work-status`),
      [id],
    ),
  );
  const codes = useCodes("WORK_STATUS");
  return (
    <section role="tabpanel" data-testid="developer-work-status-read">
      <ErrorBanner
        error={state.error}
        onRetry={() => {
          void state.reload().catch(() => {});
        }}
      />
      {state.loading ? (
        <Skeleton />
      ) : (
        !state.error &&
        (state.data?.items.length ? (
          <DataTable
            rows={state.data.items}
            total={state.data.total}
            rowKey={(r) => String(r.work_stts_hist_id)}
            columns={[
              {
                key: "work_stts_cd",
                label: "상태",
                render: (r) =>
                  codes.codes.find((c) => c.code === r.work_stts_cd)?.name ??
                  r.work_stts_cd,
              },
              { key: "bgn_dt", label: "시작일", render: (r) => r.bgn_dt },
              {
                key: "end_dt",
                label: "종료일",
                render: (r) => r.end_dt ?? "–",
              },
              {
                key: "paid_yn",
                label: "유급",
                render: (r) =>
                  r.work_stts_cd === "VACATION"
                    ? r.paid_yn === "Y"
                      ? "유급"
                      : "무급"
                    : "–",
              },
              {
                key: "rmrk_desc",
                label: "메모",
                render: (r) => r.rmrk_desc ?? "–",
              },
            ]}
          />
        ) : (
          <p>등록된 근무 상태가 없습니다.</p>
        ))
      )}
    </section>
  );
}
