import { useCallback, useEffect, useState, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import { Button, Badge, Pagination, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { api, readData } from "../../shared/api";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { failure } from "../admin/client";
import { seoulMonth } from "../shared/PeriodBar";
import { downloadUploadFile } from "../uploadsops/client";
import { UI13M1 } from "./UI13M1";
import { UI13M3 } from "./UI13M3";
import { UI13M4 } from "./UI13M4";
import {
  query,
  listBillingGaps,
  listBillingOverdue,
  getBillingSummary,
  getBillingDetail,
  confirmBilling,
} from "./client";
import type {
  BillingPage,
  BillingRow,
  BillingFilters,
  BillingGap,
  BillingOverdue,
  GapPage,
  OverduePage,
} from "./client";
import table from "../developers/Developers.module.css";
import styles from "./Billings.module.css";
import { ScreenHeading } from "../shared/ScreenHeading";
import type { BillingDetail } from "./client";

export interface BillingScreenProps {
  name?: string;
  groupName?: string;
  initialProjectId?: number;
  attendance?: (
    projectId: number | undefined,
    month: string,
    controls?: { onClose: () => void; reload: () => Promise<unknown> },
  ) => ReactNode;
  receiptActions?: (
    billing: BillingRow,
    reload: () => Promise<unknown>,
  ) => ReactNode;
  renderEditor?: (props: {
    existing?: BillingDetail;
    projectId?: number;
    month?: string;
    stageId?: number;
    onCancel: () => void;
    onSaved: () => void;
    reload: () => Promise<unknown>;
  }) => ReactNode;
  renderDetail?: (props: {
    id: number;
    version: number;
    readOnly: boolean;
    onClose: () => void;
    onEdit: () => void;
    onOpen: (id: number) => void;
    refresh: () => Promise<unknown>;
  }) => ReactNode;
}
type Tab = "list" | "gaps" | "overdue";
type ScreenFilters = BillingFilters & { page: number; size: 20 | 50 | 100 };
const titles = {
  list: "청구 목록",
  gaps: "청구 누락 점검",
  overdue: "미수 알림",
};
const won = (value: number | undefined) =>
  value == null ? "–" : value.toLocaleString("ko-KR");
export default function UI13Screen({
  name = "청구·입금",
  groupName = "매출",
  initialProjectId,
  attendance,
  receiptActions,
  renderEditor,
  renderDetail,
}: BillingScreenProps) {
  const navigate = useNavigate();
  const currentYear = Number(seoulMonth().slice(0, 4));
  const [year, setYear] = useState(currentYear),
    [tab, setTab] = useState<Tab>("list");
  const [width, setWidth] = useState(window.innerWidth);
  useEffect(() => {
    const resize = () => setWidth(window.innerWidth);
    window.addEventListener("resize", resize);
    return () => window.removeEventListener("resize", resize);
  }, []);
  const readonly = year < currentYear || width <= 767;
  const defaults: Record<Tab, ScreenFilters> = {
    list: {
      status: "",
      type: "",
      customerId: "",
      q: "",
      page: 0,
      size: 50,
      sort: "bill_dt,desc",
    },
    gaps: {
      month: "",
      judgment: "",
      q: "",
      page: 0,
      size: 50,
      sort: "project_name,asc",
    },
    overdue: {
      stage: "",
      customerId: "",
      overDays: "",
      q: "",
      page: 0,
      size: 50,
      sort: "overdue_days,desc",
    },
  };
  const [filters, setFilters] = useState(defaults),
    [applied, setApplied] = useState(defaults);
  const [selected, setSelected] = useState<number | null>(null);
  const [detailVersion, setDetailVersion] = useState(0);
  const [dialog, setDialog] = useState<
    "new" | "edit" | "cancel" | "amend" | "confirm" | "attendance" | null
  >(null);
  const [createScope, setCreateScope] = useState<{
    projectId?: number;
    month?: string;
    stageId?: number;
  }>({});
  const [message, setMessage] = useState("");
  const [downloadError, setDownloadError] = useState<ReturnType<
    typeof failure
  > | null>(null);
  const parameters: BillingFilters = {
    ...applied[tab],
    ...(tab !== "gaps" ? { year } : {}),
    ...(tab === "list" && initialProjectId
      ? { projectId: initialProjectId }
      : {}),
  };
  const key = query(parameters);
  const read = useAdminRead(
    useCallback(async () => {
      if (tab === "gaps")
        return {
          tab,
          data: await listBillingGaps(
            Object.fromEntries(new URLSearchParams(key)),
          ),
        };
      if (tab === "overdue")
        return {
          tab,
          data: await listBillingOverdue(
            Object.fromEntries(new URLSearchParams(key)),
          ),
        };
      return {
        tab,
        data: await readData<BillingPage<BillingRow>>(`/api/billings?${key}`),
      };
    }, [tab, key]),
  );
  const resource = {
    ...read,
    data: read.data?.tab === tab ? read.data.data : null,
  };
  const summary = useAdminRead(
    useCallback(() => getBillingSummary(year), [year]),
  );
  const detail = useAdminRead(
    useCallback(
      () =>
        selected == null ? Promise.resolve(null) : getBillingDetail(selected),
      [selected],
    ),
  );
  const reload = async () => {
    await Promise.all([
      resource.reload(),
      summary.reload(),
      ...(selected == null ? [] : [detail.reload()]),
    ]);
    setDetailVersion((version) => version + 1);
  };
  const write = useAdminWrite(reload);
  const changed = () => {
    setDialog(null);
    setMessage("저장했습니다.");
  };
  const filter = (field: string, value: string) =>
    setFilters((f) => ({ ...f, [tab]: { ...f[tab], [field]: value } }));
  const apply = () =>
    setApplied((f) => ({ ...f, [tab]: { ...filters[tab], page: 0 } }));
  const reset = () => {
    setFilters((f) => ({ ...f, [tab]: defaults[tab] }));
    setApplied((f) => ({ ...f, [tab]: defaults[tab] }));
  };
  const sort = (field: string) =>
    setApplied((f) => ({
      ...f,
      [tab]: {
        ...f[tab],
        sort: `${field},${f[tab].sort === `${field},asc` ? "desc" : "asc"}`,
        page: 0,
      },
    }));
  const openNew = (projectId?: number, month?: string, stageId?: number) => {
    setCreateScope({ projectId, month, stageId });
    setDialog("new");
  };
  const download = async () => {
    setDownloadError(null);
    const values = { ...parameters };
    delete values.page;
    delete values.size;
    try {
      await downloadUploadFile(
        `/api/billings${tab === "list" ? "" : `/${tab}`}/export?${query(values)}`,
        `${titles[tab]}.xlsx`,
      );
    } catch (e) {
      setDownloadError(failure(e));
    }
  };
  const headers: [string, string][] =
    tab === "gaps"
      ? [
          ["project_name", "프로젝트"],
          ["month", "귀속월"],
          ["basis_supply_amt", "투입 기준 금액"],
          ["confirmed_supply_amt", "청구 공급가"],
          ["difference_amt", "차이"],
          ["judgment", "판정"],
          ["", "설명"],
        ]
      : tab === "overdue"
        ? [
            ["customer_name", "고객사"],
            ["project_name", "프로젝트"],
            ["bill_id", "청구번호"],
            ["receivable_amt", "미수금"],
            ["rcpt_plan_dt", "입금 예정일"],
            ["overdue_days", "지난 일수"],
            ["stage", "단계"],
            ["", "수신 역할"],
            ["", "보낸 알림"],
            ["", "다음 알림"],
          ]
        : [
            ["bill_no", "청구번호"],
            ["project_name", "프로젝트"],
            ["customer_name", "고객사"],
            ["contract_type", "계약유형"],
            ["atrb_ym", "귀속월"],
            ["bill_dt", "청구일"],
            ["sply_amt", "공급가"],
            ["vat_amt", "부가세"],
            ["tot_amt", "합계"],
            ["bill_stts_cd", "상태"],
            ["txinv_no", "세금계산서 번호"],
            ["rcpt_plan_dt", "입금 예정일"],
            ["received_amt", "입금액"],
            ["receivable_amt", "미수금"],
          ];
  const field = (name: string, label: string, options?: [string, string][]) => (
    <label key={name}>
      {label}
      {options ? (
        <select
          aria-label={label}
          data-testid={`billing-filter-${name}`}
          value={filters[tab][name]}
          onChange={(e) => filter(name, e.target.value)}
        >
          <option value="">전체</option>
          {options.map(([value, text]) => (
            <option key={value} value={value}>
              {text}
            </option>
          ))}
        </select>
      ) : (
        <input
          aria-label={label}
          data-testid={`billing-filter-${name}`}
          type={name === "month" ? "month" : "text"}
          value={filters[tab][name]}
          onChange={(e) => filter(name, e.target.value)}
        />
      )}
    </label>
  );
  return (
    <section data-testid="billing-screen" data-screen-id="SCR-UI-13">
      <ScreenHeading
        screenId="UI-13"
        name={name}
        groupName={groupName}
        actions={
          !readonly && (
            <div className={table.writeActions}>
              {attendance && (
                <Button
                  data-testid="billing-upload"
                  onClick={() => setDialog("attendance")}
                >
                  엑셀 업로드
                </Button>
              )}
              <Button
                data-testid="billing-add"
                variant="primary"
                onClick={() => openNew(initialProjectId)}
              >
                청구 등록
              </Button>
            </div>
          )
        }
      />
      <div className={styles.period}>
        <Button
          data-testid="billing-year-prev"
          aria-label="이전 연도"
          onClick={() => setYear((y) => y - 1)}
        >
          ‹
        </Button>
        <strong>{year}년</strong>
        <Button
          data-testid="billing-year-next"
          aria-label="다음 연도"
          onClick={() => setYear((y) => y + 1)}
        >
          ›
        </Button>
        {readonly && <Badge tone="locked">읽기 전용</Badge>}
      </div>
      <div className={styles.tabs} role="tablist">
        {(Object.keys(titles) as Tab[]).map((t) => (
          <Button
            data-testid={`billing-tab-${t}`}
            key={t}
            role="tab"
            aria-selected={tab === t}
            onClick={() => setTab(t)}
          >
            {titles[t]}
          </Button>
        ))}
      </div>
      <div className={styles.cards}>
        {(
          [
            [
              `${year}년 청구 (공급가)`,
              summary.data?.annual_confirmed_supply_amt,
            ],
            [
              "이번 달 청구 (공급가)",
              summary.data?.current_month_confirmed_supply_amt,
            ],
            ["미수금", summary.data?.confirmed_receivable_amt],
            ["연체", summary.data?.overdue_amt],
          ] as const
        ).map(([label, amount]) => (
          <div
            data-testid={`billing-summary-${label}`}
            key={label}
            className={styles.card}
          >
            {label}
            <strong>{won(amount)} 원</strong>
          </div>
        ))}
      </div>
      {summary.data && (
        <p>
          예정 {won(summary.data.planned_supply_amt)}원 · 작성{" "}
          {won(summary.data.draft_supply_amt)}원 (매출·미수 집계 제외)
        </p>
      )}
      <form
        className={table.search}
        data-testid="billing-search"
        onSubmit={(e) => {
          e.preventDefault();
          apply();
        }}
      >
        {tab === "list" && (
          <>
            {field("status", "상태", [
              ["PLAN", "예정"],
              ["DRAFT", "작성"],
              ["CONFIRM", "확정"],
              ["CANCEL", "취소"],
            ])}
            {field("type", "유형", [
              ["TK", "턴키"],
              ["MO", "월단위"],
              ["DP", "파견"],
            ])}
          </>
        )}
        {tab === "gaps" && (
          <>
            {field("month", "월")}
            {field(
              "judgment",
              "판정",
              [
                "맞음",
                "맞음(무상)",
                "적게 청구",
                "많이 청구",
                "청구 없음",
                "근태 미입력",
              ].map((v) => [v, v]),
            )}
          </>
        )}
        {tab === "overdue" && (
          <>
            {field(
              "stage",
              "단계",
              [0, 1, 2, 3].map((v) => [String(v), `${v}단계`]),
            )}
            {field(
              "overDays",
              "연체 일수",
              [30, 60, 90].map((v) => [String(v), `${v}일 초과`]),
            )}
          </>
        )}
        {tab !== "gaps" && field("customerId", "고객사 ID")}
        {field("q", "검색")}
        <Button data-testid="billing-reset" type="button" onClick={reset}>
          초기화
        </Button>
        <Button data-testid="billing-query" type="submit" variant="primary">
          조회
        </Button>
      </form>
      <ErrorBanner
        error={resource.error ?? summary.error ?? downloadError ?? write.error}
        onRetry={() => void reload().catch(() => {})}
      />
      {message && (
        <p role="status" data-testid="billing-success">
          {message}
        </p>
      )}
      {tab === "gaps" && resource.data && (
        <p>
          점검 월 {(resource.data as GapPage).month} · 총 차이{" "}
          {won((resource.data as GapPage).total_difference_amt)}원
        </p>
      )}
      {tab === "overdue" && resource.data && (
        <>
          <div className={styles.cards}>
            {(resource.data as OverduePage).stage_counts.map((c) => (
              <div className={styles.card} key={c.stage}>
                {c.stage}단계 <strong>{c.count}건</strong>
              </div>
            ))}
          </div>
          <p>
            {(resource.data as OverduePage).customer_totals
              .map((c) => `${c.customer_name}: ${won(c.receivable_amt)}원`)
              .join(" · ")}
          </p>
          <p className={styles.message}>
            알림 발송은 사용자·권한 기능 구축 후 사용할 수 있습니다.
          </p>
        </>
      )}
      <div className={table.toolbar}>
        <span>총 {resource.data?.total ?? 0}건</span>
        <Button data-testid="billing-export" onClick={() => void download()}>
          엑셀 다운로드
        </Button>
      </div>
      <div role="tabpanel" data-testid={`billing-panel-${tab}`}>
        {resource.loading ? (
          <Skeleton />
        ) : (
          !resource.error && (
            <div className={table.tableScroll}>
              <table className={table.table}>
                <thead>
                  <tr>
                    {headers.map(([key, label]) => (
                      <th key={label}>
                        {key && key !== "month" ? (
                          <Button
                            data-testid={`billing-sort-${key}`}
                            variant="text"
                            onClick={() => sort(key)}
                          >
                            {label}
                          </Button>
                        ) : (
                          label
                        )}
                      </th>
                    ))}
                    {tab !== "list" && <th>동작</th>}
                  </tr>
                </thead>
                <tbody>
                  {tab === "list" &&
                    ((resource.data?.items as BillingRow[]) ?? []).map(
                      (r, index) => (
                        <tr
                          key={r.bill_id ?? `stage-${r.bill_stg_id}`}
                          data-testid={`billing-row-${r.bill_id ?? `stage-${r.bill_stg_id}`}`}
                        >
                          <td>
                            <Button
                              data-testid={`billing-open-${index}`}
                              variant="text"
                              onClick={() =>
                                r.bill_id == null
                                  ? !readonly &&
                                    openNew(
                                      r.prj_id,
                                      r.atrb_ym ?? undefined,
                                      r.bill_stg_id ?? undefined,
                                    )
                                  : setSelected(r.bill_id)
                              }
                            >
                              {r.bill_no ?? "청구 예정"}
                            </Button>
                          </td>
                          <td>
                            <Button
                              data-testid={`billing-project-${index}`}
                              variant="text"
                              onClick={() =>
                                navigate(`/UI-01?open=${r.prj_id}`)
                              }
                            >
                              {r.project_name}
                            </Button>
                          </td>
                          <td>{r.customer_name ?? "–"}</td>
                          <td>{r.contract_type}</td>
                          <td>{r.atrb_ym ?? "–"}</td>
                          <td>{r.bill_dt ?? "–"}</td>
                          <td className={table.amount}>{won(r.sply_amt)}</td>
                          <td className={table.amount}>{won(r.vat_amt)}</td>
                          <td className={table.amount}>{won(r.tot_amt)}</td>
                          <td>
                            <Badge
                              tone={
                                r.bill_stts_cd === "CONFIRM"
                                  ? "success"
                                  : "neutral"
                              }
                            >
                              {
                                (
                                  {
                                    PLAN: "예정",
                                    DRAFT: "작성",
                                    CONFIRM: "확정",
                                    CANCEL: "취소",
                                  } as Record<string, string>
                                )[r.bill_stts_cd]
                              }
                            </Badge>
                          </td>
                          <td>{r.txinv_no ?? "미입력"}</td>
                          <td>{r.rcpt_plan_dt ?? "–"}</td>
                          <td className={table.amount}>
                            {won(r.received_amt)}
                          </td>
                          <td className={table.amount}>
                            {won(r.receivable_amt)}
                          </td>
                        </tr>
                      ),
                    )}
                  {tab === "gaps" &&
                    ((resource.data?.items as BillingGap[]) ?? []).map((r) => (
                      <tr
                        key={r.project_id}
                        data-testid={`billing-gap-${r.project_id}`}
                      >
                        <td>
                          <Button
                            data-testid={`billing-gap-project-${r.project_id}`}
                            variant="text"
                            onClick={() =>
                              navigate(`/UI-01?open=${r.project_id}`)
                            }
                          >
                            {r.project_name}
                          </Button>
                        </td>
                        <td>{r.month}</td>
                        <td className={table.amount}>
                          {won(r.basis_supply_amt)}
                        </td>
                        <td className={table.amount}>
                          {won(r.confirmed_supply_amt)}
                        </td>
                        <td className={table.amount}>
                          {won(r.difference_amt)}
                        </td>
                        <td>
                          <Badge
                            tone={
                              r.judgment.startsWith("맞음")
                                ? "success"
                                : "warning"
                            }
                          >
                            {r.judgment}
                          </Badge>
                        </td>
                        <td>
                          {r.explanation}
                          {r.suspected_people.map((p) => (
                            <p key={p.management_number}>
                              {p.name} · {p.management_number}
                            </p>
                          ))}
                        </td>
                        <td>
                          {!readonly && (
                            <Button
                              data-testid={`billing-gap-action-${r.project_id}`}
                              onClick={() =>
                                r.billing_ids.length
                                  ? setSelected(r.billing_ids[0])
                                  : openNew(r.project_id, r.month)
                              }
                            >
                              {r.billing_ids.length
                                ? "청구 고치기"
                                : "청구 등록"}
                            </Button>
                          )}
                        </td>
                      </tr>
                    ))}
                  {tab === "overdue" &&
                    ((resource.data?.items as BillingOverdue[]) ?? []).map(
                      (r) => (
                        <tr
                          key={r.billing.bill_id}
                          data-testid={`billing-overdue-${r.billing.bill_id}`}
                        >
                          <td>{r.billing.customer_name ?? "–"}</td>
                          <td>{r.billing.project_name}</td>
                          <td>
                            <Button
                              data-testid={`billing-overdue-open-${r.billing.bill_id}`}
                              variant="text"
                              onClick={() => setSelected(r.billing.bill_id)}
                            >
                              {r.billing.bill_no}
                            </Button>
                          </td>
                          <td className={table.amount}>
                            {won(r.billing.receivable_amt)}
                          </td>
                          <td>{r.billing.rcpt_plan_dt ?? "예정일 미입력"}</td>
                          <td>{r.overdue_days ?? "–"}</td>
                          <td>
                            {r.stage == null ? (
                              "–"
                            ) : (
                              <Badge
                                tone={
                                  r.stage >= 2
                                    ? "danger"
                                    : r.stage === 1
                                      ? "warning"
                                      : "neutral"
                                }
                              >
                                {r.stage}단계 · {r.overdue_days}일
                              </Badge>
                            )}
                          </td>
                          <td>{r.recipient_roles.join(" · ") || "–"}</td>
                          <td>
                            {r.sent_history.length
                              ? r.sent_history.map((h) => h.sent_at).join(" · ")
                              : "보낸 알림 없음"}
                          </td>
                          <td>{r.next_notification_date ?? "–"}</td>
                          <td>
                            <Button
                              data-testid={`billing-remind-${r.billing.bill_id}`}
                              disabled
                              title="알림 발송은 사용자·권한 기능 구축 후 사용할 수 있습니다."
                            >
                              다시 알림
                            </Button>
                          </td>
                        </tr>
                      ),
                    )}
                  {resource.data?.items.length === 0 && (
                    <tr>
                      <td
                        colSpan={headers.length + (tab === "list" ? 0 : 1)}
                        className={table.empty}
                      >
                        조건에 맞는 결과가 없습니다{" "}
                        <Button
                          data-testid="billing-empty-reset"
                          onClick={reset}
                        >
                          조건 초기화
                        </Button>
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          )
        )}
        <Pagination
          page={applied[tab].page + 1}
          size={applied[tab].size}
          total={resource.data?.total ?? 0}
          onPage={(p) =>
            setApplied((f) => ({ ...f, [tab]: { ...f[tab], page: p - 1 } }))
          }
          onSize={(size) =>
            setApplied((f) => ({ ...f, [tab]: { ...f[tab], size, page: 0 } }))
          }
        />
      </div>
      {selected != null &&
        renderDetail?.({
          id: selected,
          version: detailVersion,
          readOnly: readonly,
          onClose: () => setSelected(null),
          onEdit: () => setDialog("edit"),
          onOpen: setSelected,
          refresh: reload,
        })}
      {selected != null && !renderDetail && (
        <div className={table.scrim}>
          <aside
            role="dialog"
            aria-label="청구 상세"
            data-testid="billing-detail"
            className={table.drawer}
          >
            <div className={table.drawerHeader}>
              <h2>청구 상세</h2>
              <Button
                data-testid="billing-detail-close"
                onClick={() => setSelected(null)}
              >
                닫기
              </Button>
            </div>
            <ErrorBanner error={detail.error} />
            {detail.loading ? (
              <Skeleton />
            ) : (
              detail.data && (
                <>
                  <h3>
                    {detail.data.billing.bill_no} ·{" "}
                    {detail.data.billing.project_name}
                  </h3>
                  <p>
                    {detail.data.billing.customer_name} ·{" "}
                    {detail.data.billing.bill_stts_cd} ·{" "}
                    {detail.data.billing.contract_type}
                  </p>
                  <p>
                    귀속월 {detail.data.billing.atrb_ym ?? "–"} · 청구일{" "}
                    {detail.data.billing.bill_dt ?? "–"}
                  </p>
                  <p>
                    공급가액 {won(detail.data.billing.sply_amt)} · 부가세{" "}
                    {won(detail.data.billing.vat_amt)} · 합계{" "}
                    {won(detail.data.billing.tot_amt)}
                  </p>
                  <p>
                    입금 예정일 {detail.data.billing.rcpt_plan_dt ?? "–"} ·
                    세금계산서 {detail.data.billing.txinv_no ?? "미입력"} ·
                    작성일 {detail.data.billing.txinv_dt ?? "–"} (기록만)
                  </p>
                  <p>
                    누적 입금 {won(detail.data.billing.received_amt)} · 조정
                    청구 총액 {won(detail.data.billing.adjusted_total_amt)} ·
                    미수금 {won(detail.data.billing.receivable_amt)}
                  </p>
                  {detail.data.original_bill_id && (
                    <Button
                      data-testid="billing-original"
                      onClick={() => setSelected(detail.data!.original_bill_id)}
                    >
                      원본 청구 열기
                    </Button>
                  )}
                  {detail.data.details.map((line) => (
                    <p
                      key={line.asgn_id}
                      data-testid={`billing-detail-line-${line.asgn_id}`}
                    >
                      {line.display_name} · {line.work_ym} · MM{" "}
                      {line.mm_qty ?? "–"} · 공급가 {won(line.bill_amt)}
                    </p>
                  ))}
                  {detail.data.amendments.map((b) => (
                    <Button
                      data-testid={`billing-child-${b.bill_id}`}
                      key={b.bill_id}
                      onClick={() => setSelected(b.bill_id)}
                    >
                      수정 청구 {b.bill_no} ({b.bill_stts_cd})
                    </Button>
                  ))}
                  {detail.data.receipts.map((r) => (
                    <p
                      data-testid={`billing-detail-receipt-${r.receipt_id}`}
                      key={r.receipt_id}
                    >
                      {r.rcpt_dt} · {won(r.rcpt_amt)}원 · {r.rmrk_desc}
                    </p>
                  ))}
                  {detail.data.warnings.map((text, i) => (
                    <p className={styles.message} key={i}>
                      {text}
                    </p>
                  ))}
                  {detail.data.change_history.map((h, i) => (
                    <p data-testid={`billing-history-${i}`} key={i}>
                      {h.changed_at} · {h.description} ·{" "}
                      {h.user_id ?? "기본 사용자"}
                    </p>
                  ))}
                  {!readonly && (
                    <div className={table.writeActions}>
                      {detail.data.billing.bill_stts_cd === "DRAFT" && (
                        <>
                          <Button
                            data-testid="billing-edit"
                            onClick={() => setDialog("edit")}
                          >
                            수정
                          </Button>
                          <Button
                            data-testid="billing-recalculate"
                            onClick={() =>
                              void write.save(
                                () =>
                                  api(`/api/billings/${selected}`, {
                                    method: "PUT",
                                    body: JSON.stringify({ recalculate: true }),
                                  }),
                                () => setMessage("다시 계산했습니다."),
                              )
                            }
                          >
                            다시 계산
                          </Button>
                          <Button
                            data-testid="billing-confirm"
                            onClick={() => setDialog("confirm")}
                          >
                            확정
                          </Button>
                        </>
                      )}
                      {["DRAFT", "CONFIRM"].includes(
                        detail.data.billing.bill_stts_cd,
                      ) && (
                        <Button
                          data-testid="billing-cancel"
                          onClick={() => setDialog("cancel")}
                        >
                          청구 취소
                        </Button>
                      )}
                      {detail.data.billing.bill_stts_cd === "CONFIRM" &&
                        !detail.data.original_bill_id && (
                          <>
                            <Button
                              data-testid="billing-amend"
                              onClick={() => setDialog("amend")}
                            >
                              수정 청구
                            </Button>
                            {receiptActions?.(detail.data.billing, reload)}
                          </>
                        )}
                    </div>
                  )}
                </>
              )
            )}
            <ErrorBanner
              error={write.error}
              onRetry={() => void write.recover()}
            />
          </aside>
        </div>
      )}
      {!readonly &&
        dialog === "new" &&
        (renderEditor ? (
          renderEditor({
            ...createScope,
            reload,
            onCancel: () => setDialog(null),
            onSaved: changed,
          })
        ) : (
          <UI13M1
            {...createScope}
            attendance={attendance}
            reload={reload}
            onClose={() => setDialog(null)}
            onSaved={changed}
          />
        ))}
      {!readonly &&
        dialog === "edit" &&
        detail.data &&
        (renderEditor ? (
          renderEditor({
            existing: detail.data,
            reload,
            onCancel: () => setDialog(null),
            onSaved: changed,
          })
        ) : (
          <UI13M1
            existing={detail.data}
            attendance={attendance}
            reload={reload}
            onClose={() => setDialog(null)}
            onSaved={changed}
          />
        ))}
      {!readonly && dialog === "cancel" && detail.data && (
        <UI13M3
          billing={detail.data.billing}
          reload={reload}
          onClose={() => setDialog(null)}
          onSaved={changed}
        />
      )}
      {!readonly && dialog === "amend" && detail.data && (
        <UI13M4
          billing={detail.data.billing}
          reload={reload}
          onClose={() => setDialog(null)}
          onSaved={changed}
        />
      )}
      {!readonly && dialog === "confirm" && selected != null && (
        <div
          role="dialog"
          aria-label="청구 확정 확인"
          data-testid="billing-confirm-dialog"
          className={table.card}
        >
          <p>
            확정하면 상세 스냅샷과 금액을 직접 수정할 수 없습니다. 확정할까요?
          </p>
          <Button
            data-testid="billing-confirm-close"
            onClick={() => setDialog(null)}
          >
            취소
          </Button>
          <Button
            data-testid="billing-confirm-submit"
            pending={write.pending}
            disabled={write.uncertain}
            onClick={() =>
              void write.save(() => confirmBilling(selected), changed)
            }
          >
            확정 확인
          </Button>
        </div>
      )}
      {!readonly &&
        dialog === "attendance" &&
        attendance?.(initialProjectId, seoulMonth(), {
          onClose: () => setDialog(null),
          reload,
        })}
    </section>
  );
}
