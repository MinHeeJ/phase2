import { useCallback, useState } from "react";
import { Badge, Button, Pagination, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "../admin/useAdminResource";
import { failure } from "../admin/client";
import type { BillingScreenContext } from "../billings/UI13Screen";
import {
  listBillingOverdue,
  query,
  type BillingFilters,
} from "../billingsops/client";
import { listPartnerOptions } from "../partnersops/client";
import { downloadUploadFile } from "../uploadsops/client";
import tables from "../developers/Developers.module.css";
import styles from "./Overdue.module.css";

const customers = () => listPartnerOptions("CUST");
type OverdueFilters = BillingFilters & { page: number; size: 20 | 50 | 100 };
const defaults = (): OverdueFilters => ({
  stage: "",
  customerId: "",
  overDays: "",
  q: "",
  page: 0,
  size: 50,
  sort: "overdue_days,desc",
});
const stages = [
  { stage: 1, label: "1단계 예정일 + 7일", tone: "warning" },
  { stage: 2, label: "2단계 예정일 + 30일", tone: "danger" },
  { stage: 3, label: "3단계 예정일 + 60일", tone: "danger" },
] as const;
const columns = [
  ["project_name", "프로젝트"],
  ["bill_id", "청구번호"],
  ["receivable_amt", "미수금"],
  ["rcpt_plan_dt", "입금 예정일"],
  ["overdue_days", "지난 일수"],
  ["stage", "지금 단계"],
] as const;

export function OverduePanel(context: BillingScreenContext) {
  return <OverdueContent key={context.year} {...context} />;
}
function OverdueContent({ year, openBilling }: BillingScreenContext) {
  const [filters, setFilters] = useState(defaults);
  const [draft, setDraft] = useState(filters);
  const [downloadError, setDownloadError] = useState<ReturnType<
    typeof failure
  > | null>(null);
  const [downloading, setDownloading] = useState(false);
  const resource = useAdminRead(
    useCallback(
      () => listBillingOverdue({ ...filters, year }),
      [filters, year],
    ),
  );
  const options = useAdminRead(customers);
  const reset = () => {
    const next = defaults();
    setDraft(next);
    setFilters(next);
  };
  const download = async () => {
    setDownloading(true);
    setDownloadError(null);
    const { page: _page, size: _size, ...scope } = filters;
    try {
      await downloadUploadFile(
        `/api/billings/overdue/export?${query({ ...scope, year })}`,
        `미수알림_${year}.xlsx`,
      );
    } catch (e) {
      setDownloadError(failure(e));
    } finally {
      setDownloading(false);
    }
  };
  return (
    <section data-testid="overdue-panel" role="tabpanel" aria-label="미수 알림">
      <p className={styles.notice}>
        입금 예정일을 기준으로 단계와 수신 역할을 안내합니다. 이 화면에서 알림을
        자동 발송하지 않습니다.
      </p>
      <form
        className={tables.search}
        onSubmit={(e) => {
          e.preventDefault();
          setFilters({ ...draft, page: 0 });
        }}
      >
        <label>
          단계
          <select
            data-testid="overdue-stage"
            value={draft.stage}
            onChange={(e) => setDraft({ ...draft, stage: e.target.value })}
          >
            <option value="">전체</option>
            <option value="0">단계 전</option>
            {stages.map((s) => (
              <option key={s.stage} value={s.stage}>
                {s.stage}단계
              </option>
            ))}
          </select>
        </label>
        <label>
          고객사
          <select
            data-testid="overdue-customer"
            value={draft.customerId}
            onChange={(e) => setDraft({ ...draft, customerId: e.target.value })}
          >
            <option value="">전체</option>
            {options.data?.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          지난 일수
          <select
            data-testid="overdue-days"
            value={draft.overDays}
            onChange={(e) => setDraft({ ...draft, overDays: e.target.value })}
          >
            <option value="">전체</option>
            <option value="30">30일 초과</option>
            <option value="60">60일 초과</option>
            <option value="90">90일 초과</option>
          </select>
        </label>
        <label>
          검색
          <input
            data-testid="overdue-query"
            maxLength={200}
            placeholder="프로젝트·청구번호·고객사"
            value={draft.q}
            onChange={(e) => setDraft({ ...draft, q: e.target.value })}
          />
        </label>
        <Button type="button" data-testid="overdue-reset" onClick={reset}>
          초기화
        </Button>
        <Button type="submit" data-testid="overdue-search" variant="primary">
          조회
        </Button>
      </form>
      <ErrorBanner
        error={options.error}
        onRetry={() => void options.reload().catch(() => {})}
      />
      <ErrorBanner
        error={resource.error}
        onRetry={() => void resource.reload().catch(() => {})}
      />
      <ErrorBanner error={downloadError} />
      {resource.loading ? (
        <Skeleton />
      ) : (
        !resource.error &&
        resource.data && (
          <>
            <div className={styles.stages}>
              {stages.map((s) => (
                <article
                  className={tables.card}
                  key={s.stage}
                  data-testid={`overdue-stage-card-${s.stage}`}
                >
                  <h2>{s.label}</h2>
                  <Badge tone={s.tone}>
                    {resource.data?.stage_counts.find(
                      (c) => c.stage === s.stage,
                    )?.count ?? 0}
                    건
                  </Badge>
                </article>
              ))}
            </div>
            <div className={tables.toolbar}>
              <h2>미수 청구 {resource.data.total}건</h2>
              <Button
                data-testid="overdue-export"
                pending={downloading}
                onClick={() => void download()}
              >
                엑셀 다운로드
              </Button>
            </div>
            <div className={tables.tableScroll}>
              <table className={tables.table}>
                <thead>
                  <tr>
                    {columns.map(([key, label]) => (
                      <th key={key}>
                        <Button
                          variant="text"
                          small
                          data-testid={`overdue-sort-${key.replaceAll("_", "-")}`}
                          onClick={() =>
                            setFilters((f) => ({
                              ...f,
                              page: 0,
                              sort: `${key},${f.sort === `${key},asc` ? "desc" : "asc"}`,
                            }))
                          }
                        >
                          {label}
                        </Button>
                      </th>
                    ))}
                    <th>보낸 알림</th>
                    <th>다음 알림</th>
                  </tr>
                </thead>
                <tbody>
                  {resource.data.items.map((r) => (
                    <tr
                      key={r.billing.bill_id}
                      data-testid={`overdue-row-${r.billing.bill_id}`}
                    >
                      <td>
                        {r.billing.project_name}
                        <small className={styles.secondary}>
                          {r.billing.customer_name ?? "–"}
                        </small>
                      </td>
                      <td>
                        <Button
                          variant="text"
                          data-testid={`overdue-open-${r.billing.bill_id}`}
                          onClick={() => {
                            if (r.billing.bill_id != null)
                              openBilling(r.billing.bill_id);
                          }}
                        >
                          {r.billing.bill_no}
                        </Button>
                      </td>
                      <td className={tables.amount}>
                        {r.billing.receivable_amt.toLocaleString("ko-KR")}
                      </td>
                      <td>{r.billing.rcpt_plan_dt ?? "예정일 미입력"}</td>
                      <td className={tables.amount}>{r.overdue_days ?? "–"}</td>
                      <td>
                        <Badge
                          tone={
                            r.stage == null || r.stage === 0
                              ? "neutral"
                              : r.stage === 1
                                ? "warning"
                                : "danger"
                          }
                        >
                          {r.stage == null || r.stage === 0
                            ? r.due_status
                            : `${r.stage}단계 · ${r.overdue_days}일`}
                        </Badge>
                        <small className={styles.secondary}>
                          {r.recipient_roles.join(" · ")}
                        </small>
                      </td>
                      <td>
                        {r.sent_history.length
                          ? r.sent_history.map((s, i) => (
                              <small
                                className={styles.secondary}
                                key={`${s.sent_at}-${i}`}
                              >
                                {s.sent_at.replace("T", " ")}
                              </small>
                            ))
                          : "발송 이력 없음"}
                      </td>
                      <td>{r.next_notification_date ?? "–"}</td>
                    </tr>
                  ))}
                  {resource.data.items.length === 0 && (
                    <tr>
                      <td colSpan={8}>
                        <div className={tables.empty}>
                          <p>조건에 맞는 결과가 없습니다</p>
                          <Button
                            data-testid="overdue-empty-reset"
                            onClick={reset}
                          >
                            조건 초기화
                          </Button>
                        </div>
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
            <div className={styles.customers} aria-label="고객사별 미수 합계">
              {resource.data.customer_totals.map((c) => (
                <span
                  key={c.customer_id ?? "unassigned"}
                  data-testid={`overdue-customer-total-${c.customer_id ?? "unassigned"}`}
                >
                  {c.customer_name ?? "고객사 미지정"}{" "}
                  {c.receivable_amt.toLocaleString("ko-KR")}원
                </span>
              ))}
            </div>
            <Pagination
              page={filters.page + 1}
              size={filters.size}
              total={resource.data.total}
              onPage={(p) => setFilters((f) => ({ ...f, page: p - 1 }))}
              onSize={(size) => setFilters((f) => ({ ...f, page: 0, size }))}
            />
          </>
        )
      )}
    </section>
  );
}
