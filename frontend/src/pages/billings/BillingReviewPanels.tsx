import { useCallback, useState } from "react";
import { Badge, Button, Pagination, Skeleton } from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { readData } from "../../shared/api";
import { useAdminRead } from "../admin/useAdminResource";
import { billingQuery, type BillingRow } from "./client";
import tables from "../developers/Developers.module.css";
import styles from "./Billings.module.css";

export interface BillingGapRow {
  project_id: number;
  project_name: string;
  month: string;
  basis_supply_amt: number;
  confirmed_supply_amt: number;
  difference_amt: number;
  judgment: string;
  explanation: string;
  attendance_missing: boolean;
  suspected_people: { name: string; management_number: string }[];
  billing_ids: number[];
}
interface GapPage {
  month: string;
  total_difference_amt: number;
  items: BillingGapRow[];
  total: number;
  page: number;
  size: number;
}
interface OverdueRow {
  billing: BillingRow;
  overdue_days: number | null;
  stage: number | null;
  due_status: string;
  recipient_roles: string[];
  next_notification_date: string | null;
  sent_history: { sent_at: string }[];
}
interface OverduePage {
  items: OverdueRow[];
  total: number;
  page: number;
  size: number;
  stage_counts: { stage: number; count: number }[];
  customer_totals: {
    customer_id: number;
    customer_name: string;
    receivable_amt: number;
  }[];
}
export function BillingGapPanel({
  readOnly,
  onRegister,
  onOpen,
}: {
  readOnly: boolean;
  onRegister: (projectId: number, month: string) => void;
  onOpen: (id: number) => void;
}) {
  const initial = {
    month: "",
    judgment: "",
    q: "",
    page: 0,
    size: 50 as 20 | 50 | 100,
  };
  const [draft, setDraft] = useState(initial),
    [filters, setFilters] = useState(initial);
  const read = useAdminRead(
    useCallback(
      () => readData<GapPage>(`/api/billings/gaps?${billingQuery(filters)}`),
      [filters],
    ),
  );
  return (
    <section data-testid="billing-gaps-panel">
      <form
        className={tables.search}
        data-testid="billing-gap-filters"
        onSubmit={(e) => {
          e.preventDefault();
          setFilters({ ...draft, page: 0 });
        }}
      >
        <label>
          월
          <input
            data-testid="billing-gap-month"
            type="month"
            value={draft.month}
            onChange={(e) => setDraft((v) => ({ ...v, month: e.target.value }))}
          />
        </label>
        <label>
          판정
          <select
            data-testid="billing-gap-judgment"
            value={draft.judgment}
            onChange={(e) =>
              setDraft((v) => ({ ...v, judgment: e.target.value }))
            }
          >
            <option value="">전체</option>
            {[
              "맞음",
              "적게 청구",
              "많이 청구",
              "청구 없음",
              "근태 미입력",
              "맞음(무상)",
            ].map((v) => (
              <option
                key={v}
                data-testid={`billing-gap-judgment-option-${["맞음", "적게 청구", "많이 청구", "청구 없음", "근태 미입력", "맞음(무상)"].indexOf(v)}`}
              >
                {v}
              </option>
            ))}
          </select>
        </label>
        <label>
          검색
          <input
            data-testid="billing-gap-search"
            value={draft.q}
            onChange={(e) => setDraft((v) => ({ ...v, q: e.target.value }))}
          />
        </label>
        <Button
          data-testid="billing-gap-reset"
          type="button"
          onClick={() => {
            setDraft(initial);
            setFilters(initial);
          }}
        >
          초기화
        </Button>
        <Button data-testid="billing-gap-query" type="submit" variant="primary">
          조회
        </Button>
      </form>
      {read.loading && <Skeleton label="청구 누락 불러오는 중" />}
      <ErrorBanner
        error={read.error}
        onRetry={() => void read.reload().catch(() => {})}
      />
      {read.data && !read.error && (
        <>
          <p>
            {read.data.month} · 총 차이{" "}
            {read.data.total_difference_amt.toLocaleString()}원
          </p>
          <div className={tables.tableScroll}>
            <table className={tables.table} aria-label="청구 누락 점검">
              <thead>
                <tr>
                  {[
                    "프로젝트",
                    "귀속월",
                    "투입 기준 금액",
                    "청구 공급가",
                    "차이",
                    "판정",
                    "설명",
                    "청구",
                  ].map((h) => (
                    <th
                      key={h}
                      data-testid={`billing-gap-header-${["프로젝트", "귀속월", "투입 기준 금액", "청구 공급가", "차이", "판정", "설명", "청구"].indexOf(h)}`}
                    >
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {read.data.items.map((row) => (
                  <tr
                    key={`${row.project_id}-${row.month}`}
                    data-testid={`billing-gap-${row.project_id}-${row.month}`}
                  >
                    <td>{row.project_name}</td>
                    <td>{row.month}</td>
                    <td className={tables.amount}>
                      {row.basis_supply_amt.toLocaleString()}
                    </td>
                    <td className={tables.amount}>
                      {row.confirmed_supply_amt.toLocaleString()}
                    </td>
                    <td className={tables.amount}>
                      {row.difference_amt.toLocaleString()}
                    </td>
                    <td>
                      <Badge
                        tone={
                          row.attendance_missing
                            ? "warning"
                            : row.judgment.startsWith("맞음")
                              ? "success"
                              : "danger"
                        }
                      >
                        {row.attendance_missing ? "근태 미입력" : row.judgment}
                      </Badge>
                    </td>
                    <td>
                      {row.explanation}
                      {row.suspected_people.map((p) => (
                        <span
                          key={p.management_number}
                          data-testid={`billing-gap-person-${row.project_id}-${row.month}-${row.suspected_people.indexOf(p)}`}
                        >
                          {p.name} ({p.management_number})
                        </span>
                      ))}
                    </td>
                    <td>
                      {row.billing_ids.length > 0
                        ? row.billing_ids.map((id) => (
                            <Button
                              key={id}
                              data-testid={`billing-gap-open-${id}`}
                              onClick={() => onOpen(id)}
                            >
                              기존 청구 열기
                            </Button>
                          ))
                        : !readOnly && (
                            <Can perm="UI-13.create">
                              <Button
                                data-testid={`billing-gap-create-${row.project_id}`}
                                onClick={() =>
                                  onRegister(row.project_id, row.month)
                                }
                              >
                                청구 등록
                              </Button>
                            </Can>
                          )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {read.data.items.length === 0 && (
              <p className={tables.empty}>
                조회 조건에 맞는 청구 누락이 없습니다.
              </p>
            )}
          </div>
          <Pagination
            page={filters.page + 1}
            size={filters.size}
            total={read.data.total}
            onPage={(page) => setFilters((v) => ({ ...v, page: page - 1 }))}
            onSize={(size) => setFilters((v) => ({ ...v, size, page: 0 }))}
          />
        </>
      )}
    </section>
  );
}
export function BillingOverduePanel({
  year,
  onOpen,
  customers,
}: {
  year: number;
  onOpen: (id: number) => void;
  customers: { id: string; name: string }[];
}) {
  const initial = {
    stage: "",
    customerId: "",
    overDays: "",
    q: "",
    page: 0,
    size: 50 as 20 | 50 | 100,
  };
  const [draft, setDraft] = useState(initial),
    [filters, setFilters] = useState(initial);
  const read = useAdminRead(
    useCallback(
      () =>
        readData<OverduePage>(
          `/api/billings/overdue?${billingQuery({ ...filters, year })}`,
        ),
      [filters, year],
    ),
  );
  return (
    <section data-testid="billing-overdue-panel">
      {read.data && (
        <div className={styles.summary}>
          {read.data.stage_counts.map((stage) => (
            <article
              key={stage.stage}
              data-testid={`billing-stage-card-${stage.stage}`}
            >
              <span>{stage.stage}단계</span>
              <strong>{stage.count}건</strong>
            </article>
          ))}
        </div>
      )}
      <form
        className={tables.search}
        data-testid="billing-overdue-filters"
        onSubmit={(e) => {
          e.preventDefault();
          setFilters({ ...draft, page: 0 });
        }}
      >
        <label>
          단계
          <select
            data-testid="billing-overdue-stage"
            value={draft.stage}
            onChange={(e) => setDraft((v) => ({ ...v, stage: e.target.value }))}
          >
            <option value="">전체</option>
            {[0, 1, 2, 3].map((stage) => (
              <option
                key={stage}
                value={stage}
                data-testid={`billing-overdue-stage-option-${stage}`}
              >
                {stage}단계
              </option>
            ))}
          </select>
        </label>
        <label>
          고객사
          <select
            data-testid="billing-overdue-customer"
            value={draft.customerId}
            onChange={(e) =>
              setDraft((v) => ({ ...v, customerId: e.target.value }))
            }
          >
            <option value="">전체</option>
            {customers.map((c) => (
              <option
                key={c.id}
                value={c.id}
                data-testid={`billing-overdue-customer-option-${c.id}`}
              >
                {c.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          연체일수
          <select
            data-testid="billing-overdue-days"
            value={draft.overDays}
            onChange={(e) =>
              setDraft((v) => ({ ...v, overDays: e.target.value }))
            }
          >
            <option value="">전체</option>
            {[30, 60, 90].map((days) => (
              <option
                key={days}
                value={days}
                data-testid={`billing-overdue-days-option-${days}`}
              >
                {days}일 초과
              </option>
            ))}
          </select>
        </label>
        <Button
          data-testid="billing-overdue-reset"
          type="button"
          onClick={() => {
            setDraft(initial);
            setFilters(initial);
          }}
        >
          초기화
        </Button>
        <Button
          data-testid="billing-overdue-query"
          type="submit"
          variant="primary"
        >
          조회
        </Button>
      </form>
      {read.loading && <Skeleton label="미수 알림 불러오는 중" />}
      <ErrorBanner
        error={read.error}
        onRetry={() => void read.reload().catch(() => {})}
      />
      {read.data && !read.error && (
        <>
          <div>
            {read.data.customer_totals.map((customer) => (
              <p
                key={customer.customer_id}
                data-testid={`billing-overdue-customer-total-${customer.customer_id}`}
              >
                {customer.customer_name}:{" "}
                {customer.receivable_amt.toLocaleString()}원
              </p>
            ))}
          </div>
          <div className={tables.tableScroll}>
            <table className={tables.table} aria-label="미수 알림">
              <thead>
                <tr>
                  {[
                    "고객사",
                    "프로젝트",
                    "청구번호",
                    "미수금",
                    "입금 예정일",
                    "지난 일수",
                    "단계",
                    "수신 역할",
                    "보낸 알림",
                    "다음 알림",
                  ].map((h) => (
                    <th
                      key={h}
                      data-testid={`billing-overdue-header-${["고객사", "프로젝트", "청구번호", "미수금", "입금 예정일", "지난 일수", "단계", "수신 역할", "보낸 알림", "다음 알림"].indexOf(h)}`}
                    >
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {read.data.items.map((row) => (
                  <tr
                    key={row.billing.bill_id}
                    data-testid={`billing-overdue-${row.billing.bill_id}`}
                  >
                    <td>{row.billing.customer_name}</td>
                    <td>{row.billing.project_name}</td>
                    <td>
                      <Button
                        data-testid={`billing-overdue-open-${row.billing.bill_id}`}
                        variant="text"
                        onClick={() => onOpen(row.billing.bill_id!)}
                      >
                        {row.billing.bill_no}
                      </Button>
                    </td>
                    <td className={tables.amount}>
                      {row.billing.receivable_amt.toLocaleString()}
                    </td>
                    <td>{row.billing.rcpt_plan_dt ?? "예정일 미입력"}</td>
                    <td>{row.overdue_days ?? "—"}</td>
                    <td>
                      <Badge
                        tone={(row.stage ?? 0) >= 2 ? "danger" : "warning"}
                      >
                        {row.stage === null ? "대상 아님" : `${row.stage}단계`}
                      </Badge>
                    </td>
                    <td>{row.recipient_roles.join(", ")}</td>
                    <td>
                      {row.sent_history.length
                        ? row.sent_history.map((h) => h.sent_at).join(", ")
                        : "보낸 알림 없음"}
                    </td>
                    <td>{row.next_notification_date ?? "없음"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {read.data.items.length === 0 && (
              <p className={tables.empty}>미수 알림 대상이 없습니다.</p>
            )}
          </div>
          <Pagination
            page={filters.page + 1}
            size={filters.size}
            total={read.data.total}
            onPage={(page) => setFilters((v) => ({ ...v, page: page - 1 }))}
            onSize={(size) => setFilters((v) => ({ ...v, size, page: 0 }))}
          />
        </>
      )}
      <Can perm="UI-13.remind">
        <Button data-testid="billing-remind" disabled>
          다시 알림
        </Button>
      </Can>
      <p className={styles.readonly}>
        알림 발송은 사용자·권한 기능 구축 후 사용할 수 있습니다.
      </p>
    </section>
  );
}
