import { useCallback, useEffect, useRef, useState } from "react";
import {
  Badge,
  Button,
  LockedBanner,
  Skeleton,
  SuccessToast,
} from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import * as client from "./client";
import styles from "./Months.module.css";

export interface UI12MonthsPanelProps {
  assignmentId: number;
  readOnly?: boolean;
  assignmentStatus?: string;
  projectStatus?: string;
  useYn?: string;
  onChanged: () => Promise<unknown>;
  onDirtyChange?: (dirty: boolean) => void;
}

export function UI12MonthsPanel({
  assignmentId,
  ...props
}: UI12MonthsPanelProps) {
  return (
    <MonthsEditor key={assignmentId} assignmentId={assignmentId} {...props} />
  );
}

function MonthsEditor({
  assignmentId,
  readOnly = false,
  assignmentStatus,
  projectStatus,
  useYn,
  onChanged,
  onDirtyChange,
}: UI12MonthsPanelProps) {
  const read = useCallback(
    () => client.getAssignmentMonths(assignmentId),
    [assignmentId],
  );
  const state = useAdminRead(read);
  const mobile = useDeveloperReadOnly();
  const locked =
    readOnly ||
    mobile ||
    assignmentStatus === "CANCELLED" ||
    useYn === "N" ||
    projectStatus === "END" ||
    projectStatus === "LOST";
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [message, setMessage] = useState("");
  const dirtyCallback = useRef(onDirtyChange);
  dirtyCallback.current = onDirtyChange;
  useEffect(() => {
    if (state.data) {
      setDraft({});
      setFieldErrors({});
      dirtyCallback.current?.(false);
    }
  }, [state.data]);
  useEffect(() => () => dirtyCallback.current?.(false), []);
  const refresh = async () => {
    await state.reload();
    await onChanged();
  };
  const write = useAdminWrite(refresh);
  const blocked = locked || write.pending || write.uncertain || state.loading;
  const change = (month: string, value: string) => {
    setDraft((old) => ({ ...old, [month]: value }));
    setFieldErrors((old) => ({ ...old, [month]: "" }));
    setMessage("");
    write.clear();
    onDirtyChange?.(true);
  };
  const save = () => {
    if (blocked || !state.data) return;
    const errors: Record<string, string> = {};
    const months: client.AssignmentMonthsRequest["months"] = [];
    for (const row of state.data.items) {
      if (row.closed_yn === "Y" || draft[row.work_ym] === undefined) continue;
      const value = draft[row.work_ym];
      if (
        !/^\d+(\.\d+)?$/.test(value) ||
        !Number.isFinite(Number(value)) ||
        Number(value) > 1
      ) {
        errors[row.work_ym] = "MM을 0~1.00 사이 숫자로 입력하세요.";
      } else if (Number(value) !== row.mm_qty) {
        months.push({ work_ym: row.work_ym, mm_qty: Number(value) });
      }
    }
    setFieldErrors(errors);
    if (Object.keys(errors).length || !months.length) return;
    const updated_at = state.data.updated_at;
    void write.save(
      () => client.saveAssignmentMonths(assignmentId, { updated_at, months }),
      () => setMessage("월별 MM을 저장했습니다."),
    );
  };
  const dirty = state.data?.items.some(
    (r) =>
      draft[r.work_ym] !== undefined &&
      draft[r.work_ym] !== r.mm_qty.toFixed(2),
  );
  return (
    <section
      data-testid="assignment-months-panel"
      aria-label="월별 투입 상세"
      className={styles.panel}
    >
      <h3>월별 투입 상세</h3>
      {locked && <LockedBanner>읽기 전용입니다.</LockedBanner>}
      {message && (
        <SuccessToast message={message} onDismiss={() => setMessage("")} />
      )}
      <ErrorBanner
        error={state.error}
        onRetry={() => void state.reload().catch(() => {})}
      />
      <ErrorBanner
        error={write.error}
        pending={write.pending}
        onRetry={() => void write.recover()}
      />
      {state.loading ? (
        <Skeleton />
      ) : (
        !state.error &&
        state.data && (
          <>
            {state.data.warnings.map((warning, index) => (
              <div
                key={`${warning.code}-${index}`}
                className={styles.warning}
                data-testid={`assignment-month-warning-${index}`}
              >
                {warning.work_ym && `${warning.work_ym} · `}
                {warning.message}
              </div>
            ))}
            <div className={styles.tableScroll}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    {[
                      "월",
                      "자동 MM",
                      "MM 입력",
                      "수정함",
                      "그 사람 그 달 합계",
                      "동작",
                    ].map((label) => (
                      <th
                        data-testid={`assignment-months-header-${["월", "자동 MM", "MM 입력", "수정함", "그 사람 그 달 합계", "동작"].indexOf(label)}`}
                        key={label}
                      >
                        {label}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {!state.data.items.length && (
                    <tr>
                      <td colSpan={6} className={styles.empty}>
                        월별 투입 정보가 없습니다.
                      </td>
                    </tr>
                  )}
                  {state.data.items.map((row) => {
                    const value = draft[row.work_ym] ?? row.mm_qty.toFixed(2);
                    const closed = row.closed_yn === "Y";
                    const manual =
                      draft[row.work_ym] === undefined
                        ? row.mnl_yn === "Y"
                        : Number(value) !== row.auto_mm_qty;
                    const error =
                      fieldErrors[row.work_ym] ||
                      (write.error?.field === "mm_qty" &&
                      draft[row.work_ym] !== undefined
                        ? write.error.message
                        : "");
                    return (
                      <tr
                        key={row.work_ym}
                        data-testid={`assignment-month-row-${row.work_ym}`}
                      >
                        <td>
                          {row.work_ym}{" "}
                          {closed && <Badge tone="locked">🔒 마감</Badge>}
                        </td>
                        <td>{row.auto_mm_qty.toFixed(2)}</td>
                        <td>
                          <input
                            aria-label={`${row.work_ym} MM`}
                            inputMode="decimal"
                            data-testid={`assignment-month-input-${row.work_ym}`}
                            className={styles.input}
                            value={value}
                            readOnly={locked || closed}
                            disabled={write.pending || write.uncertain}
                            aria-invalid={!!error}
                            aria-describedby={
                              error ? `months-error-${row.work_ym}` : undefined
                            }
                            onChange={(event) =>
                              change(row.work_ym, event.target.value)
                            }
                          />
                          {error && (
                            <small
                              role="alert"
                              id={`months-error-${row.work_ym}`}
                              className={styles.error}
                            >
                              {error}
                            </small>
                          )}
                        </td>
                        <td>{manual && <Badge tone="info">수정함</Badge>}</td>
                        <td
                          className={
                            row.person_month_mm_qty > 1
                              ? styles.over
                              : undefined
                          }
                          data-testid={`assignment-month-total-${row.work_ym}`}
                        >
                          {row.person_month_mm_qty.toFixed(2)}
                        </td>
                        <td>
                          {!locked && !closed && (
                            <Can perm="UI-12.update">
                              <Button
                                small
                                data-testid={`assignment-month-auto-${row.work_ym}`}
                                disabled={blocked}
                                onClick={() =>
                                  change(
                                    row.work_ym,
                                    row.auto_mm_qty.toFixed(2),
                                  )
                                }
                              >
                                자동값으로
                              </Button>
                            </Can>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            {!locked && state.data.items.length > 0 && (
              <Can perm="UI-12.update">
                <div className={styles.actions}>
                  <Button
                    variant="primary"
                    data-testid="assignment-months-save"
                    pending={write.pending}
                    disabled={blocked || !dirty}
                    onClick={save}
                  >
                    월별 저장
                  </Button>
                </div>
              </Can>
            )}
          </>
        )
      )}
    </section>
  );
}
