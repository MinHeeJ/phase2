import { useCallback, useEffect, useState } from "react";
import {
  Badge,
  Button,
  Modal,
  Pagination,
  Skeleton,
  SuccessToast,
} from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { getMonthClose, monthStatus } from "../shared/api";
import { seoulMonth } from "../shared/PeriodBar";
import { useSharedMobile } from "../shared/PlaceholderScreen";
import tables from "../developers/Developers.module.css";
import {
  cancelClosingAdjustment,
  createClosingAdjustment,
  listClosingAdjustments,
  type AdjustmentRow,
  type AdjustmentType,
  type AdjustmentInput,
} from "./client";

export const adjustmentLabels: Record<AdjustmentType, string> = {
  REVENUE: "매출",
  COST: "원가",
  SGA: "판관비",
  ETC: "기타",
};

export function AdjustmentPanel({
  month,
  onRefresh,
}: {
  month: string;
  onRefresh?: () => Promise<unknown>;
}) {
  return (
    <Can perm="UI-14.read">
      <AdjustmentContent key={month} month={month} onRefresh={onRefresh} />
    </Can>
  );
}
function AdjustmentContent({
  month,
  onRefresh,
}: {
  month: string;
  onRefresh?: () => Promise<unknown>;
}) {
  const currentMonth = seoulMonth();
  const mobile = useSharedMobile();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState<20 | 50 | 100>(20);
  const [allYear, setAllYear] = useState(false);
  const [dialog, setDialog] = useState<"create" | "cancel" | "detail" | null>(
    null,
  );
  const [selected, setSelected] = useState<AdjustmentRow | null>(null);
  const [message, setMessage] = useState("");
  const [reason, setReason] = useState("");
  const [validation, setValidation] = useState("");
  const read = useCallback(
    () =>
      listClosingAdjustments({
        year: Number(month.slice(0, 4)),
        month: allYear ? undefined : month,
        page,
        size,
      }),
    [month, allYear, page, size],
  );
  const list = useAdminRead(read);
  const readMonths = useCallback(
    () => getMonthClose("0001-01", currentMonth),
    [currentMonth],
  );
  const months = useAdminRead(readMonths);
  const refresh = async () => {
    await Promise.all([list.reload(), months.reload()]);
    await onRefresh?.();
  };
  const write = useAdminWrite(refresh);
  const writable =
    !mobile &&
    !!months.data &&
    !months.loading &&
    !months.error &&
    monthStatus(months.data, currentMonth) === "OPEN";
  const rows = list.loading || list.error ? [] : (list.data?.items ?? []);
  const closedMonths = (months.data ?? [])
    .filter((m) => m.status === "CLOSED" && m.month < currentMonth)
    .map((m) => m.month);
  const dismiss = () => {
    if (!write.pending) {
      setDialog(null);
      setReason("");
      setValidation("");
      write.clear();
    }
  };
  const open = (
    kind: "create" | "cancel" | "detail",
    row: AdjustmentRow | null = null,
  ) => {
    setSelected(row);
    setDialog(kind);
    setReason("");
    setValidation("");
    write.clear();
  };
  const success = (text: string) => {
    setDialog(null);
    setSelected(null);
    setReason("");
    setMessage(text);
  };
  const cancel = () => {
    if (!reason.trim() || reason.trim().length > 500) {
      setValidation("취소 사유를 1~500자로 입력하세요.");
      return;
    }
    if (!selected || !writable) return;
    setValidation("");
    void write.save(
      () => cancelClosingAdjustment(selected.id, reason.trim()),
      () => success("조정을 취소했습니다."),
    );
  };
  useEffect(() => {
    if (mobile) setDialog(null);
  }, [mobile]);
  return (
    <section
      role="tabpanel"
      aria-label="전월 조정"
      data-testid="closing-adjustments-panel"
      className={tables.card}
    >
      <div className={tables.toolbar}>
        <h2>전월 조정</h2>
        {writable && (
          <Can perm="UI-14.update">
            <Button
              data-testid="adjustment-create-button"
              variant="primary"
              onClick={() => open("create")}
            >
              조정 등록
            </Button>
          </Can>
        )}
      </div>
      <p className={tables.help}>
        마감한 원월은 변경하지 않습니다. 현재 OPEN 월에만 조정을 등록하며 취소된
        조정은 집계에서 제외됩니다.
      </p>
      {mobile && (
        <p className={tables.help}>
          조회 전용입니다. 등록·취소는 PC에서 이용해 주세요.
        </p>
      )}
      <div className={tables.search}>
        <label>
          <input
            data-testid="adjustment-year-filter"
            type="checkbox"
            checked={allYear}
            onChange={(e) => {
              setAllYear(e.target.checked);
              setPage(0);
            }}
          />
          선택 연도 전체
        </label>
        <Button
          data-testid="adjustment-refresh-button"
          onClick={() => void refresh().catch(() => {})}
        >
          조회
        </Button>
      </div>
      <ErrorBanner
        error={months.error}
        onRetry={() => void months.reload().catch(() => {})}
      />
      <ErrorBanner
        error={list.error}
        onRetry={() => void list.reload().catch(() => {})}
      />
      {list.loading && <Skeleton />}
      {!list.loading && !list.error && (
        <>
          <p>총 {list.data?.total ?? 0}건 · 단위: 원</p>
          <div className={tables.tableScroll}>
            <table className={tables.table}>
              <thead>
                <tr>
                  {[
                    "원월",
                    "반영월",
                    "유형",
                    "금액",
                    "사유",
                    "원본 링크",
                    "상태",
                    "작업",
                  ].map((label) => (
                    <th
                      key={label}
                      data-testid={`adjustment-header-${["원월", "반영월", "유형", "금액", "사유", "원본 링크", "상태", "작업"].indexOf(label)}`}
                    >
                      {label}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rows.length === 0 && (
                  <tr>
                    <td colSpan={8} className={tables.empty}>
                      조건에 맞는 결과가 없습니다
                    </td>
                  </tr>
                )}
                {rows.map((row) => (
                  <tr key={row.id} data-testid={`adjustment-row-${row.id}`}>
                    <td>{row.originalMonth}</td>
                    <td>{row.applyMonth}</td>
                    <td>{adjustmentLabels[row.type]}</td>
                    <td className={tables.amount}>
                      {row.amount.toLocaleString("ko-KR")}
                    </td>
                    <td>{row.reason}</td>
                    <td>
                      {row.originalAdjustmentId
                        ? `#${row.originalAdjustmentId}`
                        : "–"}
                    </td>
                    <td>
                      <Badge
                        tone={row.status === "CANCELLED" ? "locked" : "success"}
                      >
                        {row.status === "CANCELLED" ? "취소" : "유효"}
                      </Badge>
                    </td>
                    <td>
                      <Button
                        small
                        data-testid={`adjustment-detail-${row.id}`}
                        onClick={() => open("detail", row)}
                      >
                        상세
                      </Button>
                      {writable &&
                        row.status === "ACTIVE" &&
                        row.applyMonth === currentMonth && (
                          <Can perm="UI-14.update">
                            <Button
                              small
                              data-testid={`adjustment-cancel-${row.id}`}
                              onClick={() => open("cancel", row)}
                            >
                              취소
                            </Button>
                          </Can>
                        )}
                      {writable &&
                        row.status === "ACTIVE" &&
                        monthStatus(months.data ?? [], row.applyMonth) ===
                          "CLOSED" && (
                          <Can perm="UI-14.update">
                            <Button
                              small
                              data-testid={`adjustment-correction-${row.id}`}
                              onClick={() => open("create", row)}
                            >
                              정정 등록
                            </Button>
                          </Can>
                        )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination
            page={page + 1}
            size={size}
            total={list.data?.total ?? 0}
            onPage={(p) => setPage(p - 1)}
            onSize={(n) => {
              setSize(n);
              setPage(0);
            }}
          />
        </>
      )}
      {dialog === "create" && writable && (
        <Modal title="전월 조정 등록" onCancel={dismiss}>
          <div data-testid="adjustment-create-dialog">
            <AdjustmentForm
              currentMonth={currentMonth}
              closedMonths={closedMonths}
              original={selected}
              pending={write.pending}
              disabled={write.uncertain}
              errorField={write.error?.field ?? null}
              onCancel={dismiss}
              onSave={(input) =>
                void write.save(
                  () => createClosingAdjustment(input),
                  () => success("조정을 등록했습니다."),
                )
              }
            />
            <ErrorBanner
              error={write.error}
              onRetry={write.uncertain ? () => void write.recover() : undefined}
            />
          </div>
        </Modal>
      )}
      {dialog === "cancel" && selected && writable && (
        <Modal title="전월 조정 취소" onCancel={dismiss} confirmation>
          <div data-testid="adjustment-cancel-dialog">
            <p>
              {selected.originalMonth} → {selected.applyMonth} ·{" "}
              {adjustmentLabels[selected.type]} ·{" "}
              {selected.amount.toLocaleString("ko-KR")}원
            </p>
            <p>원본 내용과 생성 이력은 보존되며 상태만 취소로 변경됩니다.</p>
            <label>
              취소 사유
              <textarea
                data-testid="adjustment-cancel-reason"
                value={reason}
                maxLength={500}
                onChange={(e) => setReason(e.target.value)}
                aria-invalid={!!validation || write.error?.field === "reason"}
              />
            </label>
            {validation && <p role="alert">{validation}</p>}
            <ErrorBanner
              error={write.error}
              onRetry={write.uncertain ? () => void write.recover() : undefined}
            />
            <div className={tables.actions}>
              <Button
                data-testid="adjustment-cancel-dismiss"
                disabled={write.pending}
                onClick={dismiss}
              >
                닫기
              </Button>
              <Button
                data-testid="adjustment-cancel-confirm"
                variant="primary"
                pending={write.pending}
                disabled={write.uncertain}
                onClick={cancel}
              >
                조정 취소
              </Button>
            </div>
          </div>
        </Modal>
      )}
      {dialog === "detail" && selected && (
        <Modal title="전월 조정 상세" onCancel={dismiss}>
          <div data-testid="adjustment-detail-dialog">
            <dl className={tables.details}>
              <dt>원월</dt>
              <dd>{selected.originalMonth}</dd>
              <dt>반영월</dt>
              <dd>{selected.applyMonth}</dd>
              <dt>유형</dt>
              <dd>{adjustmentLabels[selected.type]}</dd>
              <dt>금액</dt>
              <dd>{selected.amount.toLocaleString("ko-KR")}원</dd>
              <dt>사유</dt>
              <dd>{selected.reason}</dd>
              <dt>상태</dt>
              <dd>{selected.status === "ACTIVE" ? "유효" : "취소"}</dd>
              <dt>원본 링크</dt>
              <dd>
                {selected.originalAdjustmentId
                  ? `#${selected.originalAdjustmentId}`
                  : "–"}
              </dd>
              <dt>생성 일시</dt>
              <dd>{selected.createdAt ?? "–"}</dd>
              <dt>취소 일시</dt>
              <dd>{selected.cancelledAt ?? "–"}</dd>
            </dl>
            <Button data-testid="adjustment-detail-dismiss" onClick={dismiss}>
              닫기
            </Button>
          </div>
        </Modal>
      )}
      {message && (
        <SuccessToast message={message} onDismiss={() => setMessage("")} />
      )}
    </section>
  );
}

function AdjustmentForm({
  currentMonth,
  closedMonths,
  original,
  pending,
  disabled,
  errorField,
  onSave,
  onCancel,
}: {
  currentMonth: string;
  closedMonths: string[];
  original: AdjustmentRow | null;
  pending: boolean;
  disabled: boolean;
  errorField: string | null;
  onSave: (input: AdjustmentInput) => void;
  onCancel: () => void;
}) {
  const [originalMonth, setOriginalMonth] = useState(
    original?.originalMonth ?? "",
  );
  const [type, setType] = useState<AdjustmentType>(original?.type ?? "REVENUE");
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState("");
  const [projectId, setProjectId] = useState("");
  const [developerId, setDeveloperId] = useState("");
  const [validation, setValidation] = useState("");
  const submit = () => {
    if (!closedMonths.includes(originalMonth)) {
      setValidation("과거 CLOSED 원월을 선택하세요.");
      return;
    }
    if (
      !/^-?\d+$/.test(amount) ||
      !Number.isSafeInteger(Number(amount)) ||
      Number(amount) === 0
    ) {
      setValidation("금액은 0이 아닌 정수로 입력하세요.");
      return;
    }
    if (!reason.trim() || reason.trim().length > 500) {
      setValidation("사유를 1~500자로 입력하세요.");
      return;
    }
    if (
      [projectId, developerId].some(
        (id) =>
          id &&
          (!/^\d+$/.test(id) ||
            !Number.isSafeInteger(Number(id)) ||
            Number(id) <= 0),
      )
    ) {
      setValidation("연결 ID는 양의 정수로 입력하세요.");
      return;
    }
    setValidation("");
    onSave({
      originalMonth,
      applyMonth: currentMonth,
      type,
      amount: Number(amount),
      reason: reason.trim(),
      ...(projectId ? { projectId: Number(projectId) } : {}),
      ...(developerId ? { developerId: Number(developerId) } : {}),
      ...(original ? { originalAdjustmentId: original.id } : {}),
    });
  };
  return (
    <form
      data-testid="adjustment-create-form"
      className={tables.form}
      onSubmit={(e) => {
        e.preventDefault();
        if (!pending && !disabled) submit();
      }}
    >
      {original && (
        <p>
          원본 #{original.id}는 변경하지 않습니다. 역방향 금액과 정상 금액을
          각각 별도 등록하세요.
        </p>
      )}
      <label>
        원월
        <select
          data-testid="adjustment-original-month"
          value={originalMonth}
          onChange={(e) => setOriginalMonth(e.target.value)}
          aria-invalid={errorField === "originalMonth"}
        >
          <option value="">마감 월 선택</option>
          {closedMonths.map((m) => (
            <option
              key={m}
              data-testid={`adjustment-original-month-option-${m}`}
              value={m}
            >
              {m}
            </option>
          ))}
        </select>
      </label>
      <label>
        반영월
        <input
          data-testid="adjustment-apply-month"
          value={currentMonth}
          readOnly
        />
      </label>
      <label>
        유형
        <select
          data-testid="adjustment-type"
          value={type}
          onChange={(e) => setType(e.target.value as AdjustmentType)}
        >
          {Object.entries(adjustmentLabels).map(([key, label]) => (
            <option
              key={key}
              data-testid={`adjustment-type-option-${key.toLowerCase()}`}
              value={key}
            >
              {label}
            </option>
          ))}
        </select>
      </label>
      <label>
        금액
        <input
          data-testid="adjustment-amount"
          inputMode="numeric"
          value={amount}
          onChange={(e) => setAmount(e.target.value)}
          aria-invalid={errorField === "amount"}
        />
      </label>
      <label>
        사유
        <textarea
          data-testid="adjustment-reason"
          maxLength={500}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          aria-invalid={errorField === "reason"}
        />
      </label>
      <label>
        프로젝트 ID (선택)
        <input
          data-testid="adjustment-project-id"
          inputMode="numeric"
          value={projectId}
          onChange={(e) => setProjectId(e.target.value)}
          aria-invalid={errorField === "projectId"}
        />
      </label>
      <label>
        개발자 ID (선택)
        <input
          data-testid="adjustment-developer-id"
          inputMode="numeric"
          value={developerId}
          onChange={(e) => setDeveloperId(e.target.value)}
          aria-invalid={errorField === "developerId"}
        />
      </label>
      {validation && <p role="alert">{validation}</p>}
      <div className={tables.actions}>
        <Button
          type="button"
          data-testid="adjustment-create-dismiss"
          disabled={pending}
          onClick={onCancel}
        >
          닫기
        </Button>
        <Button
          type="submit"
          data-testid="adjustment-save"
          variant="primary"
          pending={pending}
          disabled={disabled}
        >
          등록
        </Button>
      </div>
    </form>
  );
}
