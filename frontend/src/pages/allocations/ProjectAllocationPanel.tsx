import { Fragment, useCallback, useEffect, useRef, useState } from "react";
import { Badge, Button, Modal, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { Can } from "../../shared/Can";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { useCodes } from "../shared/useCodes";
import { listDeveloperOptions } from "../developersops/client";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import {
  getProjectAllocations,
  saveProjectAllocations,
  type AllocationLine,
  type AllocationStage,
} from "./client";
import styles from "./Allocations.module.css";

type DraftMonth = { aloc_ym: string; aloc_amt: string; mm_qty: string };
type DraftLine = Omit<AllocationLine, "months"> & { months: DraftMonth[] };
const stages: { key: AllocationStage; label: string }[] = [
  { key: "EST", label: "견적" },
  { key: "FINAL", label: "최종 계약" },
  { key: "ACTUAL", label: "실제 투입계획" },
];
const draftOf = (items: AllocationLine[]): DraftLine[] =>
  items.map((l) => ({
    ...l,
    months: l.months.map((m) => ({
      aloc_ym: m.aloc_ym,
      aloc_amt: m.aloc_amt?.toString() ?? "",
      mm_qty: m.mm_qty?.toString() ?? "",
    })),
  }));
function performanceMonths(start: string, end: string) {
  const result: string[] = [];
  let [year, month] = start.slice(0, 7).split("-").map(Number);
  const last = end.slice(0, 7);
  if (!year || !month || !last) return result;
  while (year <= 9999) {
    const value = `${year.toString().padStart(4, "0")}-${month.toString().padStart(2, "0")}`;
    if (value > last) break;
    result.push(value);
    month++;
    if (month > 12) {
      month = 1;
      year++;
    }
  }
  return result;
}
const amountSum = (lines: DraftLine[], month?: string) =>
  lines
    .flatMap((l) => l.months)
    .filter((m) => !month || m.aloc_ym === month)
    .reduce(
      (sum, m) => sum + (/^-?\d+$/.test(m.aloc_amt) ? BigInt(m.aloc_amt) : 0n),
      0n,
    );
const mmSum = (lines: DraftLine[], month?: string) =>
  (
    lines
      .flatMap((l) => l.months)
      .filter((m) => !month || m.aloc_ym === month)
      .reduce((sum, m) => sum + Math.round(Number(m.mm_qty || 0) * 100), 0) /
    100
  ).toFixed(2);
const money = (v: bigint | number) => v.toLocaleString("ko-KR");

// SCR-UI-01: 배분표 tab inside the selected turnkey project's drawer.
export function ProjectAllocationPanel({
  projectId,
  performanceStart,
  performanceEnd,
  useYn = "Y",
  onChanged = async () => {},
  onDirtyChange,
}: {
  projectId: number;
  performanceStart: string;
  performanceEnd: string;
  useYn?: "Y" | "N";
  onChanged?: () => Promise<unknown>;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const [stage, setStage] = useState<AllocationStage>();
  const read = useCallback(async () => {
    const active = await getProjectAllocations(projectId);
    const selected =
      stage && active.aloc_stg_cd !== stage
        ? await getProjectAllocations(projectId, stage)
        : active;
    return { active, selected };
  }, [projectId, stage]);
  const state = useAdminRead(read);
  const developers = useAdminRead(
    useCallback(() => listDeveloperOptions(), []),
  );
  const jobs = useCodes("JOB"),
    kinds = useCodes("ASSIGN_KIND");
  const [lines, setLines] = useState<DraftLine[]>([]);
  const [dirty, setDirty] = useState(false),
    [message, setMessage] = useState("");
  const [nextStage, setNextStage] = useState<AllocationStage | null>(null);
  const [localError, setLocalError] = useState<{
    field: string;
    message: string;
    row: number;
    month?: string;
  } | null>(null);
  const root = useRef<HTMLElement>(null);
  const mobile = useDeveloperReadOnly();
  const readonly = mobile || useYn === "N";
  const write = useAdminWrite(async () => {
    await state.reload();
    await onChanged();
  });
  useEffect(() => {
    if (!state.data) return;
    setLines(draftOf(state.data.selected.items));
    setDirty(false);
    setLocalError(null);
  }, [state.data]);
  useEffect(() => {
    setStage(undefined);
    setMessage("");
    setNextStage(null);
  }, [projectId]);
  useEffect(() => {
    onDirtyChange?.(dirty);
  }, [dirty, onDirtyChange]);
  const error = localError ?? write.error;
  useEffect(() => {
    if (error?.field)
      root.current
        ?.querySelector<HTMLElement>(
          `[data-field="${error.field.replace(/[^a-z_]/g, "")}"]`,
        )
        ?.focus();
  }, [error]);
  const selected = state.data?.selected;
  const period = performanceMonths(performanceStart, performanceEnd);
  const months = [
    ...new Set([
      ...period,
      ...lines.flatMap((l) => l.months.map((m) => m.aloc_ym)),
    ]),
  ].sort();
  const outside = months.some((m) => !period.includes(m));
  const blocked = readonly || write.pending || write.uncertain || state.loading;
  const change = (index: number, values: Partial<DraftLine>) => {
    setLines((current) =>
      current.map((l, i) => (i === index ? { ...l, ...values } : l)),
    );
    setDirty(true);
    setLocalError(null);
    setMessage("");
    write.clear();
  };
  const cellChange = (
    index: number,
    ym: string,
    key: "aloc_amt" | "mm_qty",
    value: string,
  ) => {
    const cells = lines[index].months;
    const existing = cells.find((m) => m.aloc_ym === ym) ?? {
      aloc_ym: ym,
      aloc_amt: "",
      mm_qty: "",
    };
    change(index, {
      months: [
        ...cells.filter((m) => m.aloc_ym !== ym),
        { ...existing, [key]: value },
      ],
    });
  };
  const add = (type: "PERSON" | "ITEM") => {
    setLines((current) => [
      ...current,
      {
        line_ty_cd: type,
        dev_id: null,
        item_nm: null,
        job_cd: null,
        asgn_bgn_dt: null,
        asgn_se_cd: null,
        rmrk_desc: null,
        sort_seq: current.length,
        months: [],
      },
    ]);
    setDirty(true);
    setMessage("");
  };
  const save = () => {
    if (!selected || blocked) return;
    for (let row = 0; row < lines.length; row++) {
      const l = lines[row];
      const invalid = (field: string, message: string, month?: string) =>
        setLocalError({ field, message, row, month });
      if (l.line_ty_cd === "PERSON") {
        if (!l.dev_id) return invalid("dev_id", "개발자를 골라 주세요.");
        if (!l.job_cd) return invalid("job_cd", "직무를 골라 주세요.");
        if (!l.asgn_bgn_dt)
          return invalid("asgn_bgn_dt", "투입일을 입력해 주세요.");
      } else if (!l.item_nm?.trim())
        return invalid("item_nm", "항목명을 입력해 주세요.");
      for (const m of l.months) {
        if (
          m.aloc_amt &&
          (!/^-?\d+$/.test(m.aloc_amt) ||
            !Number.isSafeInteger(Number(m.aloc_amt)))
        )
          return invalid(
            "aloc_amt",
            "원 단위 정수 금액을 확인하세요.",
            m.aloc_ym,
          );
        if (
          m.mm_qty &&
          (!/^-?\d+(\.\d{1,2})?$/.test(m.mm_qty) ||
            Math.abs(Number(m.mm_qty)) >= 1000)
        )
          return invalid(
            "mm_qty",
            "MM은 소수 둘째 자리와 숫자 범위를 확인하세요.",
            m.aloc_ym,
          );
      }
    }
    const payload = lines.map((l) => ({
      ...l,
      months: l.months.map((m) => ({
        aloc_ym: m.aloc_ym,
        aloc_amt: m.aloc_amt === "" ? null : Number(m.aloc_amt),
        mm_qty: m.mm_qty === "" ? null : Number(m.mm_qty),
      })),
    }));
    void write.save(
      () => saveProjectAllocations(projectId, selected.aloc_stg_cd, payload),
      () => {
        setDirty(false);
        setMessage("배분표를 저장했습니다.");
      },
    );
  };
  const activeLines = state.data
    ? selected?.aloc_stg_cd === state.data.active.activeStage
      ? lines
      : draftOf(state.data.active.items)
    : [];
  const activeTotal = amountSum(activeLines);
  const mismatch =
    state.data?.active.activeStage &&
    activeTotal !== BigInt(state.data.active.cur_cntrct_amt);
  const fieldMessage = (row: number, field: string) =>
    error?.field === field && (!localError || localError.row === row) ? (
      <small className={styles.fieldError}>{error.message}</small>
    ) : null;
  return (
    <section
      ref={root}
      role="tabpanel"
      aria-label="배분표"
      data-testid="project-allocation-panel"
      data-screen-id="SCR-UI-01"
      className={styles.panel}
    >
      <h3>배분표</h3>
      {useYn === "N" && (
        <p className={styles.locked}>사용 안 함으로 바꾼 프로젝트입니다.</p>
      )}
      {state.data?.selected.realoc_need_yn === "Y" && (
        <p className={styles.warning}>
          계약이 바뀌었습니다. 배분표를 다시 맞춰 주세요.
        </p>
      )}
      {message && <p role="status">{message}</p>}
      <div role="tablist" aria-label="배분 단계" className={styles.tabs}>
        {stages.map((s) => (
          <button
            key={s.key}
            role="tab"
            data-testid={`allocation-stage-${s.key.toLowerCase()}`}
            aria-selected={selected?.aloc_stg_cd === s.key}
            disabled={write.pending || state.loading}
            onClick={() => {
              if (selected?.aloc_stg_cd === s.key) return;
              if (dirty) setNextStage(s.key);
              else {
                setStage(s.key);
                setMessage("");
              }
            }}
          >
            {s.label}{" "}
            {state.data?.active.activeStage === s.key && (
              <Badge tone="info">사용 중</Badge>
            )}
          </button>
        ))}
      </div>
      <ErrorBanner
        error={state.error}
        onRetry={() => void state.reload().catch(() => {})}
      />
      <ErrorBanner error={write.error} onRetry={() => void write.recover()} />
      <ErrorBanner
        error={developers.error}
        onRetry={() => void developers.reload().catch(() => {})}
      />
      <ErrorBanner error={jobs.error} onRetry={jobs.retry} />
      <ErrorBanner error={kinds.error} onRetry={kinds.retry} />
      {state.loading ? (
        <Skeleton />
      ) : (
        !state.error &&
        selected && (
          <>
            {!readonly && (
              <Can perm="UI-01.update">
                <div className={styles.toolbar}>
                  <Button
                    data-testid="allocation-add-person"
                    disabled={blocked}
                    onClick={() => add("PERSON")}
                  >
                    + 사람 줄
                  </Button>
                  <Button
                    data-testid="allocation-add-item"
                    disabled={blocked}
                    onClick={() => add("ITEM")}
                  >
                    + 항목 줄
                  </Button>
                </div>
              </Can>
            )}
            {outside && (
              <p className={styles.warning}>
                수행 기간 밖의 기존 월 값도 합계에 포함됩니다. 기존 값을
                유지하거나 삭제할 수 있습니다.
              </p>
            )}
            {lines.length === 0 && (
              <p className={styles.empty}>등록된 배분표가 없습니다.</p>
            )}
            <div className={styles.scroll}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    <th>종류</th>
                    <th>사람 또는 항목명</th>
                    <th>직무</th>
                    <th>투입일</th>
                    <th>투입 구분</th>
                    <th>비고</th>
                    {months.map((m) => (
                      <th
                        key={m}
                        data-testid={`allocation-month-header-${m}`}
                        colSpan={2}
                      >
                        {m}
                        {!period.includes(m) && (
                          <Badge tone="warning">기간 밖</Badge>
                        )}
                      </th>
                    ))}
                    {!readonly && <th>작업</th>}
                  </tr>
                  <tr>
                    <th colSpan={6} />
                    {months.map((m) => (
                      <Fragment key={m}>
                        <th>금액</th>
                        <th>MM</th>
                      </Fragment>
                    ))}
                    {!readonly && <th />}
                  </tr>
                </thead>
                <tbody>
                  {lines.map((line, index) => (
                    <tr key={index} data-testid={`allocation-row-${index}`}>
                      <td>{line.line_ty_cd === "PERSON" ? "사람" : "항목"}</td>
                      <td>
                        {line.line_ty_cd === "PERSON" ? (
                          <>
                            <select
                              aria-label={`사람 ${index + 1}`}
                              data-field="dev_id"
                              data-testid={`allocation-developer-${index}`}
                              disabled={
                                blocked ||
                                developers.loading ||
                                !!developers.error
                              }
                              value={line.dev_id ?? ""}
                              onChange={(e) =>
                                change(index, {
                                  dev_id: e.target.value
                                    ? Number(e.target.value)
                                    : null,
                                })
                              }
                            >
                              <option value="">선택</option>
                              {line.dev_id &&
                                !developers.data?.some(
                                  (d) => d.id === line.dev_id,
                                ) && (
                                  <option value={line.dev_id}>
                                    기존 개발자 #{line.dev_id}
                                  </option>
                                )}
                              {developers.data?.map((d) => (
                                <option
                                  key={d.id}
                                  data-testid={`allocation-developer-option-${index}-${d.id}`}
                                  value={d.id}
                                >
                                  {d.name} · {d.mngNo}
                                </option>
                              ))}
                            </select>
                            {fieldMessage(index, "dev_id")}
                          </>
                        ) : (
                          <>
                            <input
                              aria-label={`항목명 ${index + 1}`}
                              data-field="item_nm"
                              data-testid={`allocation-item-${index}`}
                              value={line.item_nm ?? ""}
                              maxLength={100}
                              disabled={blocked}
                              onChange={(e) =>
                                change(index, { item_nm: e.target.value })
                              }
                            />
                            {fieldMessage(index, "item_nm")}
                          </>
                        )}
                      </td>
                      <td>
                        {line.line_ty_cd === "PERSON" ? (
                          <>
                            <select
                              aria-label={`직무 ${index + 1}`}
                              data-field="job_cd"
                              data-testid={`allocation-job-${index}`}
                              value={line.job_cd ?? ""}
                              disabled={blocked || jobs.loading || !!jobs.error}
                              onChange={(e) =>
                                change(index, {
                                  job_cd: e.target.value || null,
                                })
                              }
                            >
                              <option value="">선택</option>
                              {line.job_cd &&
                                !jobs.codes.some(
                                  (c) => c.code === line.job_cd,
                                ) && (
                                  <option value={line.job_cd}>
                                    {line.job_cd}
                                  </option>
                                )}
                              {jobs.codes.map((c) => (
                                <option
                                  key={c.code}
                                  data-testid={`allocation-job-option-${index}-${c.code.toLowerCase().replaceAll("_", "-")}`}
                                  value={c.code}
                                >
                                  {c.name}
                                </option>
                              ))}
                            </select>
                            {fieldMessage(index, "job_cd")}
                          </>
                        ) : (
                          "–"
                        )}
                      </td>
                      <td>
                        {line.line_ty_cd === "PERSON" ? (
                          <>
                            <input
                              type="date"
                              aria-label={`투입일 ${index + 1}`}
                              data-field="asgn_bgn_dt"
                              data-testid={`allocation-date-${index}`}
                              value={line.asgn_bgn_dt ?? ""}
                              disabled={blocked}
                              onChange={(e) =>
                                change(index, {
                                  asgn_bgn_dt: e.target.value || null,
                                })
                              }
                            />
                            {fieldMessage(index, "asgn_bgn_dt")}
                          </>
                        ) : (
                          "–"
                        )}
                      </td>
                      <td>
                        <select
                          aria-label={`투입 구분 ${index + 1}`}
                          data-field="asgn_se_cd"
                          data-testid={`allocation-kind-${index}`}
                          value={line.asgn_se_cd ?? ""}
                          disabled={blocked || kinds.loading || !!kinds.error}
                          onChange={(e) =>
                            change(index, {
                              asgn_se_cd: e.target.value || null,
                            })
                          }
                        >
                          <option value="">선택 안 함</option>
                          {line.asgn_se_cd &&
                            !kinds.codes.some(
                              (c) => c.code === line.asgn_se_cd,
                            ) && (
                              <option value={line.asgn_se_cd}>
                                {line.asgn_se_cd}
                              </option>
                            )}
                          {kinds.codes.map((c) => (
                            <option
                              key={c.code}
                              data-testid={`allocation-kind-option-${index}-${c.code.toLowerCase().replaceAll("_", "-")}`}
                              value={c.code}
                            >
                              {c.name}
                            </option>
                          ))}
                        </select>
                        {fieldMessage(index, "asgn_se_cd")}
                      </td>
                      <td>
                        <input
                          aria-label={`비고 ${index + 1}`}
                          data-field="rmrk_desc"
                          data-testid={`allocation-remark-${index}`}
                          maxLength={200}
                          value={line.rmrk_desc ?? ""}
                          disabled={blocked}
                          onChange={(e) =>
                            change(index, { rmrk_desc: e.target.value || null })
                          }
                        />
                        {fieldMessage(index, "rmrk_desc")}
                      </td>
                      {months.flatMap((m) =>
                        (["aloc_amt", "mm_qty"] as const).map((key) => {
                          const cell = line.months.find((c) => c.aloc_ym === m);
                          return (
                            <td
                              key={`${m}-${key}`}
                              data-testid={`allocation-cell-${index}-${m}-${key.replaceAll("_", "-")}`}
                            >
                              <input
                                inputMode="decimal"
                                aria-label={`${m} ${key === "aloc_amt" ? "금액" : "MM"} ${index + 1}`}
                                data-field={key}
                                data-testid={`allocation-${key.replaceAll("_", "-")}-${index}-${m}`}
                                value={cell?.[key] ?? ""}
                                disabled={blocked}
                                readOnly={!period.includes(m)}
                                onChange={(e) =>
                                  cellChange(index, m, key, e.target.value)
                                }
                              />
                              {(localError?.month
                                ? localError.month === m
                                : m === months[0]) && fieldMessage(index, key)}
                              {!readonly &&
                                !period.includes(m) &&
                                cell?.[key] && (
                                  <Button
                                    small
                                    data-testid={`allocation-clear-${key.replaceAll("_", "-")}-${index}-${m}`}
                                    disabled={blocked}
                                    onClick={() =>
                                      cellChange(index, m, key, "")
                                    }
                                  >
                                    값 삭제
                                  </Button>
                                )}
                            </td>
                          );
                        }),
                      )}
                      {!readonly && (
                        <td>
                          <Button
                            small
                            data-testid={`allocation-delete-${index}`}
                            disabled={blocked}
                            onClick={() => {
                              setLines((current) =>
                                current.filter((_, i) => i !== index),
                              );
                              setDirty(true);
                            }}
                          >
                            삭제
                          </Button>
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
                <tfoot>
                  <tr>
                    <th colSpan={6}>월별 합계</th>
                    {months.flatMap((m) => [
                      <td
                        key={`${m}-amount`}
                        data-testid={`allocation-total-amount-${m}`}
                      >
                        {money(amountSum(lines, m))}
                      </td>,
                      <td
                        key={`${m}-mm`}
                        data-testid={`allocation-total-mm-${m}`}
                      >
                        {mmSum(lines, m)}
                      </td>,
                    ])}
                    {!readonly && <td />}
                  </tr>
                </tfoot>
              </table>
            </div>
            <p className={styles.total}>
              전체 합계 <strong>{money(amountSum(lines))}원</strong> ·{" "}
              {mmSum(lines)} MM
            </p>
            {mismatch && (
              <p
                className={styles.warning}
                data-testid="allocation-amount-warning"
              >
                배분표 합계({money(activeTotal)})가 현재 계약금액(
                {money(state.data!.active.cur_cntrct_amt)})과 다릅니다.
              </p>
            )}
            {!readonly && (
              <Can perm="UI-01.update">
                <div className={styles.footer}>
                  <Button
                    data-testid="allocation-cancel"
                    disabled={blocked}
                    onClick={() => {
                      setLines(draftOf(selected.items));
                      setDirty(false);
                      setLocalError(null);
                      write.clear();
                    }}
                  >
                    취소
                  </Button>
                  <Button
                    data-testid="allocation-save"
                    variant="primary"
                    pending={write.pending}
                    disabled={
                      blocked ||
                      !!jobs.error ||
                      !!kinds.error ||
                      jobs.loading ||
                      kinds.loading
                    }
                    onClick={save}
                  >
                    저장
                  </Button>
                </div>
              </Can>
            )}
          </>
        )
      )}
      {nextStage && (
        <Modal
          title="저장하지 않은 내용이 있습니다."
          confirmation
          onCancel={() => setNextStage(null)}
        >
          <div data-testid="allocation-unsaved-dialog">
            <p>단계를 바꾸면 입력한 내용이 사라집니다.</p>
            <Button
              data-testid="allocation-keep-editing"
              onClick={() => setNextStage(null)}
            >
              계속 작성
            </Button>
            <Button
              data-testid="allocation-discard"
              onClick={() => {
                setDirty(false);
                setStage(nextStage);
                setNextStage(null);
                setMessage("");
              }}
            >
              나가기
            </Button>
          </div>
        </Modal>
      )}
    </section>
  );
}
