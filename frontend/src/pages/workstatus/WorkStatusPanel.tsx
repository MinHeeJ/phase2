import { useCallback, useEffect, useRef, useState } from "react";
import {
  Badge,
  Button,
  Modal,
  Pagination,
  Skeleton,
} from "../../shared/Controls";
import { TextInput, SelectInput, TextareaInput } from "../../shared/Inputs";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { Can } from "../../shared/Can";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { useCodes } from "../shared/useCodes";
import {
  listDeveloperWorkStatuses,
  createDeveloperWorkStatus,
  updateDeveloperWorkStatus,
  updateDeveloperWorkStatusRemoval,
  type WorkStatusInput,
  type WorkStatusRow,
  type WorkStatusCode,
} from "./client";
import styles from "./WorkStatus.module.css";

function useWorkStatusReadonly() {
  const [readonly, setReadonly] = useState(() => window.innerWidth <= 767);
  useEffect(() => {
    const resize = () => setReadonly(window.innerWidth <= 767);
    window.addEventListener("resize", resize);
    return () => window.removeEventListener("resize", resize);
  }, []);
  return readonly;
}

// SCR-UI-02: selected developer's 근무 상태 이력 tab, not a second developer route.
export function WorkStatusPanel({
  developerId,
  onChanged,
}: {
  developerId: number;
  onChanged: () => Promise<unknown>;
}) {
  const [page, setPage] = useState(0),
    [size, setSize] = useState<20 | 50 | 100>(50);
  const read = useCallback(
    () =>
      listDeveloperWorkStatuses(developerId, {
        page,
        size,
        sort: "bgn_dt,desc",
      }),
    [developerId, page, size],
  );
  const state = useAdminRead(read);
  const codes = useCodes("WORK_STATUS");
  const [editing, setEditing] = useState<WorkStatusRow | "new" | null>(null);
  const [removing, setRemoving] = useState<WorkStatusRow | null>(null);
  const [message, setMessage] = useState("");
  const readonly = useWorkStatusReadonly();
  useEffect(() => {
    setEditing(null);
    setRemoving(null);
    setPage(0);
    setMessage("");
  }, [developerId]);
  useEffect(() => {
    if (readonly) {
      setEditing(null);
      setRemoving(null);
    }
  }, [readonly]);
  const refresh = async () => {
    await state.reload();
    await onChanged();
  };
  const name = (code: string) =>
    codes.codes.find((c) => c.code === code)?.name ?? code;
  return (
    <section
      role="tabpanel"
      aria-label="근무 상태 이력"
      data-testid="work-status-panel"
      data-screen-id="SCR-UI-02"
    >
      <div className={styles.toolbar}>
        <h3>근무 상태 이력</h3>
        {!readonly && (
          <Can perm="UI-02.update">
            <Button
              data-testid="work-status-add"
              onClick={() => {
                setMessage("");
                setEditing("new");
              }}
            >
              + 근무 상태 추가
            </Button>
          </Can>
        )}
      </div>
      {message && <p role="status">{message}</p>}
      <ErrorBanner
        error={state.error}
        onRetry={() => void state.reload().catch(() => {})}
      />
      <ErrorBanner error={codes.error} onRetry={codes.retry} />
      {state.loading ? (
        <Skeleton />
      ) : (
        !state.error &&
        state.data && (
          <>
            {state.data.items.length ? (
              <div className={styles.tableScroll}>
                <table className={styles.table}>
                  <thead>
                    <tr>
                      <th>상태</th>
                      <th>시작일</th>
                      <th>종료일</th>
                      <th>유급</th>
                      <th>메모</th>
                      {!readonly && <th>작업</th>}
                    </tr>
                  </thead>
                  <tbody>
                    {state.data.items.map((row) => (
                      <tr
                        key={row.work_stts_hist_id}
                        data-testid={`work-status-row-${row.work_stts_hist_id}`}
                      >
                        <td>
                          <Badge
                            tone={
                              row.work_stts_cd === "RESIGN"
                                ? "danger"
                                : "locked"
                            }
                          >
                            {name(row.work_stts_cd)}
                          </Badge>
                        </td>
                        <td>{row.bgn_dt}</td>
                        <td>{row.end_dt ?? "–"}</td>
                        <td>
                          {row.work_stts_cd === "VACATION"
                            ? row.paid_yn === "Y"
                              ? "유급"
                              : "무급"
                            : "–"}
                        </td>
                        <td className={styles.memo}>{row.rmrk_desc ?? "–"}</td>
                        {!readonly && (
                          <td>
                            <Can perm="UI-02.update">
                              <div className={styles.actions}>
                                <Button
                                  small
                                  data-testid={`work-status-edit-${row.work_stts_hist_id}`}
                                  onClick={() => {
                                    setMessage("");
                                    setEditing(row);
                                  }}
                                >
                                  수정
                                </Button>
                                <Button
                                  small
                                  data-testid={`work-status-delete-${row.work_stts_hist_id}`}
                                  onClick={() => {
                                    setMessage("");
                                    setRemoving(row);
                                  }}
                                >
                                  삭제
                                </Button>
                              </div>
                            </Can>
                          </td>
                        )}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <p className={styles.empty}>등록된 근무 상태가 없습니다.</p>
            )}
            {state.data.total > 0 && (
              <Pagination
                page={page + 1}
                size={size}
                total={state.data.total}
                onPage={(p) => setPage(p - 1)}
                onSize={(s) => {
                  setSize(s);
                  setPage(0);
                }}
              />
            )}
          </>
        )
      )}
      {!readonly && editing && (
        <WorkStatusForm
          key={editing === "new" ? "new" : editing.work_stts_hist_id}
          developerId={developerId}
          initial={editing === "new" ? undefined : editing}
          onCancel={() => setEditing(null)}
          reload={refresh}
          onSaved={() => {
            setEditing(null);
            setMessage("저장했습니다.");
          }}
        />
      )}
      {!readonly && removing && (
        <WorkStatusDelete
          developerId={developerId}
          row={removing}
          reload={refresh}
          onCancel={() => setRemoving(null)}
          onDeleted={() => {
            setRemoving(null);
            setMessage("삭제했습니다.");
            if (state.data?.items.length === 1 && page > 0) setPage(page - 1);
          }}
        />
      )}
    </section>
  );
}

// SCR-UI-02-M1
export function WorkStatusForm({
  developerId,
  initial,
  onCancel,
  reload,
  onSaved,
}: {
  developerId: number;
  initial?: WorkStatusRow;
  onCancel: () => void;
  reload: () => Promise<unknown>;
  onSaved: () => void;
}) {
  const [draft, setDraft] = useState<WorkStatusInput>(
    initial ?? {
      work_stts_cd: "",
      bgn_dt: "",
      end_dt: null,
      paid_yn: "N",
      rmrk_desc: null,
    },
  );
  const [localError, setLocalError] = useState<{
    field: string;
    message: string;
  } | null>(null);
  const write = useAdminWrite(reload);
  const codes = useCodes("WORK_STATUS");
  const form = useRef<HTMLFormElement>(null);
  const error = localError ?? write.error;
  useEffect(() => {
    if (error?.field)
      form.current
        ?.querySelector<HTMLElement>(
          `[name="${error.field.replace(/[^a-z_]/g, "")}"]`,
        )
        ?.focus();
  }, [error]);
  const change = (field: keyof WorkStatusInput, value: string) => {
    setLocalError(null);
    write.clear();
    setDraft((d) => ({
      ...d,
      [field]: value,
      ...(field === "work_stts_cd"
        ? {
            paid_yn:
              value === "VACATION" && d.work_stts_cd === "VACATION"
                ? d.paid_yn
                : "N",
            end_dt: value === "RESIGN" ? null : d.end_dt,
          }
        : {}),
    }));
  };
  const fieldError = (key: string) =>
    error?.field === key ? error.message : null;
  const cancel = () => {
    if (!write.pending) onCancel();
  };
  const save = () => {
    let invalid: { field: string; message: string } | null = null;
    if (!draft.work_stts_cd)
      invalid = { field: "work_stts_cd", message: "상태를 골라 주세요." };
    else if (!draft.bgn_dt)
      invalid = { field: "bgn_dt", message: "시작일을 입력해 주세요." };
    else if (
      draft.work_stts_cd !== "RESIGN" &&
      draft.end_dt &&
      draft.end_dt < draft.bgn_dt
    )
      invalid = {
        field: "end_dt",
        message: "종료일은 시작일 이후여야 합니다.",
      };
    else if ((draft.rmrk_desc?.length ?? 0) > 500)
      invalid = {
        field: "rmrk_desc",
        message: "메모는 500자 이하로 입력해 주세요.",
      };
    setLocalError(invalid);
    if (invalid) return;
    void write.save(
      () =>
        initial
          ? updateDeveloperWorkStatus(
              developerId,
              initial.work_stts_hist_id,
              draft,
            )
          : createDeveloperWorkStatus(developerId, draft),
      onSaved,
    );
  };
  return (
    <Modal
      title={initial ? "근무 상태 수정" : "근무 상태 추가"}
      onCancel={cancel}
    >
      <form
        ref={form}
        noValidate
        className={styles.form}
        data-testid="work-status-form"
        data-screen-id="SCR-UI-02-M1"
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
        <SelectInput
          name="work_stts_cd"
          label="상태"
          required
          value={draft.work_stts_cd}
          disabled={
            write.pending || write.uncertain || codes.loading || !!codes.error
          }
          error={fieldError("work_stts_cd")}
          options={codes.codes
            .filter((c) =>
              ["VACATION", "PARENTAL", "UNPAID", "LEAVE", "RESIGN"].includes(
                c.code,
              ),
            )
            .map((c) => ({ value: c.code, label: c.name }))}
          onValueChange={(v) => change("work_stts_cd", v as WorkStatusCode)}
        />
        <div className={styles.dates}>
          <TextInput
            name="bgn_dt"
            label="시작일"
            type="date"
            required
            value={draft.bgn_dt}
            disabled={write.pending || write.uncertain}
            error={fieldError("bgn_dt")}
            onValueChange={(v) => change("bgn_dt", v)}
          />
          {draft.work_stts_cd !== "RESIGN" && (
            <TextInput
              name="end_dt"
              label="종료일"
              type="date"
              value={draft.end_dt ?? ""}
              disabled={write.pending || write.uncertain}
              error={fieldError("end_dt")}
              onValueChange={(v) => change("end_dt", v)}
            />
          )}
        </div>
        {draft.work_stts_cd === "VACATION" && (
          <label className={styles.paid}>
            <input
              data-testid="work-status-paid"
              name="paid_yn"
              type="checkbox"
              checked={draft.paid_yn === "Y"}
              disabled={write.pending || write.uncertain}
              onChange={(e) => change("paid_yn", e.target.checked ? "Y" : "N")}
            />
            유급
          </label>
        )}
        <TextareaInput
          name="rmrk_desc"
          label="메모"
          maxLength={500}
          value={draft.rmrk_desc ?? ""}
          disabled={write.pending || write.uncertain}
          error={fieldError("rmrk_desc")}
          onValueChange={(v) => change("rmrk_desc", v)}
        />
        <div className={styles.actions}>
          <Button
            type="button"
            data-testid="work-status-cancel"
            disabled={write.pending}
            onClick={cancel}
          >
            취소
          </Button>
          <Button
            data-testid="work-status-save"
            type="submit"
            variant="primary"
            pending={write.pending}
            disabled={write.uncertain || codes.loading || !!codes.error}
          >
            저장
          </Button>
        </div>
      </form>
    </Modal>
  );
}

// SCR-UI-02-M2
function WorkStatusDelete({
  developerId,
  row,
  onCancel,
  reload,
  onDeleted,
}: {
  developerId: number;
  row: WorkStatusRow;
  onCancel: () => void;
  reload: () => Promise<unknown>;
  onDeleted: () => void;
}) {
  const write = useAdminWrite(reload);
  const cancel = () => {
    if (!write.pending) onCancel();
  };
  return (
    <Modal title="이 근무 상태를 지울까요?" confirmation onCancel={cancel}>
      <div
        data-testid="work-status-delete-dialog"
        data-screen-id="SCR-UI-02-M2"
      >
        <ErrorBanner
          error={write.error}
          pending={write.pending}
          onRetry={() => void write.recover()}
        />
        <div className={styles.actions}>
          <Button
            data-testid="work-status-delete-cancel"
            disabled={write.pending}
            onClick={cancel}
          >
            취소
          </Button>
          <Button
            data-testid="work-status-delete-confirm"
            className={styles.destructive}
            pending={write.pending}
            disabled={write.uncertain}
            onClick={() =>
              void write.save(
                () =>
                  updateDeveloperWorkStatusRemoval(
                    developerId,
                    row.work_stts_hist_id,
                  ),
                onDeleted,
              )
            }
          >
            삭제
          </Button>
        </div>
      </div>
    </Modal>
  );
}
