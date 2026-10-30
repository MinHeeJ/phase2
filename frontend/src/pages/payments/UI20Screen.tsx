import { useCallback, useEffect, useState, type ReactNode } from "react";
import {
  Badge,
  Button,
  LockedBanner,
  Modal,
  Pagination,
  Skeleton,
} from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { ScreenHeading } from "../shared/ScreenHeading";
import { seoulMonth } from "../shared/PeriodBar";
import { useSharedMobile } from "../shared/PlaceholderScreen";
import tables from "../developers/Developers.module.css";
import * as client from "./client";
import type {
  Payment,
  PaymentInput,
  PaymentList,
  PaymentOrder,
} from "./client";

const money = (n: number) => n.toLocaleString("ko-KR");
const labels = {
  PLAN: "예정",
  PAID: "지급",
  UNPAID: "미지급/이월",
  CANCEL: "취소",
};
type Dialog =
  | { type: "edit" | "complete" | "reopen" | "carry"; row: Payment }
  | { type: "create" | "export" | "orders" };
// SCR-UI-20 — /UI-20. All payment windows stay inside this mounted screen.
export default function UI20Screen({
  name = "지급 관리",
  groupName = "재무·자금",
  onDirtyChange,
  renderPaymentOrders,
}: {
  name?: string;
  groupName?: string;
  onDirtyChange?: (dirty: boolean) => void;
  renderPaymentOrders?: (context: {
    month: string;
    readOnly: boolean;
    refreshVersion?: PaymentList | null;
    onSaved: () => void;
  }) => ReactNode;
}) {
  const mobile = useSharedMobile();
  const [month, setMonth] = useState(
    () => sessionStorage.getItem("payments-month") ?? seoulMonth(),
  );
  const [page, setPage] = useState(1),
    [size, setSize] = useState<20 | 50 | 100>(20);
  const [order, setOrder] = useState<number | null>(null),
    [category, setCategory] = useState(""),
    [status, setStatus] = useState("");
  const [selected, setSelected] = useState<number[]>([]),
    [dialog, setDialog] = useState<Dialog | null>(null);
  const [message, setMessage] = useState("");
  const read = useCallback(() => client.listFinancePayments(month), [month]);
  const list = useAdminRead(read);
  const write = useAdminWrite(list.reload);
  useEffect(() => {
    onDirtyChange?.(dialog != null);
    return () => onDirtyChange?.(false);
  }, [dialog, onDirtyChange]);
  const close = () => {
    if (!write.pending) {
      setDialog(null);
      write.clear();
    }
  };
  const rows = list.loading || list.error ? [] : (list.data?.items ?? []);
  const filtered = rows.filter(
    (r) =>
      (order == null || r.pay_odr_no === order) &&
      (!category || r.pay_se_cd === category) &&
      (!status || r.pay_stts_cd === status),
  );
  const blocked =
    list.loading ||
    !!list.error ||
    !list.data ||
    list.data.closed ||
    month.slice(0, 4) < seoulMonth().slice(0, 4) ||
    write.pending;
  const save = (action: () => Promise<unknown>) =>
    write.save(action, () => {
      setDialog(null);
      setSelected([]);
      setMessage("저장했습니다.");
    });
  if (mobile)
    return (
      <section data-testid="payments-mobile-screen">
        <p>PC에서 이용해 주세요</p>
        <a data-testid="payments-dashboard-link" href="/UI-16">
          대시보드로
        </a>
      </section>
    );
  return (
    <section
      data-testid="payments-screen"
      data-screen-id="SCR-UI-20"
      aria-label="지급 관리 화면"
    >
      <ScreenHeading
        screenId="UI-20"
        name={name}
        groupName={groupName}
        actions={
          <Button
            data-testid="payment-export-button"
            disabled={!selected.length || list.loading}
            onClick={() => setDialog({ type: "export" })}
          >
            은행 이체 파일
          </Button>
        }
      />
      <div className={tables.search}>
        <label>
          조회월{" "}
          <input
            data-testid="payment-month-input"
            aria-label="조회월"
            type="month"
            value={month}
            onChange={(e) => {
              if (!e.target.value) return;
              setMonth(e.target.value);
              sessionStorage.setItem("payments-month", e.target.value);
              setSelected([]);
              setPage(1);
              setDialog(null);
            }}
          />
        </label>
        {list.data && (
          <Badge tone={list.data.closed ? "locked" : "info"}>
            {list.data.closed ? "마감" : "작업중"}
          </Badge>
        )}
        <Button
          data-testid="payment-query-button"
          onClick={() => void list.reload().catch(() => {})}
        >
          조회
        </Button>
        {!renderPaymentOrders && (
          <Button
            data-testid="payment-orders-button"
            disabled={blocked}
            onClick={() => setDialog({ type: "orders" })}
          >
            차수 설정
          </Button>
        )}
      </div>
      {renderPaymentOrders?.({
        month,
        readOnly: blocked,
        refreshVersion: list.data,
        onSaved: () => {
          void list.reload().catch(() => {});
        },
      })}
      <ErrorBanner
        error={list.error}
        onRetry={() => void list.reload().catch(() => {})}
      />
      <ErrorBanner
        error={write.error}
        onRetry={write.uncertain ? () => void write.recover() : undefined}
      />
      {message && <p role="status">{message}</p>}
      {list.data?.closed && (
        <LockedBanner>마감된 월입니다. 조회만 가능합니다.</LockedBanner>
      )}
      {list.data?.warnings.map((w, i) => (
        <p role="status" key={i}>
          <Badge tone="warning">{w}</Badge>
        </p>
      ))}
      {list.loading && <Skeleton />}
      {list.data && !list.loading && !list.error && (
        <div className={tables.toolbar} data-testid="payment-month-summary">
          <span>
            계획{" "}
            {money(list.data.summary.reduce((n, s) => n + s.planned_amount, 0))}
            원
          </span>
          <span>
            지급{" "}
            {money(list.data.summary.reduce((n, s) => n + s.paid_amount, 0))}원
          </span>
          <span>
            잔액{" "}
            {money(
              list.data.summary.reduce((n, s) => n + s.remaining_amount, 0),
            )}
            원
          </span>
        </div>
      )}
      <div role="tablist" aria-label="지급 차수" className={tables.actions}>
        <Button
          role="tab"
          data-testid="payment-order-all-tab"
          aria-selected={order == null}
          onClick={() => {
            setOrder(null);
            setPage(1);
          }}
        >
          전체 {list.data?.total ?? 0}
        </Button>
        {list.data?.summary.map((s) => (
          <Button
            key={s.pay_odr_no ?? "none"}
            role="tab"
            data-testid={`payment-order-${s.pay_odr_no ?? "none"}-tab`}
            aria-selected={order === s.pay_odr_no}
            onClick={() => {
              setOrder(s.pay_odr_no);
              setPage(1);
            }}
          >
            {s.pay_odr_no == null ? "미지정" : `${s.pay_odr_no}차`} {s.count} ·
            잔액 {money(s.remaining_amount)}원
          </Button>
        ))}
      </div>
      <div className={tables.search}>
        <label>
          지급 구분{" "}
          <select
            data-testid="payment-category-filter"
            value={category}
            onChange={(e) => {
              setCategory(e.target.value);
              setPage(1);
            }}
          >
            <option value="">전체</option>
            {list.data?.categories.map((c) => (
              <option key={c.code} value={c.code}>
                {c.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          상태{" "}
          <select
            data-testid="payment-status-filter"
            value={status}
            onChange={(e) => {
              setStatus(e.target.value);
              setPage(1);
            }}
          >
            <option value="">전체</option>
            {Object.entries(labels).map(([key, label]) => (
              <option key={key} value={key}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <Button
          data-testid="payment-generate-button"
          disabled={blocked}
          pending={write.pending}
          onClick={() => void save(() => client.generateFinancePayments(month))}
        >
          원천 재생성
        </Button>
        <Button
          data-testid="payment-create-button"
          disabled={blocked}
          onClick={() => setDialog({ type: "create" })}
        >
          수기 지급 추가
        </Button>
      </div>
      <div className={tables.toolbar}>
        <span>총 {list.data?.total ?? 0}건</span>
        <span>
          선택 {selected.length}건 · 검토액{" "}
          {money(
            rows
              .filter((r) => selected.includes(r.pay_id))
              .reduce((a, r) => a + r.pay_amt - r.executed_amt, 0),
          )}
          원
        </span>
      </div>
      <div className={tables.tableScroll}>
        <table className={tables.table}>
          <thead>
            <tr>
              {[
                "선택",
                "차수",
                "받는 사람·거래처",
                "지급 구분",
                "공급가",
                "부가세",
                "공제",
                "지급액",
                "지급 예정일",
                "상태",
                "관리",
              ].map((c) => (
                <th key={c}>{c}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {!list.loading && !list.error && !filtered.length && (
              <tr>
                <td colSpan={11} className={tables.empty}>
                  조건에 맞는 결과가 없습니다
                </td>
              </tr>
            )}
            {filtered.slice((page - 1) * size, page * size).map((r) => (
              <tr key={r.pay_id} data-testid={`payment-row-${r.pay_id}`}>
                <td>
                  <input
                    data-testid={`payment-select-${r.pay_id}`}
                    aria-label={`${r.recipient} 선택`}
                    type="checkbox"
                    disabled={r.pay_stts_cd !== "PLAN"}
                    checked={selected.includes(r.pay_id)}
                    onChange={(e) =>
                      setSelected(
                        e.target.checked
                          ? [...selected, r.pay_id]
                          : selected.filter((id) => id !== r.pay_id),
                      )
                    }
                  />
                </td>
                <td>{r.pay_odr_no == null ? "–" : `${r.pay_odr_no}차`}</td>
                <td>{r.recipient}</td>
                <td>
                  {list.data?.categories.find((c) => c.code === r.pay_se_cd)
                    ?.name ?? r.pay_se_cd}
                </td>
                <td className={tables.numeric}>{money(r.sply_amt)}</td>
                <td className={tables.numeric}>{money(r.vat_amt)}</td>
                <td className={tables.numeric}>{money(r.ddct_amt)}</td>
                <td className={tables.numeric}>
                  {money(r.pay_amt)}
                  {r.executed_amt > 0 && (
                    <small>
                      {" "}
                      · 지급 {money(r.executed_amt)} / 잔액{" "}
                      {money(r.pay_amt - r.executed_amt)}
                    </small>
                  )}
                </td>
                <td>{r.pay_plan_dt}</td>
                <td>
                  <Badge
                    tone={
                      r.pay_stts_cd === "PAID"
                        ? "success"
                        : r.pay_stts_cd === "PLAN"
                          ? "info"
                          : r.pay_stts_cd === "UNPAID"
                            ? "warning"
                            : "neutral"
                    }
                  >
                    {labels[r.pay_stts_cd]}
                  </Badge>
                </td>
                <td>
                  {r.pay_stts_cd === "PLAN" && (
                    <>
                      <Button
                        data-testid={`payment-edit-${r.pay_id}`}
                        disabled={blocked}
                        onClick={() => setDialog({ type: "edit", row: r })}
                      >
                        수정
                      </Button>
                      <Button
                        data-testid={`payment-complete-${r.pay_id}`}
                        disabled={blocked}
                        onClick={() => setDialog({ type: "complete", row: r })}
                      >
                        지급 완료
                      </Button>
                      <Button
                        data-testid={`payment-carry-${r.pay_id}`}
                        disabled={blocked}
                        onClick={() => setDialog({ type: "carry", row: r })}
                      >
                        이월
                      </Button>
                    </>
                  )}
                  {r.executed_amt > 0 && (
                    <Button
                      data-testid={`payment-reopen-${r.pay_id}`}
                      disabled={blocked}
                      onClick={() => setDialog({ type: "reopen", row: r })}
                    >
                      완료 취소
                    </Button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <Pagination
        page={page}
        size={size}
        total={filtered.length}
        onPage={setPage}
        onSize={(s) => {
          setSize(s);
          setPage(1);
        }}
      />
      {dialog && list.data && (
        <PaymentWindow
          key={dialog.type}
          dialog={dialog}
          month={month}
          data={list.data}
          selected={selected}
          close={close}
          pending={write.pending}
          uncertain={write.uncertain}
          error={write.error}
          save={save}
        />
      )}
    </section>
  );
}

function PaymentWindow({
  dialog,
  month,
  data,
  selected,
  close,
  pending,
  uncertain,
  error,
  save,
}: {
  dialog: Dialog;
  month: string;
  data: PaymentList;
  selected: number[];
  close: () => void;
  pending: boolean;
  uncertain: boolean;
  error: ApiFailure | null;
  save: (fn: () => Promise<unknown>) => Promise<void>;
}) {
  const row = "row" in dialog ? dialog.row : null;
  const [form, setForm] = useState<PaymentInput>(() =>
    row
      ? {
          pay_ym: row.pay_ym,
          pay_odr_no: row.pay_odr_no,
          pay_se_cd: row.pay_se_cd,
          rcvr_se_cd: row.rcvr_se_cd,
          dev_id: row.dev_id,
          ptnr_id: row.ptnr_id,
          recipient: row.recipient,
          sply_amt: row.sply_amt,
          vat_amt: row.vat_amt,
          ddct_amt: row.ddct_amt,
          pay_plan_dt: row.pay_plan_dt,
          bank: row.bank,
          account: row.account,
          dpstr_nm: row.dpstr_nm,
          remark: row.remark,
          updated_at: row.updated_at,
        }
      : {
          pay_ym: month,
          pay_odr_no: null,
          pay_se_cd: "",
          rcvr_se_cd: "ETC",
          dev_id: null,
          ptnr_id: null,
          recipient: "",
          sply_amt: 0,
          vat_amt: 0,
          ddct_amt: 0,
          pay_plan_dt: "",
          bank: "",
          account: "",
          dpstr_nm: "",
          remark: "",
        },
  );
  const [date, setDate] = useState(""),
    [amount, setAmount] = useState(row ? row.pay_amt - row.executed_amt : 0),
    [reason, setReason] = useState(""),
    [target, setTarget] = useState("");
  const [validation, setValidation] = useState("");
  const titles = {
    create: "수기 지급 추가",
    edit: "지급 정보 수정",
    complete: "지급 완료",
    reopen: "완료 취소",
    carry: "미지급 이월",
    export: "은행 이체 검토",
    orders: "지급 차수 설정",
  };
  const set = <K extends keyof PaymentInput>(key: K, value: PaymentInput[K]) =>
    setForm({ ...form, [key]: value });
  const submit = () => {
    setValidation("");
    if (dialog.type === "create" || dialog.type === "edit") {
      if (form.sply_amt + form.vat_amt - form.ddct_amt < 0) {
        setValidation("지급액은 0원 이상이어야 합니다.");
        return;
      }
      if (
        !form.recipient ||
        !form.bank ||
        !form.account ||
        !form.dpstr_nm ||
        !form.pay_se_cd ||
        !form.pay_plan_dt
      ) {
        setValidation("필수 항목을 입력하세요.");
        return;
      }
      return save(() =>
        row
          ? client.updateFinancePayment(row.pay_id, form)
          : client.createFinancePayment(form),
      );
    }
    if (dialog.type === "complete" && row) {
      if (
        !date ||
        !Number.isSafeInteger(amount) ||
        amount <= 0 ||
        amount > row.pay_amt - row.executed_amt
      ) {
        setValidation("지급일과 잔액 이하의 지급액을 입력하세요.");
        return;
      }
      return save(() =>
        client.completeFinancePayment(row.pay_id, {
          pay_dt: date,
          paid_amount: amount,
          updated_at: row.updated_at,
        }),
      );
    }
    if (dialog.type === "reopen" && row) {
      if (!reason.trim()) {
        setValidation("취소 사유를 입력하세요.");
        return;
      }
      return save(() => client.reopenFinancePayment(row.pay_id, reason));
    }
    if (dialog.type === "carry" && row) {
      if (!target) {
        setValidation("다음 미마감 월을 선택하세요.");
        return;
      }
      return save(() => client.carryFinancePayment(row.pay_id, target));
    }
    if (dialog.type === "export")
      return save(() => client.exportFinancePayments(month, selected));
  };
  if (dialog.type === "orders")
    return <PaymentOrderWindow month={month} close={close} refresh={save} />;
  return (
    <Modal title={titles[dialog.type]} onCancel={close}>
      <div data-testid="payment-window" className={tables.search}>
        <ErrorBanner error={error} />
        {validation && <p role="alert">{validation}</p>}
        {(dialog.type === "create" || dialog.type === "edit") && (
          <>
            <label>
              지급 구분{" "}
              <select
                data-testid="payment-type-input"
                value={form.pay_se_cd}
                onChange={(e) => set("pay_se_cd", e.target.value)}
              >
                <option value="">선택</option>
                {data.categories.map((c) => (
                  <option key={c.code} value={c.code}>
                    {c.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              차수{" "}
              <select
                data-testid="payment-order-input"
                value={form.pay_odr_no ?? ""}
                onChange={(e) =>
                  set(
                    "pay_odr_no",
                    e.target.value ? Number(e.target.value) : null,
                  )
                }
              >
                <option value="">미지정</option>
                {[1, 2, 3, 4, 5].map((n) => (
                  <option key={n} value={n}>
                    {n}차
                  </option>
                ))}
              </select>
            </label>
            <label>
              수령 구분{" "}
              <select
                data-testid="payment-receiver-type-input"
                value={form.rcvr_se_cd}
                onChange={(e) => set("rcvr_se_cd", e.target.value)}
              >
                {["DEV", "PARTNER", "AGENCY", "ETC"].map((v) => (
                  <option key={v} value={v}>
                    {
                      {
                        DEV: "인력",
                        PARTNER: "협력사",
                        AGENCY: "기관",
                        ETC: "기타",
                      }[v as "DEV"]
                    }
                  </option>
                ))}
              </select>
            </label>
            {form.rcvr_se_cd === "DEV" && (
              <label>
                인력 ID{" "}
                <input
                  data-testid="payment-developer-input"
                  type="number"
                  min="1"
                  value={form.dev_id ?? ""}
                  onChange={(e) =>
                    set(
                      "dev_id",
                      e.target.value ? Number(e.target.value) : null,
                    )
                  }
                />
              </label>
            )}
            {form.rcvr_se_cd === "PARTNER" && (
              <label>
                협력사 ID{" "}
                <input
                  data-testid="payment-partner-input"
                  type="number"
                  min="1"
                  value={form.ptnr_id ?? ""}
                  onChange={(e) =>
                    set(
                      "ptnr_id",
                      e.target.value ? Number(e.target.value) : null,
                    )
                  }
                />
              </label>
            )}
            <label>
              수령인{" "}
              <input
                data-testid="payment-recipient-input"
                required
                maxLength={100}
                value={form.recipient}
                onChange={(e) => set("recipient", e.target.value)}
              />
            </label>
            {(
              [
                ["sply_amt", "공급가"],
                ["vat_amt", "부가세"],
                ["ddct_amt", "공제"],
              ] as const
            ).map(([key, label]) => (
              <label key={key}>
                {label}{" "}
                <input
                  data-testid={`payment-${key.replaceAll("_", "-")}-input`}
                  type="number"
                  min="0"
                  step="1"
                  value={form[key]}
                  onChange={(e) => set(key, Number(e.target.value))}
                />
              </label>
            ))}
            <output>
              지급액 {money(form.sply_amt + form.vat_amt - form.ddct_amt)}원
            </output>
            <label>
              지급 예정일{" "}
              <input
                data-testid="payment-plan-date-input"
                type="date"
                required
                value={form.pay_plan_dt}
                onChange={(e) => set("pay_plan_dt", e.target.value)}
              />
            </label>
            <label>
              은행{" "}
              <select
                data-testid="payment-bank-input"
                value={form.bank ?? ""}
                onChange={(e) => set("bank", e.target.value)}
              >
                <option value="">선택</option>
                {data.banks.map((b) => (
                  <option key={b.code} value={b.code}>
                    {b.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              계좌{" "}
              <input
                data-testid="payment-account-input"
                maxLength={30}
                value={form.account ?? ""}
                onChange={(e) => set("account", e.target.value)}
              />
            </label>
            <label>
              예금주{" "}
              <input
                data-testid="payment-depositor-input"
                maxLength={50}
                value={form.dpstr_nm ?? ""}
                onChange={(e) => set("dpstr_nm", e.target.value)}
              />
            </label>
            <label>
              비고{" "}
              <textarea
                data-testid="payment-remark-input"
                maxLength={500}
                value={form.remark ?? ""}
                onChange={(e) => set("remark", e.target.value)}
              />
            </label>
          </>
        )}
        {dialog.type === "complete" && (
          <>
            <label>
              지급일{" "}
              <input
                data-testid="payment-actual-date-input"
                type="date"
                value={date}
                onChange={(e) => setDate(e.target.value)}
              />
            </label>
            <label>
              지급액{" "}
              <input
                data-testid="payment-paid-amount-input"
                type="number"
                min="1"
                max={row!.pay_amt - row!.executed_amt}
                value={amount}
                onChange={(e) => setAmount(Number(e.target.value))}
              />
            </label>
            <p>부분 지급은 잔액을 보존하고 전액 지급 시 완료됩니다.</p>
          </>
        )}
        {dialog.type === "reopen" && (
          <label>
            취소 사유{" "}
            <textarea
              data-testid="payment-reason-input"
              maxLength={500}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />
          </label>
        )}
        {dialog.type === "carry" && (
          <label>
            다음 미마감 월{" "}
            <input
              data-testid="payment-carry-month-input"
              type="month"
              value={target}
              onChange={(e) => setTarget(e.target.value)}
            />
          </label>
        )}
        {dialog.type === "export" && (
          <>
            <p>
              검토용 파일이며 은행 제출용이 아닙니다. 다운로드로 지급 완료되지
              않습니다.
            </p>
            <p>
              총 {selected.length}건 · 총액{" "}
              {money(
                data.items
                  .filter((r) => selected.includes(r.pay_id))
                  .reduce((sum, r) => sum + r.pay_amt - r.executed_amt, 0),
              )}
              원
            </p>
          </>
        )}
        <div className={tables.actions}>
          <Button
            data-testid="payment-cancel-button"
            disabled={pending}
            onClick={close}
          >
            취소
          </Button>
          <Button
            data-testid="payment-save-button"
            variant="primary"
            pending={pending}
            disabled={uncertain}
            onClick={() => void submit()}
          >
            {dialog.type === "export" ? "검토 xlsx 다운로드" : "확인"}
          </Button>
        </div>
      </div>
    </Modal>
  );
}
function PaymentOrderWindow({
  month,
  close,
  refresh,
}: {
  month: string;
  close: () => void;
  refresh: (fn: () => Promise<unknown>) => Promise<void>;
}) {
  const read = useCallback(
    () => client.listFinancePaymentOrders(month),
    [month],
  );
  const resource = useAdminRead(read);
  const [items, setItems] = useState<PaymentOrder[]>([]);
  const locked = resource.data?.month_status === "CLOSED";
  useEffect(() => {
    if (resource.data) setItems(resource.data.items);
  }, [resource.data]);
  return (
    <Modal title="지급 차수 설정" onCancel={close}>
      <div data-testid="payment-orders-window" className={tables.search}>
        <ErrorBanner
          error={resource.error}
          onRetry={() => void resource.reload().catch(() => {})}
        />
        {resource.loading && <Skeleton />}
        {items.map((r, i) => (
          <label key={r.pay_odr_no}>
            {r.pay_odr_no}차{" "}
            <input
              data-testid={`payment-order-date-${r.pay_odr_no}`}
              type="date"
              value={r.planned_date}
              disabled={
                resource.loading || !!resource.error || locked || r.paid
              }
              onChange={(e) =>
                setItems(
                  items.map((v, j) =>
                    j === i ? { ...v, planned_date: e.target.value } : v,
                  ),
                )
              }
            />
          </label>
        ))}
        <Button
          data-testid="payment-orders-add-button"
          disabled={
            resource.loading || !!resource.error || locked || items.length >= 5
          }
          onClick={() => {
            const next = [1, 2, 3, 4, 5].find(
              (n) => !items.some((r) => r.pay_odr_no === n),
            );
            if (next)
              setItems([...items, { pay_odr_no: next, planned_date: "" }]);
          }}
        >
          차수 추가
        </Button>
        <p>
          월 최대 5차 · 기본일 10일/15일/20일/말일 · 5차 예비. 미마감·미지급
          건만 변경됩니다.
        </p>
        <Button data-testid="payment-orders-cancel-button" onClick={close}>
          취소
        </Button>
        <Button
          data-testid="payment-orders-save-button"
          disabled={resource.loading || !!resource.error || locked}
          onClick={() =>
            void refresh(() => client.updateFinancePaymentOrders(month, items))
          }
        >
          저장
        </Button>
      </div>
    </Modal>
  );
}
