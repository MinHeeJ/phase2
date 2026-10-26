import { useCallback, useEffect, useRef, useState } from "react";
import {
  Button,
  Badge,
  Modal,
  Skeleton,
  LockedBanner,
  ConfirmDialog,
} from "../../shared/Controls";
import {
  TextInput,
  MoneyInput,
  SelectInput,
  TextareaInput,
} from "../../shared/Inputs";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { Can } from "../../shared/Can";
import { formatMoney, isMonth, isDate } from "../../lib/format";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { listManagedCodes } from "../admin/client";
import { useCodes } from "../shared/useCodes";
import { getMonthClose, monthStatus } from "../shared/api";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import {
  createProjectExpense,
  listProjectExpenses,
  updateProjectExpense,
  updateProjectExpenseRemoval,
  type ProjectExpense,
  type ProjectExpenseInput,
} from "./client";
import styles from "./ProjectExpenses.module.css";

// SCR-UI-01: directly embedded in the selected project's 직접 경비 tab.
export function ProjectExpensePanel({
  projectId,
  useYn,
  onChanged,
}: {
  projectId: number;
  useYn: "Y" | "N";
  onChanged?: () => Promise<unknown>;
}) {
  const mobile = useDeveloperReadOnly();
  const readonly = mobile || useYn !== "Y";
  const read = useCallback(async () => {
    const [rows, names] = await Promise.all([
      listProjectExpenses(projectId),
      listManagedCodes("EXPENSE_ITEM"),
    ]);
    const months = rows.map((r) => r.expns_ym).sort();
    const closures = months.length
      ? await getMonthClose(months[0], months[months.length - 1])
      : [];
    return { rows, names, closures };
  }, [projectId]);
  const state = useAdminRead(read);
  const [editing, setEditing] = useState<ProjectExpense | "new" | null>(null);
  const [message, setMessage] = useState("");
  const refresh = async () => {
    await state.reload();
    await onChanged?.();
  };
  const removal = useAdminWrite(refresh);
  useEffect(() => {
    setEditing(null);
    setMessage("");
  }, [projectId, readonly]);
  const totals = new Map<string, { amount: bigint; vat: bigint }>();
  for (const row of state.data?.rows ?? []) {
    const sum = totals.get(row.expns_ym) ?? { amount: 0n, vat: 0n };
    sum.amount += BigInt(row.expns_amt);
    sum.vat += BigInt(row.vat_amt);
    totals.set(row.expns_ym, sum);
  }
  return (
    <section
      role="tabpanel"
      aria-label="직접 경비"
      data-testid="project-expense-panel"
      data-screen-id="SCR-UI-01"
    >
      <div className={styles.toolbar}>
        <h3>직접 경비</h3>
        {!readonly && (
          <Can perm="UI-01.update">
            <Button
              data-testid="project-expense-add"
              disabled={
                state.loading ||
                !!state.error ||
                removal.pending ||
                removal.uncertain
              }
              onClick={() => {
                setMessage("");
                setEditing("new");
              }}
            >
              + 경비 추가
            </Button>
          </Can>
        )}
      </div>
      {useYn === "N" && (
        <LockedBanner>사용 안 함으로 바꾼 프로젝트입니다.</LockedBanner>
      )}
      {message && (
        <p role="status" data-testid="project-expense-success">
          {message}
        </p>
      )}
      <ErrorBanner
        error={state.error}
        onRetry={() => void state.reload().catch(() => {})}
      />
      <ErrorBanner
        error={removal.error}
        pending={removal.pending}
        onRetry={() => void removal.recover()}
      />
      {state.loading ? (
        <Skeleton />
      ) : (
        state.data && (
          <>
            {!state.data.rows.length ? (
              <p className={styles.empty}>등록된 직접 경비가 없습니다.</p>
            ) : (
              <>
                <div className={styles.tableScroll}>
                  <table className={styles.table}>
                    <thead>
                      <tr>
                        {[
                          "월",
                          "경비 구분",
                          "금액",
                          "부가세",
                          "지급일",
                          "비고",
                          "작업",
                        ].map((label) => (
                          <th
                            data-testid={`project-expense-column-${["월", "경비 구분", "금액", "부가세", "지급일", "비고", "작업"].indexOf(label)}`}
                            key={label}
                          >
                            {label}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {state.data.rows.map((row) => {
                        const closed =
                          monthStatus(state.data!.closures, row.expns_ym) ===
                          "CLOSED";
                        return (
                          <tr
                            key={row.expns_id}
                            data-testid={`project-expense-row-${row.expns_id}`}
                          >
                            <td>
                              {row.expns_ym}{" "}
                              {closed && <Badge tone="locked">마감</Badge>}
                            </td>
                            <td>
                              {state.data!.names.find(
                                (c) => c.dtl_cd === row.expns_item_cd,
                              )?.cd_nm ?? row.expns_item_cd}
                            </td>
                            <td className={styles.money}>
                              {formatMoney(BigInt(row.expns_amt))}
                            </td>
                            <td className={styles.money}>
                              {formatMoney(BigInt(row.vat_amt))}
                            </td>
                            <td>{row.pay_dt ?? "–"}</td>
                            <td className={styles.memo}>
                              {row.rmrk_desc ?? "–"}
                            </td>
                            <td>
                              {!readonly && !closed && !state.error && (
                                <Can perm="UI-01.update">
                                  <div className={styles.actions}>
                                    <Button
                                      small
                                      data-testid={`project-expense-edit-${row.expns_id}`}
                                      disabled={
                                        removal.pending || removal.uncertain
                                      }
                                      onClick={() => {
                                        setMessage("");
                                        setEditing(row);
                                      }}
                                    >
                                      수정
                                    </Button>
                                    <Button
                                      small
                                      data-testid={`project-expense-delete-${row.expns_id}`}
                                      disabled={
                                        removal.pending || removal.uncertain
                                      }
                                      onClick={() => {
                                        setMessage("");
                                        void removal.save(
                                          () =>
                                            updateProjectExpenseRemoval(
                                              projectId,
                                              row.expns_id,
                                            ),
                                          () => setMessage("삭제했습니다."),
                                        );
                                      }}
                                    >
                                      삭제
                                    </Button>
                                  </div>
                                </Can>
                              )}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
                <h4>월별 합계</h4>
                <div className={styles.tableScroll}>
                  <table className={styles.table} aria-label="월별 합계">
                    <thead>
                      <tr>
                        <th>월</th>
                        <th>금액</th>
                        <th>부가세</th>
                      </tr>
                    </thead>
                    <tbody>
                      {[...totals]
                        .sort(([a], [b]) => b.localeCompare(a))
                        .map(([month, sum]) => (
                          <tr
                            key={month}
                            data-testid={`project-expense-total-${month}`}
                          >
                            <td>{month}</td>
                            <td className={styles.money}>
                              {formatMoney(sum.amount)}
                            </td>
                            <td className={styles.money}>
                              {formatMoney(sum.vat)}
                            </td>
                          </tr>
                        ))}
                    </tbody>
                  </table>
                </div>
              </>
            )}
          </>
        )
      )}
      {!readonly && editing && (
        <ProjectExpenseForm
          key={editing === "new" ? "new" : editing.expns_id}
          projectId={projectId}
          initial={editing === "new" ? undefined : editing}
          reload={refresh}
          onCancel={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            setMessage("저장했습니다.");
          }}
        />
      )}
    </section>
  );
}

// SCR-UI-01-M4
export function ProjectExpenseForm({
  projectId,
  initial,
  reload,
  onCancel,
  onSaved,
}: {
  projectId: number;
  initial?: ProjectExpense;
  reload: () => Promise<unknown>;
  onCancel: () => void;
  onSaved: () => void;
}) {
  const [draft, setDraft] = useState<ProjectExpenseInput>(
    initial ?? {
      expns_ym: "",
      expns_item_cd: "",
      expns_amt: null,
      pay_dt: null,
      rmrk_desc: null,
    },
  );
  const [vat, setVat] = useState(initial ? String(initial.vat_amt) : "");
  const vatEdited = useRef(!!initial);
  const [dirty, setDirty] = useState(false);
  const [leaving, setLeaving] = useState(false);
  const [localError, setLocalError] = useState<{
    field: string;
    message: string;
  } | null>(null);
  const codes = useCodes("EXPENSE_ITEM");
  const write = useAdminWrite(reload);
  const form = useRef<HTMLFormElement>(null);
  const readClosure = useCallback(
    () =>
      isMonth(draft.expns_ym)
        ? getMonthClose(draft.expns_ym, draft.expns_ym)
        : Promise.resolve([]),
    [draft.expns_ym],
  );
  const closure = useAdminRead(readClosure);
  const closed =
    !!closure.data && monthStatus(closure.data, draft.expns_ym) === "CLOSED";
  const error = localError ?? write.error;
  useEffect(() => {
    if (error?.field)
      form.current
        ?.querySelector<HTMLElement>(
          `[name="${error.field.replace(/[^a-z_]/g, "")}"]`,
        )
        ?.focus();
  }, [error]);
  const change = (input: Partial<ProjectExpenseInput>) => {
    setDraft((old) => ({ ...old, ...input }));
    setDirty(true);
    setLocalError(null);
    write.clear();
  };
  const fieldError = (field: string) =>
    error?.field === field ? error.message : null;
  const cancel = () => {
    if (!write.pending) {
      if (dirty) setLeaving(true);
      else onCancel();
    }
  };
  const save = () => {
    let invalid: { field: string; message: string } | null = null;
    if (!isMonth(draft.expns_ym))
      invalid = { field: "expns_ym", message: "월을 입력하세요." };
    else if (!draft.expns_item_cd)
      invalid = { field: "expns_item_cd", message: "경비 구분을 골라 주세요." };
    else if (
      draft.expns_amt == null ||
      !Number.isSafeInteger(draft.expns_amt) ||
      draft.expns_amt < 1
    )
      invalid = { field: "expns_amt", message: "금액은 1원 이상 입력하세요." };
    else if (
      vat !== "" &&
      (!/^-?\d+$/.test(vat) || !Number.isSafeInteger(Number(vat)))
    )
      invalid = { field: "vat_amt", message: "원 단위 정수를 입력하세요." };
    else if (draft.pay_dt && !isDate(draft.pay_dt))
      invalid = { field: "pay_dt", message: "올바른 날짜를 입력하세요." };
    else if ((draft.rmrk_desc?.length ?? 0) > 200)
      invalid = {
        field: "rmrk_desc",
        message: "비고는 200자 이하로 입력하세요.",
      };
    else if (closed)
      invalid = {
        field: "expns_ym",
        message: `${draft.expns_ym}은 마감된 달이라 입력할 수 없습니다.`,
      };
    setLocalError(invalid);
    if (invalid || closure.loading || closure.error) return;
    const input = { ...draft, vat_amt: vat === "" ? undefined : Number(vat) };
    void write.save(
      () =>
        initial
          ? updateProjectExpense(projectId, initial.expns_id, input)
          : createProjectExpense(projectId, input),
      onSaved,
    );
  };
  const disabled = write.pending || write.uncertain;
  const options = codes.codes.map((c) => ({ value: c.code, label: c.name }));
  if (initial && !options.some((c) => c.value === initial.expns_item_cd))
    options.push({
      value: initial.expns_item_cd,
      label: initial.expns_item_cd,
    });
  return (
    <>
      <Modal
        title={initial ? "직접 경비 수정" : "직접 경비 추가"}
        onCancel={cancel}
      >
        <form
          ref={form}
          noValidate
          className={styles.form}
          data-testid="project-expense-form"
          data-screen-id="SCR-UI-01-M4"
          onSubmit={(e) => {
            e.preventDefault();
            save();
          }}
        >
          <ErrorBanner
            error={write.error}
            pending={write.pending}
            onRetry={() => void write.recover()}
          />
          <ErrorBanner error={codes.error} onRetry={codes.retry} />
          <ErrorBanner
            error={closure.error}
            onRetry={() => void closure.reload().catch(() => {})}
          />
          {closed && (
            <LockedBanner>
              {draft.expns_ym}은 마감된 달이라 입력할 수 없습니다.
            </LockedBanner>
          )}
          <div className={styles.fields}>
            <TextInput
              label="월"
              name="expns_ym"
              type="month"
              required
              value={draft.expns_ym}
              disabled={disabled}
              error={fieldError("expns_ym")}
              onValueChange={(value) => change({ expns_ym: value })}
            />
            <SelectInput
              label="경비 구분"
              name="expns_item_cd"
              required
              value={draft.expns_item_cd}
              disabled={disabled || codes.loading || !!codes.error}
              error={fieldError("expns_item_cd")}
              options={options}
              onValueChange={(value) => change({ expns_item_cd: value })}
            />
            <MoneyInput
              label="금액"
              name="expns_amt"
              required
              value={draft.expns_amt}
              disabled={disabled}
              error={fieldError("expns_amt")}
              onValueChange={(value) => {
                change({ expns_amt: value });
                if (!vatEdited.current)
                  setVat(value == null ? "" : String(BigInt(value) / 10n));
              }}
            />
            <TextInput
              label="부가세"
              name="vat_amt"
              value={vat}
              disabled={disabled}
              error={fieldError("vat_amt")}
              onValueChange={(value) => {
                setVat(value);
                vatEdited.current = true;
                change({});
              }}
            />
            <TextInput
              label="지급일"
              name="pay_dt"
              type="date"
              value={draft.pay_dt ?? ""}
              disabled={disabled}
              error={fieldError("pay_dt")}
              onValueChange={(value) => change({ pay_dt: value })}
            />
          </div>
          <TextareaInput
            label="비고"
            name="rmrk_desc"
            maxLength={200}
            value={draft.rmrk_desc ?? ""}
            disabled={disabled}
            error={fieldError("rmrk_desc")}
            onValueChange={(value) => change({ rmrk_desc: value })}
          />
          <p className={styles.notice}>
            외주 인력비는 넣지 않습니다(투입·지급에서 처리).
          </p>
          <div className={styles.actions}>
            <Button
              type="button"
              data-testid="project-expense-cancel"
              disabled={write.pending}
              onClick={cancel}
            >
              취소
            </Button>
            <Button
              type="submit"
              variant="primary"
              data-testid="project-expense-save"
              pending={write.pending}
              disabled={
                write.uncertain ||
                codes.loading ||
                !!codes.error ||
                closure.loading ||
                !!closure.error
              }
            >
              저장
            </Button>
          </div>
        </form>
      </Modal>
      {leaving && (
        <ConfirmDialog
          title="저장하지 않은 내용이 있습니다."
          confirmLabel="나가기"
          cancelLabel="계속 작성"
          onCancel={() => setLeaving(false)}
          onConfirm={onCancel}
        >
          입력한 내용을 버리고 나갈까요?
        </ConfirmDialog>
      )}
    </>
  );
}
