import { useCallback, useEffect, useRef, useState } from "react";
import { Badge, Button, Skeleton } from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import { useCodes } from "../shared/useCodes";
import {
  getProjectStaffPlans,
  saveProjectStaffPlans,
  type ProjectStaffPlan,
  type ProjectStaffPlanInput,
} from "./client";
import styles from "./StaffPlans.module.css";

interface Props {
  projectId: number;
  useYn: "Y" | "N";
  performanceStart: string;
  performanceEnd: string;
  onChanged: () => Promise<unknown>;
  onDirtyChange?: (dirty: boolean) => void;
  onBasicInfo?: () => void;
}
interface Draft {
  job: string;
  grade: string;
  cost: string;
  sourced: boolean;
  cells: Record<string, string>;
}
const units = (value: number) => Math.round(value * 100);
const mmText = (value: number) => (value / 100).toFixed(2);
function drafts(items: ProjectStaffPlan[]): Draft[] {
  const result = new Map<string, Draft>();
  items.forEach((item) => {
    const key = JSON.stringify([
      item.job_cd,
      item.grd_cd,
      item.expc_mth_cst_amt,
      item.src_need_id ?? null,
    ]);
    if (!result.has(key))
      result.set(key, {
        job: item.job_cd,
        grade: item.grd_cd,
        cost:
          item.expc_mth_cst_amt == null ? "" : String(item.expc_mth_cst_amt),
        sourced: item.src_need_id != null,
        cells: {},
      });
    result.get(key)!.cells[item.plan_ym] = item.need_mm_qty.toFixed(2);
  });
  return [...result.values()];
}
function monthsBetween(start: string, end: string) {
  const months: string[] = [];
  if (!/^\d{4}-\d{2}/.test(start) || !/^\d{4}-\d{2}/.test(end)) return months;
  let year = Number(start.slice(0, 4)),
    month = Number(start.slice(5, 7));
  const last = end.slice(0, 7);
  while (month >= 1 && month <= 12) {
    const value = `${String(year).padStart(4, "0")}-${String(month).padStart(2, "0")}`;
    if (value > last) break;
    months.push(value);
    month++;
    if (month > 12) {
      year++;
      month = 1;
    }
  }
  return months;
}

// SCR-UI-01: 인원 계획 tab in the selected project drawer.
export function ProjectStaffPlanPanel(props: Props) {
  return <StaffPlanEditor key={props.projectId} {...props} />;
}
function StaffPlanEditor({
  projectId,
  useYn,
  performanceStart,
  performanceEnd,
  onChanged,
  onDirtyChange,
  onBasicInfo,
}: Props) {
  const read = useCallback(() => getProjectStaffPlans(projectId), [projectId]);
  const state = useAdminRead(read);
  const jobs = useCodes("JOB"),
    grades = useCodes("GRADE");
  const mobile = useDeveloperReadOnly();
  const readOnly = mobile || useYn === "N";
  const [editing, setEditing] = useState(false);
  const [rows, setRows] = useState<Draft[]>([]);
  const [dirty, setDirty] = useState(false);
  const [message, setMessage] = useState("");
  const [localError, setLocalError] = useState<{
    field: string;
    message: string;
  } | null>(null);
  const form = useRef<HTMLFormElement>(null);
  const refresh = async () => {
    await state.reload();
    await onChanged();
  };
  const write = useAdminWrite(refresh);
  const error = localError ?? write.error;
  const reset = useCallback(() => {
    setRows(drafts(state.data?.items ?? []));
    setDirty(false);
    setLocalError(null);
  }, [state.data]);
  useEffect(reset, [reset]);
  useEffect(() => {
    onDirtyChange?.(dirty);
  }, [dirty, onDirtyChange]);
  useEffect(() => () => onDirtyChange?.(false), [onDirtyChange]);
  useEffect(() => {
    if (readOnly) {
      reset();
      setEditing(false);
    }
  }, [readOnly, reset]);
  useEffect(() => {
    if (error?.field)
      Array.from(form.current?.querySelectorAll<HTMLElement>("[name]") ?? [])
        .find((element) => element.getAttribute("name") === error.field)
        ?.focus();
  }, [error]);
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
  const update = (index: number, patch: Partial<Draft>) =>
    change(rows.map((row, i) => (i === index ? { ...row, ...patch } : row)));
  const period = monthsBetween(performanceStart, performanceEnd);
  const months = [
    ...new Set([
      ...period,
      ...(state.data?.items.map((row) => row.plan_ym) ?? []),
    ]),
  ].sort();
  const outside = months.filter((month) => !period.includes(month));
  const locked = readOnly || write.pending || write.uncertain || state.loading;
  const summary = new Map<string, Map<string, number>>();
  state.data?.items.forEach((row) => {
    if (!summary.has(row.job_cd)) summary.set(row.job_cd, new Map());
    const group = summary.get(row.job_cd)!;
    group.set(
      row.grd_cd,
      (group.get(row.grd_cd) ?? 0) + units(row.need_mm_qty),
    );
  });
  state.data?.actual.forEach((row) => {
    if (!summary.has(row.job_cd)) summary.set(row.job_cd, new Map([["", 0]]));
  });
  const name = (group: "JOB" | "GRADE", code: string) =>
    (group === "JOB" ? jobs.codes : grades.codes).find(
      (item) => item.code === code,
    )?.name ??
    (code || "–");
  const fieldError = (field: string) =>
    error?.field === field ? error.message : null;
  const invalid = (field: string, text: string) => {
    setLocalError({ field, message: text });
  };
  const submit = () => {
    if (locked) return;
    const items: ProjectStaffPlanInput[] = [];
    const keys = new Set<string>();
    for (let i = 0; i < rows.length; i++) {
      const row = rows[i];
      if (row.sourced) continue;
      if (!row.job) {
        invalid(`rows[${i}].job`, "직무를 골라 주세요.");
        return;
      }
      if (
        row.cost &&
        (!/^-?\d+$/.test(row.cost) || !Number.isSafeInteger(Number(row.cost)))
      ) {
        invalid(`rows[${i}].cost`, "예상 월 원가는 정수로 입력하세요.");
        return;
      }
      for (const month of months) {
        const value = row.cells[month] ?? "";
        const field = `rows[${i}].${month}`;
        if (!value.trim()) continue;
        if (!/^\d+(\.\d{1,2})?$/.test(value) || Number(value) > 99.99) {
          invalid(field, "필요 MM은 0~99.99, 소수 둘째 자리까지 입력하세요.");
          return;
        }
        if (Number(value) === 0) continue;
        const key = JSON.stringify([row.job, row.grade, month]);
        if (
          keys.has(key) ||
          state.data?.items.some(
            (item) =>
              item.src_need_id != null &&
              item.job_cd === row.job &&
              item.grd_cd === row.grade &&
              item.plan_ym === month,
          )
        ) {
          invalid(field, "중복되거나 원천 연결 계획과 겹치는 칸입니다.");
          return;
        }
        keys.add(key);
        const old = state.data?.items.find(
          (item) =>
            item.src_need_id == null &&
            item.job_cd === row.job &&
            item.grd_cd === row.grade &&
            item.plan_ym === month,
        );
        const cost = row.cost === "" ? null : Number(row.cost);
        if (
          outside.includes(month) &&
          (!old ||
            old.need_mm_qty !== Number(value) ||
            (old.expc_mth_cst_amt ?? null) !== cost)
        ) {
          invalid(
            field,
            "수행 기간 밖의 기존 값은 유지하거나 지울 수만 있습니다.",
          );
          return;
        }
        items.push({
          plan_ym: month,
          job_cd: row.job,
          grd_cd: row.grade,
          need_mm_qty: Number(value),
          expc_mth_cst_amt: cost,
        });
      }
    }
    void write.save(
      () => saveProjectStaffPlans(projectId, items),
      () => {
        setEditing(false);
        setDirty(false);
        setMessage("인원 계획을 저장했습니다.");
      },
    );
  };
  const serverField = (field: string) => {
    if (!error?.field?.startsWith("items[")) return null;
    const match = /^items\[(\d+)\]\.(.+)$/.exec(error.field);
    if (!match) return null;
    const cells = rows.flatMap((row, index) =>
      row.sourced
        ? []
        : months
            .filter((month) => Number(row.cells[month] ?? 0) > 0)
            .map((month) => ({ index, month })),
    );
    const target = cells[Number(match[1])];
    if (!target) return null;
    const key =
      match[2] === "job_cd"
        ? "job"
        : match[2] === "grd_cd"
          ? "grade"
          : match[2] === "expc_mth_cst_amt"
            ? "cost"
            : target.month;
    return field === `rows[${target.index}].${key}` ? error.message : null;
  };
  useEffect(() => {
    if (!error?.field?.startsWith("items[")) return;
    Array.from(form.current?.querySelectorAll<HTMLElement>("[name]") ?? [])
      .find((element) => serverField(element.getAttribute("name") ?? ""))
      ?.focus();
  });
  return (
    <section
      role="tabpanel"
      aria-label="인원 계획"
      data-screen-id="SCR-UI-01"
      data-testid="staff-plans-panel"
    >
      <div className={styles.toolbar}>
        <h3>인원 계획</h3>
        {!readOnly && !editing && state.data && (
          <Can perm="UI-01.update">
            <Button
              data-testid="staff-plan-edit"
              disabled={locked}
              onClick={() => {
                reset();
                setEditing(true);
                setMessage("");
              }}
            >
              계획 입력
            </Button>
          </Can>
        )}
      </div>
      {useYn === "N" && (
        <p className={styles.locked}>
          사용 안 함 프로젝트는 조회만 가능합니다.
        </p>
      )}
      {outside.length > 0 && (
        <p className={styles.warning}>
          수행 기간 밖의 계획이 있습니다: {outside.join(", ")}. 합계에
          포함됩니다.
        </p>
      )}
      {message && (
        <p role="status" data-testid="staff-plan-success">
          {message}
        </p>
      )}
      <ErrorBanner
        error={state.error}
        onRetry={() => void state.reload().catch(() => {})}
      />
      <ErrorBanner error={jobs.error} onRetry={jobs.retry} />
      <ErrorBanner error={grades.error} onRetry={grades.retry} />
      <ErrorBanner
        error={write.error}
        pending={write.pending}
        onRetry={() => void write.recover()}
      />
      {localError && (
        <p role="alert" className={styles.error}>
          {localError.message}
        </p>
      )}
      {state.loading ? (
        <Skeleton />
      ) : (
        state.data &&
        (!editing ? (
          <>
            <div className={styles.tableScroll}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    {["직무", "등급", "계획 MM", "실제 MM", "차이"].map(
                      (label) => (
                        <th key={label}>{label}</th>
                      ),
                    )}
                  </tr>
                </thead>
                <tbody>
                  {[...summary].flatMap(([job, gradeRows]) => {
                    const actual = units(
                      state.data!.actual.find((row) => row.job_cd === job)
                        ?.mm_qty ?? 0,
                    );
                    const planned = [...gradeRows.values()].reduce(
                      (sum, value) => sum + value,
                      0,
                    );
                    const diff = actual - planned;
                    return [...gradeRows].map(([grade, quantity], i) => (
                      <tr
                        key={`${job}:${grade}`}
                        data-testid={`staff-summary-${job}-${grade || "none"}`}
                      >
                        <td>{name("JOB", job)}</td>
                        <td>{name("GRADE", grade)}</td>
                        <td className={styles.number}>{mmText(quantity)}</td>
                        <td className={styles.number}>
                          {i === 0 ? mmText(actual) : "–"}
                        </td>
                        <td>
                          {i === 0 ? (
                            <Badge
                              tone={
                                diff < 0
                                  ? "danger"
                                  : diff > 0
                                    ? "warning"
                                    : "success"
                              }
                            >
                              {diff === 0
                                ? "맞음"
                                : `${diff < 0 ? "부족" : "초과"} ${mmText(Math.abs(diff))}`}
                            </Badge>
                          ) : (
                            "–"
                          )}
                        </td>
                      </tr>
                    ));
                  })}
                </tbody>
              </table>
            </div>
            {state.data.items.length === 0 && (
              <p className={styles.empty}>인원 계획이 없습니다.</p>
            )}
          </>
        ) : (
          <form
            ref={form}
            noValidate
            onSubmit={(event) => {
              event.preventDefault();
              submit();
            }}
          >
            <div className={styles.toolbar}>
              <h4>월별 계획 입력</h4>
              <Can perm="UI-01.update">
                <Button
                  type="button"
                  data-testid="staff-plan-add"
                  disabled={locked || jobs.loading || !!jobs.error}
                  onClick={() =>
                    change([
                      ...rows,
                      {
                        job: "",
                        grade: "",
                        cost: "",
                        cells: {},
                        sourced: false,
                      },
                    ])
                  }
                >
                  + 직무 줄
                </Button>
              </Can>
            </div>
            <div className={styles.tableScroll}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    <th>직무 *</th>
                    <th>등급</th>
                    <th>예상 월 원가</th>
                    {months.map((month) => (
                      <th key={month}>
                        {month}
                        {outside.includes(month) ? " (기간 밖)" : ""}
                      </th>
                    ))}
                    <th>작업</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row, index) => {
                    const disabled = locked || row.sourced;
                    const field = (key: string) => `rows[${index}].${key}`;
                    const feedback = (key: string) => {
                      const text =
                        fieldError(field(key)) ?? serverField(field(key));
                      return text ? (
                        <span className={styles.error}>{text}</span>
                      ) : null;
                    };
                    return (
                      <tr key={index} data-testid={`staff-plan-row-${index}`}>
                        <td>
                          <select
                            aria-label={`${index + 1}행 직무`}
                            name={field("job")}
                            data-testid={`staff-plan-job-${index}`}
                            className={styles.input}
                            value={row.job}
                            disabled={disabled || jobs.loading}
                            onChange={(event) =>
                              update(index, { job: event.target.value })
                            }
                          >
                            <option value="">선택</option>
                            {row.job &&
                              !jobs.codes.some(
                                (code) => code.code === row.job,
                              ) && (
                                <option value={row.job}>
                                  {row.job} (기존)
                                </option>
                              )}
                            {jobs.codes.map((code) => (
                              <option key={code.code} value={code.code}>
                                {code.name}
                              </option>
                            ))}
                          </select>
                          {feedback("job")}
                        </td>
                        <td>
                          <select
                            aria-label={`${index + 1}행 등급`}
                            name={field("grade")}
                            data-testid={`staff-plan-grade-${index}`}
                            className={styles.input}
                            value={row.grade}
                            disabled={disabled || grades.loading}
                            onChange={(event) =>
                              update(index, { grade: event.target.value })
                            }
                          >
                            <option value="">미지정</option>
                            {row.grade &&
                              !grades.codes.some(
                                (code) => code.code === row.grade,
                              ) && (
                                <option value={row.grade}>
                                  {row.grade} (기존)
                                </option>
                              )}
                            {grades.codes.map((code) => (
                              <option key={code.code} value={code.code}>
                                {code.name}
                              </option>
                            ))}
                          </select>
                          {feedback("grade")}
                        </td>
                        <td>
                          <input
                            aria-label={`${index + 1}행 예상 월 원가`}
                            name={field("cost")}
                            data-testid={`staff-plan-cost-${index}`}
                            className={styles.input}
                            value={row.cost}
                            readOnly={disabled}
                            inputMode="numeric"
                            onChange={(event) =>
                              update(index, { cost: event.target.value })
                            }
                          />
                          {feedback("cost")}
                        </td>
                        {months.map((month) => (
                          <td key={month}>
                            <input
                              aria-label={`${index + 1}행 ${month} 필요 MM`}
                              name={field(month)}
                              data-testid={`staff-plan-mm-${index}-${month}`}
                              className={styles.input}
                              value={row.cells[month] ?? ""}
                              readOnly={disabled}
                              inputMode="decimal"
                              onChange={(event) =>
                                update(index, {
                                  cells: {
                                    ...row.cells,
                                    [month]: event.target.value,
                                  },
                                })
                              }
                            />
                            {feedback(month)}
                          </td>
                        ))}
                        <td>
                          {row.sourced ? (
                            <Badge tone="locked">원천 연결</Badge>
                          ) : (
                            <Button
                              type="button"
                              small
                              data-testid={`staff-plan-remove-${index}`}
                              disabled={locked}
                              onClick={() =>
                                change(rows.filter((_, i) => i !== index))
                              }
                            >
                              삭제
                            </Button>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            {rows.length === 0 && (
              <p className={styles.empty}>인원 계획이 없습니다.</p>
            )}
            <Can perm="UI-01.update">
              <div className={styles.actions}>
                <Button
                  type="button"
                  data-testid="staff-plan-cancel"
                  disabled={write.pending}
                  onClick={() => {
                    reset();
                    setEditing(false);
                    write.clear();
                  }}
                >
                  취소
                </Button>
                <Button
                  type="submit"
                  variant="primary"
                  data-testid="staff-plan-save"
                  pending={write.pending}
                  disabled={
                    locked ||
                    jobs.loading ||
                    grades.loading ||
                    !!jobs.error ||
                    !!grades.error
                  }
                >
                  저장
                </Button>
              </div>
            </Can>
          </form>
        ))
      )}
      <p className={styles.hint}>
        목표 원가·목표 마진율은 기본 정보의 수정에서 변경합니다.
        {onBasicInfo && (
          <Button
            type="button"
            variant="text"
            data-testid="staff-plan-basic-info"
            onClick={onBasicInfo}
          >
            기본 정보
          </Button>
        )}
      </p>
    </section>
  );
}
