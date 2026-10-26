import { useCallback, useEffect, useRef, useState } from "react";
import { Badge, Button, Skeleton } from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { formatMoney, isDate } from "../../lib/format";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import {
  listProjectBillStages,
  saveProjectBillStages,
  type ProjectBillStage,
} from "./client";
import styles from "./ProjectBillStages.module.css";

type Draft = Omit<ProjectBillStage, "bill_stg_id" | "sply_amt"> & {
  bill_stg_id?: number;
  amount: string;
};
interface Props {
  projectId: number;
  contractType: string;
  useYn: "Y" | "N";
  onChanged: () => Promise<unknown>;
  onDirtyChange?: (dirty: boolean) => void;
}

// SCR-UI-01: 청구 단계 tab in the selected project's detail drawer, not a separate route.
export function ProjectBillStagePanel(props: Props) {
  if (props.contractType !== "TK") return null;
  return <BillStageEditor key={props.projectId} {...props} />;
}

function BillStageEditor({
  projectId,
  useYn,
  onChanged,
  onDirtyChange,
}: Props) {
  const read = useCallback(() => listProjectBillStages(projectId), [projectId]);
  const state = useAdminRead(read);
  const [rows, setRows] = useState<Draft[]>([]);
  const [dirty, setDirty] = useState(false);
  const [message, setMessage] = useState("");
  const [localError, setLocalError] = useState<{
    field: string;
    message: string;
  } | null>(null);
  const mobile = useDeveloperReadOnly();
  const readOnly = mobile || useYn === "N";
  const form = useRef<HTMLFormElement>(null);
  const restore = useCallback(() => {
    setRows(
      (state.data?.items ?? []).map(({ sply_amt, ...row }) => ({
        ...row,
        amount: String(sply_amt),
      })),
    );
    setDirty(false);
    setLocalError(null);
  }, [state.data]);
  useEffect(restore, [restore]);
  useEffect(() => {
    onDirtyChange?.(dirty);
  }, [dirty, onDirtyChange]);
  useEffect(
    () => () => {
      onDirtyChange?.(false);
    },
    [onDirtyChange],
  );
  const refresh = async () => {
    await state.reload();
    await onChanged();
  };
  const write = useAdminWrite(refresh);
  const error = localError ?? write.error;
  useEffect(() => {
    if (error?.field) {
      Array.from(
        form.current?.querySelectorAll<HTMLInputElement>("input") ?? [],
      )
        .find((input) => input.name === error.field)
        ?.focus();
    }
  }, [error]);
  useEffect(() => {
    if (readOnly) restore();
  }, [readOnly, restore]);
  useEffect(() => {
    const leave = (event: BeforeUnloadEvent) => {
      if (dirty) {
        event.preventDefault();
        event.returnValue = "";
      }
    };
    window.addEventListener("beforeunload", leave);
    return () => window.removeEventListener("beforeunload", leave);
  }, [dirty]);
  const change = (next: Draft[]) => {
    setRows(next);
    setDirty(true);
    setMessage("");
    setLocalError(null);
    write.clear();
  };
  const locked = readOnly || write.pending || write.uncertain || state.loading;
  const sum = rows.reduce(
    (total, row) =>
      total + (/^\d+$/.test(row.amount) ? BigInt(row.amount) : 0n),
    0n,
  );
  const current = state.data?.cur_cntrct_amt;
  const fieldError = (field: string) =>
    error?.field === field ? error.message : null;
  const submit = () => {
    if (locked) return;
    for (let index = 0; index < rows.length; index++) {
      const row = rows[index];
      let invalid: { field: string; message: string } | null = null;
      if (!row.bill_stg_nm.trim() || row.bill_stg_nm.length > 100)
        invalid = {
          field: `items[${index}].bill_stg_nm`,
          message: "단계명은 필수이며 100자 이하로 입력하세요.",
        };
      else if (
        !/^\d+$/.test(row.amount) ||
        !Number.isSafeInteger(Number(row.amount)) ||
        BigInt(row.amount) < 1n
      )
        invalid = {
          field: `items[${index}].sply_amt`,
          message: "공급가액은 1원 이상의 정수로 입력하세요.",
        };
      else if (!isDate(row.bill_plan_dt))
        invalid = {
          field: `items[${index}].bill_plan_dt`,
          message: "청구 예정일을 입력하세요.",
        };
      if (invalid) {
        setLocalError(invalid);
        return;
      }
    }
    void write.save(
      () =>
        saveProjectBillStages(
          projectId,
          rows.map((row) => ({
            ...(row.bill_stg_id === undefined
              ? {}
              : { bill_stg_id: row.bill_stg_id }),
            bill_stg_nm: row.bill_stg_nm,
            sply_amt: Number(row.amount),
            bill_plan_dt: row.bill_plan_dt,
          })),
        ),
      () => {
        setMessage("청구 단계를 저장했습니다.");
        setDirty(false);
      },
    );
  };
  return (
    <section
      role="tabpanel"
      aria-label="청구 단계"
      data-testid="bill-stages-panel"
      data-screen-id="SCR-UI-01"
    >
      <form
        ref={form}
        data-testid="bill-stages-form"
        noValidate
        onSubmit={(event) => {
          event.preventDefault();
          submit();
        }}
      >
        <div className={styles.toolbar}>
          <h3>청구 단계</h3>
          {!readOnly && state.data && (
            <Can perm="UI-01.update">
              <Button
                type="button"
                data-testid="bill-stage-add"
                disabled={locked || rows.length >= 20}
                onClick={() =>
                  change([
                    ...rows,
                    {
                      bill_stg_nm:
                        ["착수금", "중도금", "잔금"][rows.length] ?? "",
                      amount: "",
                      bill_plan_dt: "",
                      billed: false,
                      stg_odr_no: rows.length + 1,
                      vat_amt: 0,
                    },
                  ])
                }
              >
                + 단계 추가
              </Button>
            </Can>
          )}
        </div>
        {useYn === "N" && (
          <p className={styles.locked}>
            사용 안 함 프로젝트는 조회만 가능합니다.
          </p>
        )}
        {message && (
          <p role="status" data-testid="bill-stage-success">
            {message}
          </p>
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
        {error?.field === "items" && (
          <p role="alert" className={styles.error}>
            {error.message}
          </p>
        )}
        {state.loading ? (
          <Skeleton />
        ) : (
          state.data && (
            <>
              <div className={styles.tableScroll}>
                <table className={styles.table}>
                  <thead>
                    <tr>
                      {[
                        "차수",
                        "단계명",
                        "공급가액",
                        "부가세",
                        "청구 예정일",
                        "청구 여부",
                      ].map((label) => (
                        <th
                          key={label}
                          data-testid={`bill-stage-header-${["차수", "단계명", "공급가액", "부가세", "청구 예정일", "청구 여부"].indexOf(label)}`}
                        >
                          {label}
                        </th>
                      ))}
                      {!readOnly && <th>작업</th>}
                    </tr>
                  </thead>
                  <tbody>
                    {rows.length === 0 ? (
                      <tr>
                        <td colSpan={readOnly ? 6 : 7} className={styles.empty}>
                          등록된 청구 단계가 없습니다.
                        </td>
                      </tr>
                    ) : (
                      rows.map((row, index) => {
                        const prefix = `items[${index}].`;
                        const blocked = locked || row.billed;
                        const cell = (
                          key: "bill_stg_nm" | "sply_amt" | "bill_plan_dt",
                          label: string,
                        ) => {
                          const value =
                            key === "sply_amt" ? row.amount : row[key];
                          const field = prefix + key;
                          return (
                            <>
                              <input
                                aria-label={`${index + 1}차 ${label}`}
                                name={field}
                                data-testid={`bill-stage-${index}-${key.replaceAll("_", "-")}`}
                                type={key === "bill_plan_dt" ? "date" : "text"}
                                inputMode={
                                  key === "sply_amt" ? "numeric" : undefined
                                }
                                required
                                maxLength={
                                  key === "bill_stg_nm" ? 100 : undefined
                                }
                                value={value}
                                readOnly={blocked}
                                aria-invalid={!!fieldError(field)}
                                aria-describedby={
                                  fieldError(field)
                                    ? `bill-stage-error-${index}-${key}`
                                    : undefined
                                }
                                className={styles.input}
                                onChange={(event) =>
                                  change(
                                    rows.map((r, i) =>
                                      i === index
                                        ? {
                                            ...r,
                                            [key === "sply_amt"
                                              ? "amount"
                                              : key]: event.target.value,
                                          }
                                        : r,
                                    ),
                                  )
                                }
                              />
                              {fieldError(field) && (
                                <span
                                  id={`bill-stage-error-${index}-${key}`}
                                  role="alert"
                                  className={styles.error}
                                >
                                  {fieldError(field)}
                                </span>
                              )}
                            </>
                          );
                        };
                        const shiftsBilled = rows
                          .slice(index + 1)
                          .some((r) => r.billed);
                        return (
                          <tr
                            key={row.bill_stg_id ?? `new-${index}`}
                            data-testid={`bill-stage-row-${index}`}
                          >
                            <td>{index + 1}</td>
                            <td>{cell("bill_stg_nm", "단계명")}</td>
                            <td>{cell("sply_amt", "공급가액")}</td>
                            <td className={styles.number}>
                              {/^\d+$/.test(row.amount)
                                ? formatMoney(BigInt(row.amount) / 10n)
                                : "–"}
                            </td>
                            <td>{cell("bill_plan_dt", "청구 예정일")}</td>
                            <td>
                              <Badge tone={row.billed ? "locked" : "neutral"}>
                                {row.billed ? "청구됨" : "미청구"}
                              </Badge>
                            </td>
                            {!readOnly && (
                              <td>
                                <Can perm="UI-01.update">
                                  <Button
                                    type="button"
                                    small
                                    data-testid={`bill-stage-remove-${index}`}
                                    disabled={blocked || shiftsBilled}
                                    title={
                                      shiftsBilled
                                        ? "청구된 단계의 차수는 바꿀 수 없습니다."
                                        : undefined
                                    }
                                    onClick={() =>
                                      change(rows.filter((_, i) => i !== index))
                                    }
                                  >
                                    삭제
                                  </Button>
                                </Can>
                              </td>
                            )}
                          </tr>
                        );
                      })
                    )}
                  </tbody>
                </table>
              </div>
              <p className={styles.totals}>
                공급가액 합계 <strong>{formatMoney(sum)}원</strong> / 현재
                계약금액 <strong>{formatMoney(current)}원</strong>
              </p>
              {current !== undefined && sum !== BigInt(current) && (
                <p className={styles.warning} data-testid="bill-stage-mismatch">
                  청구 단계 합계({formatMoney(sum)})가 현재 계약금액(
                  {formatMoney(current)})과 다릅니다.
                </p>
              )}
              {!readOnly && (
                <Can perm="UI-01.update">
                  <div className={styles.actions}>
                    <Button
                      type="button"
                      data-testid="bill-stage-cancel"
                      disabled={write.pending}
                      onClick={() => {
                        restore();
                        write.clear();
                        setMessage("");
                      }}
                    >
                      취소
                    </Button>
                    <Button
                      type="submit"
                      variant="primary"
                      data-testid="bill-stage-save"
                      pending={write.pending}
                      disabled={locked}
                    >
                      저장
                    </Button>
                  </div>
                </Can>
              )}
            </>
          )
        )}
      </form>
    </section>
  );
}
