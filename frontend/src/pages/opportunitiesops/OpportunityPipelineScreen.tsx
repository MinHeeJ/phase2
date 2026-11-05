import { useCallback, useState } from "react";
import { Link } from "react-router-dom";
import {
  Badge,
  Button,
  DataTable,
  EmptyState,
  Modal,
  Pagination,
  Skeleton,
  Tabs,
} from "../../shared/Controls";
import { ApiFailure } from "../../shared/api";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { ScreenHeading } from "../shared/ScreenHeading";
import { emptyProject, type ProjectInput } from "../projects/client";
import { validateProject } from "../projects/ProjectForm";
import { ForecastContent } from "./WorkforceDemandScreen";
import {
  createOpportunityWin,
  exportOpportunities,
  getOpportunity,
  getOpportunityForecast,
  listOpportunities,
  updateOpportunityApproval,
  updateOpportunityDisable,
  updateOpportunityLoss,
  updateOpportunityRejection,
  updateOpportunityStage,
  type OpportunityDetail,
  type OpportunityFilters,
} from "./client";
import styles from "./OpportunityLifecycle.module.css";
import type { OpportunityDetail as EditableOpportunity } from "../opportunities/client";
import { useOpportunityPermissions } from "./useOpportunityPermissions";

const stages = [
  { key: "DISC", label: "발굴", probability: 10 },
  { key: "PROP", label: "제안", probability: 30 },
  { key: "NEGO", label: "협상", probability: 60 },
  { key: "PRE", label: "계약 직전", probability: 90 },
] as const;
const amount = (n: number | null) =>
  n === null ? "권한에 따라 비공개" : n.toLocaleString("ko-KR");
type Action = "approve" | "reject" | "lose" | "disable" | "win";
const labels = {
  approve: "견적 승인",
  reject: "견적 반려",
  lose: "실주 처리",
  disable: "삭제",
  win: "수주 처리",
};
const failure = (e: unknown) =>
  e instanceof ApiFailure
    ? e
    : new ApiFailure("처리하지 못했습니다.", null, null);
export function OpportunityActionDialog({
  row,
  action,
  onCancel,
  onSaved,
}: {
  row: OpportunityDetail | EditableOpportunity;
  action: Action;
  onCancel: () => void;
  onSaved: () => Promise<unknown>;
}) {
  const [text, setText] = useState(""),
    [evidence, setEvidence] = useState(""),
    [local, setLocal] = useState<string | null>(null);
  const [project, setProject] = useState<ProjectInput>(() => {
    const [year, month] = row.bgn_ym.split("-").map(Number);
    const end = new Date(Date.UTC(year, month - 1 + row.mth_cnt, 0))
      .toISOString()
      .slice(0, 10);
    return {
      ...emptyProject,
      prj_nm: row.oppt_nm,
      cust_ptnr_id: row.cust_ptnr_id,
      bsn_part_id: row.bsn_part_id,
      cntrct_ty_cd: row.cntrct_ty_cd,
      cntrct_bgn_dt: `${row.bgn_ym}-01`,
      cntrct_end_dt: end,
      init_cntrct_amt:
        row.quote.approved_quote_amount ?? row.quote.expc_sls_amt,
      stlm_mthd_cd:
        row.cntrct_ty_cd === "TK"
          ? "STAGE"
          : row.cntrct_ty_cd === "IN"
            ? null
            : "MONTH",
    };
  });
  const write = useAdminWrite(onSaved);
  const submit = () => {
    setLocal(null);
    if ((action === "reject" || action === "lose") && !text.trim()) {
      setLocal("사유를 입력하세요.");
      return;
    }
    if (text.trim().length > 500) {
      setLocal("500자 이내로 입력하세요.");
      return;
    }
    if (action === "win") {
      const error = validateProject(project, false);
      if (error) {
        setLocal(error.message);
        return;
      }
    }
    const command = () =>
      action === "approve"
        ? updateOpportunityApproval(
            row.oppt_id,
            text.trim(),
            evidence.trim() || undefined,
          )
        : action === "reject"
          ? updateOpportunityRejection(row.oppt_id, text.trim())
          : action === "lose"
            ? updateOpportunityLoss(row.oppt_id, text.trim())
            : action === "disable"
              ? updateOpportunityDisable(row.oppt_id)
              : createOpportunityWin(row.oppt_id, project);
    void write.save(command, onCancel);
  };
  const outside =
    action === "win" &&
    row.needs.some(
      (n) =>
        n.bgn_ym < project.cntrct_bgn_dt.slice(0, 7) ||
        new Date(
          Date.UTC(
            Number(n.bgn_ym.slice(0, 4)),
            Number(n.bgn_ym.slice(5, 7)) - 1 + n.mth_cnt - 1,
            1,
          ),
        )
          .toISOString()
          .slice(0, 7) > project.cntrct_end_dt.slice(0, 7),
    );
  return (
    <Modal
      title={labels[action]}
      onCancel={onCancel}
      confirmation={action === "disable"}
    >
      <form
        data-testid="opportunity-action-dialog"
        className={styles.form}
        onSubmit={(e) => {
          e.preventDefault();
          submit();
        }}
      >
        <p>
          {row.oppt_no} · {row.oppt_nm}
        </p>
        {action === "disable" ? (
          <p>
            기회를 삭제하고 활성 가배정을 해제합니다. 원본·필요 인력·이력은
            보존됩니다.
          </p>
        ) : action === "win" ? (
          <>
            <p className={styles.warning}>
              최종 견적금액으로 프로젝트를 등록하고 월별 계획과 모든 활성
              가배정을 함께 전환합니다. 실패하면 모두 취소됩니다.
            </p>
            <label>
              프로젝트명
              <input
                data-testid="win-name"
                maxLength={100}
                value={project.prj_nm}
                onChange={(e) =>
                  setProject({ ...project, prj_nm: e.target.value })
                }
              />
            </label>
            <label>
              확정 계약금액(공급가)
              <input
                data-testid="win-amount"
                readOnly
                value={project.init_cntrct_amt ?? ""}
              />
            </label>
            <label>
              계약 시작일
              <input
                data-testid="win-start"
                type="date"
                value={project.cntrct_bgn_dt}
                onChange={(e) =>
                  setProject({ ...project, cntrct_bgn_dt: e.target.value })
                }
              />
            </label>
            <label>
              계약 종료일
              <input
                data-testid="win-end"
                type="date"
                value={project.cntrct_end_dt}
                onChange={(e) =>
                  setProject({ ...project, cntrct_end_dt: e.target.value })
                }
              />
            </label>
            <p>
              고객 {row.cust_ptnr_id} · 계약유형 {row.cntrct_ty_cd} · 영업 파트{" "}
              {row.bsn_part_id ?? "–"}
            </p>
            <p>
              필요 인력 {row.needs.reduce((sum, n) => sum + n.psn_cnt, 0)}명 ·
              계획 자동 삭제·변경 없음
            </p>
            {outside && (
              <p role="alert">
                계약기간 밖 필요 인력 계획이 있습니다. 기회를 수정한 후 다시
                시도하세요.
              </p>
            )}
            {row.quote.cost_status === "UNKNOWN" && (
              <p role="alert">
                원가 미확인: 원가 자료 등록과 재계산 전에는 수주할 수 없습니다.
              </p>
            )}
          </>
        ) : (
          <>
            <label>
              {action === "approve" ? "승인 의견 (선택)" : "사유 (필수)"}
              <textarea
                data-testid="opportunity-reason"
                value={text}
                onChange={(e) => setText(e.target.value)}
                maxLength={501}
              />
            </label>
            {action === "approve" && row.quote.expc_cst_amt === 0 && (
              <label>
                실제 0원 원가 증빙 (기존 원천 참조)
                <input
                  data-testid="zero-cost-evidence"
                  value={evidence}
                  onChange={(e) => setEvidence(e.target.value)}
                />
                <small>
                  서버가 관련 cst.mth_cst 원천·인력·경비와 현재 견적을
                  검증합니다.
                </small>
              </label>
            )}
          </>
        )}
        {local && <p role="alert">{local}</p>}
        <ErrorBanner error={write.error} />
        {write.error?.field && (
          <p role="alert">
            {write.error.field}: {write.error.message}
          </p>
        )}
        <div className={styles.actions}>
          <Button
            data-testid="opportunity-action-cancel"
            type="button"
            onClick={onCancel}
            disabled={write.pending}
          >
            취소
          </Button>
          <Button
            data-testid="opportunity-action-save"
            type="submit"
            variant="primary"
            pending={write.pending}
            disabled={
              write.uncertain ||
              outside ||
              (action === "win" && row.quote.cost_status === "UNKNOWN")
            }
          >
            {labels[action]}
          </Button>
          {write.uncertain && (
            <Button
              data-testid="opportunity-action-recover"
              type="button"
              onClick={() => void write.recover()}
            >
              현재 상태 확인
            </Button>
          )}
        </div>
      </form>
    </Modal>
  );
}
function OpportunityDrawer({
  id,
  onClose,
  onChanged,
  onEdit,
}: {
  id: number;
  onClose: () => void;
  onChanged: () => Promise<unknown>;
  onEdit?: (id?: number) => void;
}) {
  const read = useAdminRead(useCallback(() => getOpportunity(id), [id]));
  const permissions = useOpportunityPermissions();
  const [tab, setTab] = useState("info"),
    [action, setAction] = useState<Action | null>(null);
  const row = read.data;
  return (
    <div className={styles.scrim}>
      <aside
        className={styles.drawer}
        role="dialog"
        aria-modal="true"
        aria-label="기회 상세"
        data-testid="opportunity-detail"
      >
        <Button data-testid="opportunity-detail-close" onClick={onClose}>
          닫기
        </Button>
        <ErrorBanner
          error={read.error}
          onRetry={() => void read.reload().catch(() => {})}
        />
        {read.loading ? (
          <Skeleton />
        ) : (
          !read.error &&
          row && (
            <>
              <h2>{row.oppt_nm}</h2>
              <p>
                {row.oppt_no} ·{" "}
                <Badge
                  tone={
                    row.oppt_stts_cd === "WON"
                      ? "success"
                      : row.oppt_stts_cd === "LOST"
                        ? "danger"
                        : "neutral"
                  }
                >
                  {row.oppt_stts_cd}
                </Badge>
              </p>
              <Tabs
                items={[
                  { key: "info", label: "정보" },
                  { key: "needs", label: "필요 인력" },
                  { key: "approval", label: "견적 점검·승인 이력" },
                ]}
                selected={tab}
                onChange={setTab}
              />
              <div role="tabpanel" data-testid="opportunity-detail-tab">
                {tab === "info" ? (
                  <dl>
                    <dt>예상 금액(공급가)</dt>
                    <dd>{amount(row.expc_amt)}원</dd>
                    <dt>수주 확률</dt>
                    <dd>{row.win_prblt_rt}%</dd>
                    <dt>예상 기간</dt>
                    <dd>
                      {row.bgn_ym}부터 {row.mth_cnt}개월
                    </dd>
                    <dt>다음 할 일</dt>
                    <dd>
                      {row.next_action ?? "–"} · {row.next_action_dt ?? "–"}
                    </dd>
                  </dl>
                ) : tab === "needs" ? (
                  <DataTable
                    rows={row.needs}
                    total={row.needs.length}
                    rowKey={(n) => String(n.need_id)}
                    columns={[
                      {
                        key: "job",
                        label: "직무·등급",
                        render: (n) => `${n.job_cd} ${n.grd_cd ?? ""}`,
                      },
                      {
                        key: "people",
                        label: "인원",
                        render: (n) => n.psn_cnt,
                      },
                      {
                        key: "period",
                        label: "기간",
                        render: (n) => `${n.bgn_ym} / ${n.mth_cnt}개월`,
                      },
                      {
                        key: "mm",
                        label: "월 MM",
                        render: (n) => n.mth_mm_qty,
                      },
                      {
                        key: "price",
                        label: "예상 단가",
                        render: (n) => amount(n.expc_untprc_amt),
                      },
                    ]}
                  />
                ) : (
                  <>
                    <p>
                      견적 매출 {amount(row.quote.expc_sls_amt)}원 / 원가{" "}
                      {amount(row.quote.expc_cst_amt)}원 / 이익률{" "}
                      {row.quote.expc_mrgn_rt ?? "–"}%
                    </p>
                    <Badge
                      tone={
                        row.quote.approval_state === "PENDING"
                          ? "warning"
                          : row.quote.approval_state === "REJECTED"
                            ? "danger"
                            : "success"
                      }
                    >
                      {row.quote.approval_state}
                    </Badge>
                    <DataTable
                      rows={row.approval_history}
                      total={row.approval_history.length}
                      rowKey={(r) => `${r.created_at}:${r.action}`}
                      columns={[
                        {
                          key: "action",
                          label: "처리",
                          render: (r) => r.action,
                        },
                        {
                          key: "opinion",
                          label: "의견",
                          render: (r) => r.opinion ?? "–",
                        },
                        {
                          key: "actor",
                          label: "처리자",
                          render: (r) => r.user_id ?? "–",
                        },
                        {
                          key: "date",
                          label: "처리 일시",
                          render: (r) => r.created_at,
                        },
                      ]}
                    />
                  </>
                )}
              </div>
              {row.warnings.map((w, i) => (
                <p className={styles.warning} key={i}>
                  {w}
                </p>
              ))}
              <div className={styles.actions}>
                {row.oppt_stts_cd === "OPEN" ? (
                  <>
                    {onEdit && permissions.mdfcn && (
                      <Button
                        data-testid="opportunity-edit"
                        onClick={() => onEdit(row.oppt_id)}
                      >
                        수정
                      </Button>
                    )}
                    {permissions.cnfrm &&
                      row.quote.approval_state === "PENDING" && (
                        <>
                          <Button
                            data-testid="opportunity-approve"
                            onClick={() => setAction("approve")}
                          >
                            승인
                          </Button>
                          <Button
                            data-testid="opportunity-reject"
                            onClick={() => setAction("reject")}
                          >
                            반려
                          </Button>
                        </>
                      )}
                    <Button
                      data-testid="opportunity-win"
                      disabled={
                        !permissions.cnfrm ||
                        row.quote.approval_state === "PENDING" ||
                        row.quote.approval_state === "REJECTED" ||
                        row.quote.cost_status === "UNKNOWN"
                      }
                      onClick={() => setAction("win")}
                    >
                      수주 처리
                    </Button>
                    <Button
                      data-testid="opportunity-lose"
                      disabled={!permissions.mdfcn}
                      onClick={() => setAction("lose")}
                    >
                      실주 처리
                    </Button>
                    <Button
                      data-testid="opportunity-disable"
                      disabled={!permissions.del}
                      onClick={() => setAction("disable")}
                    >
                      삭제
                    </Button>
                  </>
                ) : (
                  <p>종료된 기회는 수정할 수 없습니다.</p>
                )}
              </div>
              {row.win_prj_id && (
                <Link
                  data-testid="opportunity-project-link"
                  to={`/UI-01?open=${row.win_prj_id}`}
                >
                  프로젝트 보기
                </Link>
              )}
              {action && (
                <OpportunityActionDialog
                  row={row}
                  action={action}
                  onCancel={() => setAction(null)}
                  onSaved={async () => {
                    await read.reload();
                    await onChanged();
                  }}
                />
              )}
            </>
          )
        )}
      </aside>
    </div>
  );
}
// SCR-UI-29: lifecycle extension of the single pipeline; registration/editing is supplied by its existing resource screen.
export default function OpportunityPipelineScreen({
  onEdit,
}: {
  onEdit?: (id?: number) => void;
}) {
  const permissions = useOpportunityPermissions();
  const [tab, setTab] = useState("open"),
    [filters, setFilters] = useState<OpportunityFilters>({
      oppt_stts_cd: "OPEN",
      page: 0,
      size: 50,
    }),
    [search, setSearch] = useState("");
  const [selected, setSelected] = useState<number | null>(null),
    [error, setError] = useState<ApiFailure | null>(null),
    [busy, setBusy] = useState(false);
  const list = useAdminRead(
    useCallback(() => listOpportunities(filters), [filters]),
  );
  const forecast = useAdminRead(
    useCallback(
      () =>
        tab === "forecast" ? getOpportunityForecast() : Promise.resolve(null),
      [tab],
    ),
  );
  const move = async (id: number, stage: string) => {
    if (!permissions.mdfcn) return;
    setBusy(true);
    setError(null);
    try {
      await updateOpportunityStage(id, stage);
      await list.reload();
    } catch (e) {
      setError(failure(e));
    } finally {
      setBusy(false);
    }
  };
  const download = async () => {
    setBusy(true);
    setError(null);
    try {
      await exportOpportunities(filters);
    } catch (e) {
      setError(failure(e));
    } finally {
      setBusy(false);
    }
  };
  const kpi = list.data?.kpi;
  return (
    <section
      data-screen-id="SCR-UI-29"
      data-testid="opportunity-pipeline-screen"
      className={styles.screen}
    >
      <ScreenHeading
        screenId="UI-29"
        name="영업 파이프라인"
        groupName="인력 계획"
        actions={
          <>
            <Button
              data-testid="opportunity-export"
              disabled={!permissions.dwnld}
              pending={busy}
              onClick={() => void download()}
            >
              엑셀 다운로드
            </Button>
            {onEdit && permissions.reg && (
              <Button
                data-testid="opportunity-create"
                variant="primary"
                onClick={() => onEdit()}
              >
                기회 등록
              </Button>
            )}
          </>
        }
      />
      {kpi && (
        <div className={styles.kpis}>
          {[
            ["진행 기회", kpi.open_count],
            ["예상 금액", amount(kpi.expected_amount)],
            ["확률 반영 금액", amount(kpi.weighted_amount)],
            ["필요 인력", kpi.needed_people],
          ].map(([label, value]) => (
            <div key={label}>
              {label}
              <strong>{value}</strong>
            </div>
          ))}
        </div>
      )}
      <Tabs
        items={[
          { key: "open", label: "진행 중" },
          { key: "closed", label: "종료 · 수주·실주" },
          { key: "forecast", label: "매출·인력 예측" },
        ]}
        selected={tab}
        onChange={(value) => {
          setTab(value);
          setFilters({
            ...filters,
            oppt_stts_cd: value === "closed" ? "WON" : "OPEN",
            page: 0,
          });
        }}
      />
      <ErrorBanner error={error} />
      {tab === "forecast" ? (
        <div role="tabpanel" data-testid="pipeline-forecast">
          <ErrorBanner
            error={forecast.error}
            onRetry={() => void forecast.reload().catch(() => {})}
          />
          {forecast.loading ? (
            <Skeleton />
          ) : (
            forecast.data && <ForecastContent data={forecast.data} />
          )}
        </div>
      ) : (
        <div role="tabpanel" data-testid="pipeline-list">
          <form
            className={styles.filters}
            onSubmit={(e) => {
              e.preventDefault();
              setFilters({ ...filters, q: search, page: 0 });
            }}
          >
            <label>
              기회명·고객 검색
              <input
                data-testid="pipeline-search"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
              />
            </label>
            {tab === "closed" && (
              <label>
                종료 상태
                <select
                  data-testid="pipeline-status"
                  value={filters.oppt_stts_cd}
                  onChange={(e) =>
                    setFilters({
                      ...filters,
                      oppt_stts_cd: e.target.value,
                      page: 0,
                    })
                  }
                >
                  <option value="WON">수주</option>
                  <option value="LOST">실주</option>
                </select>
              </label>
            )}
            <label>
              시작 예정월부터
              <input
                data-testid="pipeline-from"
                type="month"
                value={filters.from ?? ""}
                onChange={(e) =>
                  setFilters({ ...filters, from: e.target.value, page: 0 })
                }
              />
            </label>
            <label>
              시작 예정월까지
              <input
                data-testid="pipeline-to"
                type="month"
                value={filters.to ?? ""}
                onChange={(e) =>
                  setFilters({ ...filters, to: e.target.value, page: 0 })
                }
              />
            </label>
            <Button data-testid="pipeline-query" type="submit">
              조회
            </Button>
            <Button
              data-testid="pipeline-reset"
              type="button"
              onClick={() => {
                setSearch("");
                setFilters({
                  oppt_stts_cd: tab === "closed" ? "WON" : "OPEN",
                  page: 0,
                  size: 50,
                });
              }}
            >
              초기화
            </Button>
          </form>
          <ErrorBanner
            error={list.error}
            onRetry={() => void list.reload().catch(() => {})}
          />
          {list.loading ? (
            <Skeleton />
          ) : (
            !list.error &&
            list.data && (
              <>
                {tab === "open" ? (
                  <div className={styles.kanban}>
                    {stages.map((s) => (
                      <section
                        key={s.key}
                        className={styles.column}
                        data-testid={`pipeline-column-${s.key.toLowerCase()}`}
                        onDragOver={(e) => e.preventDefault()}
                        onDrop={(e) => {
                          e.preventDefault();
                          const id = Number(
                            e.dataTransfer.getData("application/x-opportunity"),
                          );
                          if (
                            !busy &&
                            list.data?.items.some((r) => r.oppt_id === id)
                          )
                            void move(id, s.key);
                        }}
                      >
                        <h2>
                          {s.label} <small>기본 {s.probability}%</small>
                        </h2>
                        {list
                          .data!.items.filter(
                            (r) =>
                              r.oppt_stts_cd === "OPEN" &&
                              r.bsn_stg_cd === s.key,
                          )
                          .map((r) => (
                            <article
                              className={styles.opportunity}
                              draggable={!busy && !!permissions.mdfcn}
                              key={r.oppt_id}
                              data-testid={`pipeline-card-${r.oppt_id}`}
                              onDragStart={(e) =>
                                e.dataTransfer.setData(
                                  "application/x-opportunity",
                                  String(r.oppt_id),
                                )
                              }
                            >
                              <button
                                data-testid={`pipeline-open-${r.oppt_id}`}
                                onClick={() => setSelected(r.oppt_id)}
                              >
                                {r.oppt_no} · {r.oppt_nm}
                              </button>
                              <span>
                                {amount(r.expc_amt)}원 · {r.win_prblt_rt}%
                              </span>
                              <span>
                                {r.bgn_ym} / {r.mth_cnt}개월 ·{" "}
                                {r.needs.reduce((n, v) => n + v.psn_cnt, 0)}명
                              </span>
                              {r.quote.approval_state === "PENDING" && (
                                <Badge tone="warning">승인 대기</Badge>
                              )}
                              {r.stale_warning && (
                                <Badge tone="danger">장기 미갱신</Badge>
                              )}
                              <small>
                                {r.next_action ?? "다음 할 일 없음"}{" "}
                                {r.next_action_dt ?? ""}
                              </small>
                              <label>
                                단계 변경
                                <select
                                  data-testid={`pipeline-stage-${r.oppt_id}`}
                                  disabled={busy || !permissions.mdfcn}
                                  value={r.bsn_stg_cd}
                                  onChange={(e) =>
                                    void move(r.oppt_id, e.target.value)
                                  }
                                >
                                  {stages.map((v) => (
                                    <option key={v.key} value={v.key}>
                                      {v.label}
                                    </option>
                                  ))}
                                </select>
                              </label>
                            </article>
                          ))}
                      </section>
                    ))}
                  </div>
                ) : (
                  <DataTable
                    rows={list.data.items.filter(
                      (r) =>
                        r.oppt_stts_cd === "WON" || r.oppt_stts_cd === "LOST",
                    )}
                    total={list.data.total}
                    rowKey={(r) => String(r.oppt_id)}
                    columns={[
                      {
                        key: "number",
                        label: "기회번호",
                        render: (r) => r.oppt_no,
                      },
                      {
                        key: "name",
                        label: "기회명",
                        render: (r) => (
                          <Button
                            data-testid={`closed-open-${r.oppt_id}`}
                            onClick={() => setSelected(r.oppt_id)}
                          >
                            {r.oppt_nm}
                          </Button>
                        ),
                      },
                      {
                        key: "status",
                        label: "수주·실주",
                        render: (r) => (
                          <Badge
                            tone={
                              r.oppt_stts_cd === "WON" ? "success" : "danger"
                            }
                          >
                            {r.oppt_stts_cd === "WON" ? "수주" : "실주"}
                          </Badge>
                        ),
                      },
                      {
                        key: "amount",
                        label: "예상 금액",
                        render: (r) => amount(r.expc_amt),
                      },
                      {
                        key: "project",
                        label: "연결 프로젝트",
                        render: (r) =>
                          r.win_prj_id ? (
                            <Link
                              data-testid={`closed-project-${r.oppt_id}`}
                              to={`/UI-01?open=${r.win_prj_id}`}
                            >
                              프로젝트 보기
                            </Link>
                          ) : (
                            "–"
                          ),
                      },
                    ]}
                  />
                )}
                {list.data.items.length === 0 && (
                  <EmptyState
                    reason="조건에 맞는 기회가 없습니다."
                    actionLabel="조건 초기화"
                    onAction={() =>
                      setFilters({
                        oppt_stts_cd: tab === "closed" ? "WON" : "OPEN",
                      })
                    }
                  />
                )}
                <Pagination
                  page={(filters.page ?? 0) + 1}
                  size={(filters.size ?? 50) as 20 | 50 | 100}
                  total={list.data.total}
                  onPage={(page) => setFilters({ ...filters, page: page - 1 })}
                  onSize={(size) => setFilters({ ...filters, size, page: 0 })}
                />
              </>
            )
          )}
        </div>
      )}
      {selected && (
        <OpportunityDrawer
          id={selected}
          onClose={() => setSelected(null)}
          onChanged={list.reload}
          onEdit={onEdit}
        />
      )}
    </section>
  );
}
