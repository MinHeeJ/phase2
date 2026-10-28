import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Badge, Button, Modal, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { type ApiFailure, readData } from "../../shared/api";
import { ScreenHeading } from "../shared/ScreenHeading";
import { seoulMonth } from "../shared/PeriodBar";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import { UI13M1 } from "./UI13M1";
import { UI13M2 } from "./UI13M2";
import { UI13M3 } from "./UI13M3";
import { UI13M4 } from "./UI13M4";
import { UI13M5 } from "./UI13M5";
import * as client from "./client";
import type {
  BillingRow,
  BillingDetail,
  Gap,
  Overdue,
  Summary,
  Filters,
  Receipt,
} from "./client";
import styles from "../developers/Developers.module.css";

type Tab = "billings" | "gaps" | "overdue";
const tabs = [
  { key: "billings", label: "청구 목록" },
  { key: "gaps", label: "청구 누락 점검" },
  { key: "overdue", label: "미수 알림" },
] as const;
const amount = (v: number | null | undefined) =>
  v == null ? "–" : `${v.toLocaleString("ko-KR")}원`;
const billColumns = [
  ["bill_no", "청구번호"],
  ["project_name", "프로젝트"],
  ["customer_name", "고객사"],
  ["contract_type", "계약유형"],
  ["atrb_ym", "귀속월"],
  ["bill_dt", "청구일"],
  ["sply_amt", "공급가액"],
  ["vat_amt", "부가세"],
  ["tot_amt", "합계"],
  ["bill_stts_cd", "상태"],
  ["txinv_no", "세금계산서 번호"],
  ["rcpt_plan_dt", "입금 예정일"],
  ["received_amt", "누적 입금"],
  ["receivable_amt", "미수금"],
  ["reminder_stage", "미수 알림 단계"],
] as const;
const gapColumns = [
  ["project_name", "프로젝트"],
  ["month", "귀속월"],
  ["basis_supply_amt", "투입 기준 금액"],
  ["confirmed_supply_amt", "청구 공급가"],
  ["difference_amt", "차이"],
  ["judgment", "판정"],
  ["explanation", "설명"],
] as const;
const overdueColumns = [
  ["customer_name", "고객사"],
  ["project_name", "프로젝트"],
  ["bill_no", "청구번호"],
  ["receivable_amt", "미수금"],
  ["rcpt_plan_dt", "입금 예정일"],
  ["overdue_days", "지난 일수"],
  ["stage", "지금 단계"],
  ["recipient_roles", "수신 역할"],
  ["sent_history", "보낸 알림"],
  ["next_notification_date", "다음 알림"],
] as const;
export default function UI13Screen({
  name = "청구·입금",
  groupName = "매출",
}: {
  name?: string;
  groupName?: string;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const navigate = useNavigate(),
    mobile = useDeveloperReadOnly();
  const currentYear = Number(seoulMonth().slice(0, 4));
  const [year, setYear] = useState(currentYear),
    [tab, setTab] = useState<Tab>("billings");
  const readonly = mobile || year < currentYear;
  const [filters, setFilters] = useState<Record<Tab, Filters>>({
    billings: {
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
  });
  const [rows, setRows] = useState<(BillingRow | Gap | Overdue)[]>([]),
    [total, setTotal] = useState(0),
    [summary, setSummary] = useState<Summary | null>(null);
  const [gapTotal, setGapTotal] = useState<number | null>(null),
    [overdue, setOverdue] = useState<client.OverdueResult | null>(null);
  const [customers, setCustomers] = useState<{ id: number; name: string }[]>(
    [],
  );
  const [error, setError] = useState<ApiFailure | null>(null),
    [loading, setLoading] = useState(false),
    [revision, setRevision] = useState(0);
  const [detail, setDetail] = useState<BillingDetail | null>(null),
    [modal, setModal] = useState<
      "new" | "edit" | "attendance" | "cancel" | "amend" | "confirm" | null
    >(null);
  const [context, setContext] = useState<{
    projectId?: number;
    month?: string;
  }>({});
  const [receipt, setReceipt] = useState<{
    mode: "create" | "edit" | "delete";
    row?: Receipt;
  } | null>(null);
  const [pending, setPending] = useState(false),
    [message, setMessage] = useState("");
  const selected = filters[tab];
  const update = (key: string, value: string | number) =>
    setFilters((f) => ({
      ...f,
      [tab]: {
        ...f[tab],
        [key]: value,
        ...(key !== "page" ? { page: 0 } : {}),
      },
    }));
  useEffect(() => {
    readData<{ id: number; name: string }[]>("/api/partners/options?kind=CUST")
      .then(setCustomers)
      .catch((e) => setError(client.billingFailure(e)));
  }, []);
  useEffect(() => {
    let active = true;
    setLoading(true);
    setError(null);
    setRows([]);
    const f = { ...selected, ...(tab === "gaps" ? {} : { year }) };
    const load =
      tab === "billings"
        ? client.listBillings(f)
        : tab === "gaps"
          ? client.listBillingGaps(f)
          : client.listBillingOverdue(f);
    load
      .then((result) => {
        if (!active) return;
        setRows(result.items);
        setTotal(result.total);
        if (tab === "gaps")
          setGapTotal((result as client.GapResult).total_difference_amt);
        if (tab === "overdue") setOverdue(result as client.OverdueResult);
      })
      .catch((e) => {
        if (active) setError(client.billingFailure(e));
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [tab, selected, year, revision]);
  useEffect(() => {
    let active = true;
    client
      .getBillingSummary(year)
      .then((s) => {
        if (active) setSummary(s);
      })
      .catch((e) => {
        if (active) setError(client.billingFailure(e));
      });
    return () => {
      active = false;
    };
  }, [year, revision]);
  useEffect(() => {
    if (readonly) {
      setModal(null);
      setReceipt(null);
    }
  }, [readonly]);
  const open = async (id: number) => {
    try {
      setError(null);
      setDetail(await client.getBilling(id));
    } catch (e) {
      setError(client.billingFailure(e));
    }
  };
  const refresh = useCallback(async () => {
    if (detail?.billing.bill_id != null)
      setDetail(await client.getBilling(detail.billing.bill_id));
    setRevision((r) => r + 1);
    setMessage("저장했습니다.");
  }, [detail?.billing.bill_id]);
  const columns =
    tab === "billings"
      ? billColumns
      : tab === "gaps"
        ? gapColumns
        : overdueColumns;
  const cell = (entry: BillingRow | Gap | Overdue, key: string) => {
    const data = ("billing" in entry
      ? { ...entry.billing, ...entry }
      : entry) as unknown as Record<string, unknown>;
    const v = data[key];
    if (key === "project_name")
      return (
        <Button
          data-testid={`billing-project-${"billing" in entry ? entry.billing.bill_id : "project_id" in entry ? entry.project_id : (entry.bill_id ?? entry.bill_stg_id)}`}
          variant="text"
          onClick={() =>
            navigate(`/UI-01?open=${data.prj_id ?? data.project_id}`)
          }
        >
          {String(v ?? "–")}
        </Button>
      );
    if (key === "bill_no" && "bill_id" in entry && entry.bill_id != null)
      return (
        <Button
          data-testid={`billing-open-${entry.bill_id}`}
          variant="text"
          onClick={() => void open(entry.bill_id!)}
        >
          {String(v)}
        </Button>
      );
    if (key === "bill_no" && "billing" in entry)
      return (
        <Button
          data-testid={`billing-open-${entry.billing.bill_id}`}
          variant="text"
          onClick={() => void open(entry.billing.bill_id!)}
        >
          {String(v ?? "–")}
        </Button>
      );
    if (key.endsWith("_amt")) return amount(v as number);
    if (key === "bill_stts_cd")
      return (
        <Badge
          tone={
            v === "CONFIRM"
              ? "success"
              : v === "DRAFT"
                ? "info"
                : v === "CANCEL"
                  ? "locked"
                  : "neutral"
          }
        >
          {(
            {
              CONFIRM: "확정",
              DRAFT: "작성",
              CANCEL: "취소",
              PLAN: "예정",
            } as Record<string, string>
          )[String(v)] ?? String(v)}
        </Badge>
      );
    if (key === "judgment")
      return (
        <Badge
          tone={
            String(v).startsWith("맞음")
              ? "success"
              : v === "많이 청구"
                ? "danger"
                : "warning"
          }
        >
          {String(v)}
        </Badge>
      );
    if (key === "stage" || key === "reminder_stage")
      return v == null ? (
        "–"
      ) : (
        <Badge
          tone={
            Number(v) >= 2 ? "danger" : Number(v) === 1 ? "warning" : "neutral"
          }
        >
          {String(v)}단계
        </Badge>
      );
    if (key === "recipient_roles") return (v as string[]).join(" · ") || "–";
    if (key === "sent_history")
      return (
        (v as { sent_at: string }[]).map((h) => h.sent_at).join(" · ") ||
        "보낸 알림 없음"
      );
    return v == null ? "–" : String(v);
  };
  return (
    <section data-testid="billing-screen" data-screen-id="SCR-UI-13">
      <ScreenHeading
        screenId="UI-13"
        name={name}
        groupName={groupName}
        actions={
          !readonly && (
            <>
              <Button
                data-testid="billing-template"
                onClick={() =>
                  void client
                    .downloadBilling("/api/uploads/templates/B.3")
                    .catch((e) => setError(client.billingFailure(e)))
                }
              >
                양식 내려받기
              </Button>
              <Button
                data-testid="billing-upload"
                onClick={() => setModal("attendance")}
              >
                엑셀 업로드
              </Button>
              <Button
                data-testid="billing-create"
                variant="primary"
                onClick={() => {
                  setContext({});
                  setModal("new");
                }}
              >
                청구 등록
              </Button>
            </>
          )
        }
      />
      <div className={styles.toolbar}>
        <Button
          data-testid="billing-year-previous"
          onClick={() => setYear((y) => y - 1)}
        >
          이전 연도
        </Button>
        <label>
          조회 연도
          <input
            data-testid="billing-year"
            type="number"
            min={1}
            max={9999}
            value={year}
            onChange={(e) => setYear(Number(e.target.value))}
          />
        </label>
        <Button
          data-testid="billing-year-next"
          onClick={() => setYear((y) => y + 1)}
        >
          다음 연도
        </Button>
      </div>
      {readonly && (
        <p>조회 전용입니다. 과거 연도와 휴대폰에서는 변경할 수 없습니다.</p>
      )}
      <div role="tablist" className={styles.actions}>
        {tabs.map((t) => (
          <Button
            key={t.key}
            role="tab"
            aria-selected={tab === t.key}
            data-testid={`billing-tab-${t.key}`}
            onClick={() => setTab(t.key)}
          >
            {t.label}
          </Button>
        ))}
      </div>
      {summary && (
        <div className={styles.search} data-testid="billing-summary">
          <span>
            연간 청구(공급가) {amount(summary.annual_confirmed_supply_amt)}
          </span>
          <span>
            이번 달 청구(공급가){" "}
            {amount(summary.current_month_confirmed_supply_amt)}
          </span>
          <span>미수 {amount(summary.confirmed_receivable_amt)}</span>
          <span>연체 {amount(summary.overdue_amt)}</span>
          <span>
            예정 {amount(summary.planned_supply_amt)} / 작성{" "}
            {amount(summary.draft_supply_amt)}
          </span>
        </div>
      )}
      <section
        role="tabpanel"
        aria-label={tabs.find((t) => t.key === tab)!.label}
        data-testid={`billing-panel-${tab}`}
      >
        <form
          className={styles.search}
          onSubmit={(e) => {
            e.preventDefault();
            setRevision((r) => r + 1);
          }}
        >
          {tab === "gaps" ? (
            <>
              <label>
                월
                <input
                  data-testid="billing-gap-month"
                  type="month"
                  value={selected.month}
                  onChange={(e) => update("month", e.target.value)}
                />
              </label>
              <label>
                판정
                <select
                  data-testid="billing-gap-judgment"
                  value={selected.judgment}
                  onChange={(e) => update("judgment", e.target.value)}
                >
                  <option value="">전체</option>
                  {[
                    "맞음",
                    "맞음(무상)",
                    "적게 청구",
                    "많이 청구",
                    "청구 없음",
                    "근태 미입력",
                  ].map((v) => (
                    <option key={v}>{v}</option>
                  ))}
                </select>
              </label>
            </>
          ) : (
            <>
              {tab === "billings" ? (
                <>
                  <label>
                    상태
                    <select
                      data-testid="billing-status"
                      value={selected.status}
                      onChange={(e) => update("status", e.target.value)}
                    >
                      <option value="">전체</option>
                      {["PLAN", "DRAFT", "CONFIRM", "CANCEL"].map((v) => (
                        <option key={v}>{v}</option>
                      ))}
                    </select>
                  </label>
                  <label>
                    유형
                    <select
                      data-testid="billing-type"
                      value={selected.type}
                      onChange={(e) => update("type", e.target.value)}
                    >
                      <option value="">전체</option>
                      {["TK", "MO", "DP"].map((v) => (
                        <option key={v}>{v}</option>
                      ))}
                    </select>
                  </label>
                </>
              ) : (
                <>
                  <label>
                    단계
                    <select
                      data-testid="billing-overdue-stage"
                      value={selected.stage}
                      onChange={(e) => update("stage", e.target.value)}
                    >
                      <option value="">전체</option>
                      {[0, 1, 2, 3].map((v) => (
                        <option key={v} value={v}>
                          {v}단계
                        </option>
                      ))}
                    </select>
                  </label>
                  <label>
                    연체
                    <select
                      data-testid="billing-overdue-days"
                      value={selected.overDays}
                      onChange={(e) => update("overDays", e.target.value)}
                    >
                      <option value="">전체</option>
                      {[30, 60, 90].map((v) => (
                        <option key={v} value={v}>
                          {v}일 초과
                        </option>
                      ))}
                    </select>
                  </label>
                </>
              )}
              <label>
                고객사
                <select
                  data-testid="billing-customer"
                  value={selected.customerId}
                  onChange={(e) => update("customerId", e.target.value)}
                >
                  <option value="">전체</option>
                  {customers.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name}
                    </option>
                  ))}
                </select>
              </label>
            </>
          )}
          <label>
            검색
            <input
              data-testid="billing-search"
              value={selected.q}
              onChange={(e) => update("q", e.target.value)}
            />
          </label>
          <Button data-testid="billing-search-submit" type="submit">
            조회
          </Button>
          <Button
            data-testid="billing-export"
            type="button"
            onClick={() =>
              void client
                .downloadBilling(
                  tab === "billings"
                    ? "/api/billings/export"
                    : `/api/billings/${tab}/export`,
                  { ...selected, ...(tab === "gaps" ? {} : { year }) },
                )
                .catch((e) => setError(client.billingFailure(e)))
            }
          >
            엑셀 다운로드
          </Button>
        </form>
        {tab === "gaps" && gapTotal != null && (
          <p>총 차이 {amount(gapTotal)}</p>
        )}
        {tab === "overdue" && (
          <>
            <p>알림 발송은 사용자·권한 기능 구축 후 사용할 수 있습니다.</p>
            <Button data-testid="billing-remind" disabled>
              다시 알림
            </Button>
            {overdue && (
              <>
                <div className={styles.actions}>
                  {overdue.stage_counts.map((c) => (
                    <Badge
                      key={c.stage}
                      tone={
                        c.stage >= 2
                          ? "danger"
                          : c.stage === 1
                            ? "warning"
                            : "neutral"
                      }
                    >
                      {c.stage}단계 {c.count}건
                    </Badge>
                  ))}
                </div>
                {overdue.customer_totals.map((c, i) => (
                  <p data-testid={`billing-customer-total-${i}`} key={i}>
                    {c.customer_name ?? "고객사 미지정"}{" "}
                    {amount(c.receivable_amt)}
                  </p>
                ))}
              </>
            )}
          </>
        )}
        {loading && <Skeleton />}
        {error && (
          <ErrorBanner
            error={error}
            onRetry={() => setRevision((r) => r + 1)}
          />
        )}
        {!loading && !error && (
          <>
            <p>총 {total}건</p>
            <div className={styles.tableScroll}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    {columns.map(([key, label]) => (
                      <th key={key}>
                        {["recipient_roles", "sent_history"].includes(key) ? (
                          label
                        ) : (
                          <button
                            data-testid={`billing-sort-${key}`}
                            onClick={() =>
                              update(
                                "sort",
                                `${key},${selected.sort === `${key},asc` ? "desc" : "asc"}`,
                              )
                            }
                          >
                            {label}
                          </button>
                        )}
                      </th>
                    ))}
                    {tab === "gaps" && !readonly && <th>작업</th>}
                  </tr>
                </thead>
                <tbody>
                  {!rows.length && (
                    <tr>
                      <td colSpan={columns.length + 1}>
                        조건에 맞는 결과가 없습니다
                      </td>
                    </tr>
                  )}
                  {rows.map((row, i) => (
                    <tr
                      data-testid={`billing-row-${i}`}
                      key={
                        "billing" in row
                          ? row.billing.bill_id
                          : "project_id" in row
                            ? row.project_id
                            : (row.bill_id ?? `stage-${row.bill_stg_id}`)
                      }
                    >
                      {columns.map(([key]) => (
                        <td
                          key={key}
                          className={
                            key.endsWith("_amt") ? styles.amount : undefined
                          }
                        >
                          {cell(row, key)}
                        </td>
                      ))}
                      {tab === "gaps" && !readonly && "project_id" in row && (
                        <td>
                          <Button
                            data-testid={`billing-gap-create-${row.project_id}`}
                            onClick={() => {
                              setContext({
                                projectId: row.project_id,
                                month: row.month,
                              });
                              setModal("new");
                            }}
                          >
                            청구 등록
                          </Button>
                          {row.billing_ids.length > 0 && (
                            <Button
                              data-testid={`billing-gap-edit-${row.project_id}`}
                              onClick={() => void open(row.billing_ids[0])}
                            >
                              청구 고치기
                            </Button>
                          )}
                          {row.suspected_people.map((p) => (
                            <span key={p.management_number}>
                              {p.name} {p.management_number}
                            </span>
                          ))}
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className={styles.actions}>
              <label>
                한 화면 건수
                <select
                  data-testid="billing-size"
                  value={selected.size}
                  onChange={(e) => update("size", Number(e.target.value))}
                >
                  {[20, 50, 100].map((s) => (
                    <option key={s}>{s}</option>
                  ))}
                </select>
              </label>
              <Button
                data-testid="billing-page-previous"
                disabled={Number(selected.page) === 0}
                onClick={() => update("page", Number(selected.page) - 1)}
              >
                이전 쪽
              </Button>
              <span>{Number(selected.page) + 1}쪽</span>
              <Button
                data-testid="billing-page-next"
                disabled={
                  (Number(selected.page) + 1) * Number(selected.size) >= total
                }
                onClick={() => update("page", Number(selected.page) + 1)}
              >
                다음 쪽
              </Button>
            </div>
          </>
        )}
      </section>
      {message && <p role="status">{message}</p>}
      {detail && !modal && !receipt && (
        <Modal title="청구 상세" onCancel={() => setDetail(null)}>
          <section data-testid="billing-detail" className={styles.form}>
            <h3>
              {detail.billing.bill_no} · {detail.billing.project_name}
            </h3>
            <p>{detail.billing.bill_stts_cd}</p>
            {billColumns.map(([key, label]) => (
              <p key={key}>
                {label}: {cell(detail.billing, key)}
              </p>
            ))}
            <p>세금계산서 작성일: {detail.billing.txinv_dt ?? "–"} (기록만)</p>
            {detail.warnings.map((w, i) => (
              <p key={i}>{w}</p>
            ))}
            {detail.details.map((d, i) => (
              <p data-testid={`billing-detail-line-${i}`} key={i}>
                {d.display_name} {d.mm_qty ?? "–"} MM · {amount(d.untprc_amt)} ·{" "}
                {amount(d.bill_amt)}
              </p>
            ))}
            {detail.original_bill_id != null && (
              <Button
                data-testid="billing-original"
                onClick={() => void open(detail.original_bill_id!)}
              >
                원본 청구
              </Button>
            )}
            {detail.amendments.map((a) => (
              <Button
                data-testid={`billing-amendment-link-${a.bill_id}`}
                key={a.bill_id}
                onClick={() => void open(a.bill_id!)}
              >
                {a.bill_no}
              </Button>
            ))}
            <h3>입금 이력</h3>
            {detail.receipts.map((r) => (
              <p
                data-testid={`billing-receipt-${r.receipt_id}`}
                key={r.receipt_id}
              >
                {r.rcpt_dt} {amount(r.rcpt_amt)} {r.rmrk_desc}
                {!readonly &&
                  detail.original_bill_id == null &&
                  detail.billing.bill_stts_cd === "CONFIRM" && (
                    <>
                      <Button
                        data-testid={`billing-receipt-edit-${r.receipt_id}`}
                        onClick={() => setReceipt({ mode: "edit", row: r })}
                      >
                        입금 수정
                      </Button>
                      <Button
                        data-testid={`billing-receipt-delete-${r.receipt_id}`}
                        onClick={() => setReceipt({ mode: "delete", row: r })}
                      >
                        입금 삭제
                      </Button>
                    </>
                  )}
              </p>
            ))}
            <p>조정 청구 총액 {amount(detail.billing.adjusted_total_amt)}</p>
            <h3>변경 이력</h3>
            {detail.change_history.map((h, i) => (
              <p key={i} data-testid={`billing-history-${i}`}>
                {h.changed_at} {h.description}
              </p>
            ))}
            {!readonly && (
              <div className={styles.actions}>
                {detail.billing.bill_stts_cd === "DRAFT" && (
                  <>
                    <Button
                      data-testid="billing-edit"
                      onClick={() => setModal("edit")}
                    >
                      수정
                    </Button>
                    <Button
                      data-testid="billing-recalculate"
                      pending={pending}
                      onClick={() => {
                        setPending(true);
                        client
                          .writeBilling(
                            `/api/billings/${detail.billing.bill_id}`,
                            "PUT",
                            { recalculate: true },
                          )
                          .then(refresh)
                          .catch((e) => setError(client.billingFailure(e)))
                          .finally(() => setPending(false));
                      }}
                    >
                      다시 계산
                    </Button>
                    <Button
                      data-testid="billing-confirm"
                      onClick={() => setModal("confirm")}
                    >
                      확정
                    </Button>
                  </>
                )}
                {detail.billing.bill_stts_cd !== "CANCEL" &&
                  detail.receipts.length === 0 && (
                    <Button
                      data-testid="billing-cancel"
                      onClick={() => setModal("cancel")}
                    >
                      청구 취소
                    </Button>
                  )}
                {detail.billing.bill_stts_cd === "CONFIRM" &&
                  detail.original_bill_id == null && (
                    <>
                      <Button
                        data-testid="billing-amend"
                        onClick={() => setModal("amend")}
                      >
                        수정 청구
                      </Button>
                      <Button
                        data-testid="billing-receipt-add"
                        onClick={() => setReceipt({ mode: "create" })}
                      >
                        입금 등록
                      </Button>
                    </>
                  )}
              </div>
            )}
            <Button
              data-testid="billing-detail-close"
              onClick={() => setDetail(null)}
            >
              닫기
            </Button>
          </section>
        </Modal>
      )}
      {!readonly && (modal === "new" || modal === "edit") && (
        <UI13M1
          detail={modal === "edit" ? (detail ?? undefined) : undefined}
          {...context}
          onClose={() => setModal(null)}
          onSaved={refresh}
          onAttendance={() => setModal("attendance")}
        />
      )}
      {!readonly && modal === "attendance" && (
        <UI13M2 onClose={() => setModal(null)} onSaved={refresh} />
      )}
      {!readonly && detail && modal === "cancel" && (
        <UI13M3
          billing={detail.billing}
          onClose={() => setModal(null)}
          onSaved={refresh}
        />
      )}
      {!readonly && detail && modal === "amend" && (
        <UI13M4
          billing={detail.billing}
          onClose={() => setModal(null)}
          onSaved={refresh}
        />
      )}
      {!readonly && detail && receipt && (
        <UI13M5
          billing={detail.billing}
          receipt={receipt.row}
          mode={receipt.mode}
          onClose={() => setReceipt(null)}
          onSaved={refresh}
        />
      )}
      {!readonly && detail && modal === "confirm" && (
        <Modal title="청구 확정" onCancel={() => setModal(null)}>
          <section data-testid="billing-confirm-dialog">
            <p>
              청구일과 금액을 확인하고 확정합니다. 상세 스냅샷은 이후 변경할 수
              없습니다.
            </p>
            {error && <ErrorBanner error={error} />}
            <Button
              data-testid="billing-confirm-close"
              onClick={() => setModal(null)}
            >
              취소
            </Button>
            <Button
              data-testid="billing-confirm-submit"
              pending={pending}
              onClick={() => {
                setPending(true);
                client
                  .confirmBilling(detail.billing.bill_id!)
                  .then(async () => {
                    await refresh();
                    setModal(null);
                  })
                  .catch((e) => setError(client.billingFailure(e)))
                  .finally(() => setPending(false));
              }}
            >
              확정 확인
            </Button>
          </section>
        </Modal>
      )}
    </section>
  );
}
