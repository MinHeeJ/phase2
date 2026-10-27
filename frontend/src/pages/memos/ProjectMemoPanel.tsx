import { useCallback, useEffect, useRef, useState } from "react";
import {
  Button,
  LockedBanner,
  Modal,
  Skeleton,
  SuccessToast,
} from "../../shared/Controls";
import { TextInput, TextareaInput } from "../../shared/Inputs";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { Can } from "../../shared/Can";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import * as client from "./client";
import type { MemoProject, ProjectWeeklyMemo } from "./client";
import styles from "./ProjectMemos.module.css";

function memoToday() {
  const parts = new Intl.DateTimeFormat("en", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(new Date());
  return ["year", "month", "day"]
    .map((key) => parts.find((p) => p.type === key)!.value)
    .join("-");
}
const writable = (project: MemoProject) =>
  project.use_yn === "Y" &&
  ["PLAN", "RUN", "HOLD"].includes(project.prj_stts_cd);

// SCR-UI-01: weekly memo region embedded in the selected project's progress tab.
export function ProjectMemoPanel({
  project,
  onChanged,
}: {
  project: MemoProject;
  onChanged: () => Promise<unknown>;
}) {
  const state = useAdminRead(
    useCallback(
      () => client.readMemoProgress(project.prj_id),
      [project.prj_id],
    ),
  );
  const [editing, setEditing] = useState<{
    initial?: ProjectWeeklyMemo;
    mode: "today" | "latest";
  } | null>(null);
  const [message, setMessage] = useState("");
  const allowed = writable(project);
  useEffect(() => {
    setEditing(null);
    setMessage("");
  }, [project.prj_id, allowed]);
  const rows = [...(state.data?.memos ?? [])].sort(
    (a, b) => b.wrt_dt.localeCompare(a.wrt_dt) || b.memo_id - a.memo_id,
  );
  const refresh = async () => {
    await state.reload();
    await onChanged();
  };
  const manual = project.cntrct_ty_cd === "TK" || project.cntrct_ty_cd === "IN";
  return (
    <section
      data-testid="project-memo-panel"
      data-screen-id="SCR-UI-01"
      aria-label="주간 메모"
    >
      <div className={styles.toolbar}>
        <h3>주간 메모</h3>
        {allowed && state.data && !state.loading && !state.error && (
          <Can perm="UI-01.update">
            <Button
              data-testid="project-memo-add"
              onClick={() =>
                setEditing({
                  mode: "today",
                  initial: rows.find((r) => r.wrt_dt === memoToday()),
                })
              }
            >
              + 이번 주 상태 적기
            </Button>
          </Can>
        )}
      </div>
      {!allowed && (
        <LockedBanner>
          {project.use_yn === "N"
            ? "사용 안 함"
            : project.prj_stts_cd === "END"
              ? "종료"
              : "실주"}{" "}
          프로젝트입니다. 기록은 읽기만 할 수 있습니다.
        </LockedBanner>
      )}
      {message && (
        <SuccessToast message={message} onDismiss={() => setMessage("")} />
      )}
      <ErrorBanner
        error={state.error}
        onRetry={() => void state.reload().catch(() => {})}
      />
      {state.loading ? (
        <Skeleton />
      ) : (
        !state.error && (
          <>
            {rows.length === 0 && (
              <p className={styles.empty}>아직 주간 메모가 없습니다.</p>
            )}
            {rows.map((row, index) => (
              <article
                key={row.memo_id}
                className={styles.memo}
                data-testid={`project-memo-row-${row.memo_id}`}
              >
                <div className={styles.toolbar}>
                  <strong>{row.wrt_dt}</strong>
                  <div className={styles.actions}>
                    {manual && (
                      <span>실적 진척률 {row.actl_prgs_rt ?? "–"}%</span>
                    )}
                    {allowed && index === 0 && (
                      <Can perm="UI-01.update">
                        <Button
                          small
                          data-testid={`project-memo-edit-${row.memo_id}`}
                          onClick={() =>
                            setEditing({ mode: "latest", initial: row })
                          }
                        >
                          수정
                        </Button>
                      </Can>
                    )}
                  </div>
                </div>
                <small>기본 사용자</small>
                <p>한 일 {row.cmpl_work_desc}</p>
                <p>할 일 {row.plan_work_desc}</p>
                {row.rqst_desc && (
                  <p className={styles.request}>요청 {row.rqst_desc}</p>
                )}
              </article>
            ))}
          </>
        )
      )}
      {allowed && editing && state.data && (
        <ProjectMemoForm
          project={project}
          initial={editing.initial}
          mode={editing.mode}
          currentRate={state.data.actl_prgs_rt}
          plannedRate={state.data.plan_prgs_rt}
          previousRate={
            (editing.mode === "latest" ? rows[1] : rows[0])?.actl_prgs_rt ??
            null
          }
          reload={refresh}
          onCancel={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            setMessage("주간 상태를 저장했습니다.");
          }}
        />
      )}
    </section>
  );
}

// SCR-UI-01-M11: POST identifies today; PUT identifies the latest memo by its path id.
export function ProjectMemoForm({
  project,
  initial,
  mode,
  currentRate,
  plannedRate,
  previousRate,
  reload,
  onCancel,
  onSaved,
}: {
  project: MemoProject;
  initial?: ProjectWeeklyMemo;
  mode: "today" | "latest";
  currentRate: number;
  plannedRate: number;
  previousRate: number | null;
  reload: () => Promise<unknown>;
  onCancel: () => void;
  onSaved: () => void;
}) {
  const manual = project.cntrct_ty_cd === "TK" || project.cntrct_ty_cd === "IN";
  const [rate, setRate] = useState(
    String(initial?.actl_prgs_rt ?? currentRate),
  );
  const [completed, setCompleted] = useState(initial?.cmpl_work_desc ?? "");
  const [planned, setPlanned] = useState(initial?.plan_work_desc ?? "");
  const [request, setRequest] = useState(initial?.rqst_desc ?? "");
  const [localError, setLocalError] = useState<{
    field: string;
    message: string;
  } | null>(null);
  const write = useAdminWrite(reload);
  const form = useRef<HTMLFormElement>(null);
  const error = localError ?? write.error;
  const blocked = write.pending || write.uncertain || !writable(project);
  useEffect(() => {
    if (error?.field)
      form.current
        ?.querySelector<HTMLElement>(
          `[name="${error.field.replace(/[^a-z_]/g, "")}"]`,
        )
        ?.focus();
  }, [error]);
  const fieldError = (key: string) =>
    error?.field === key ? error.message : null;
  const changed = (set: (value: string) => void) => (value: string) => {
    set(value);
    setLocalError(null);
    write.clear();
  };
  const save = () => {
    if (blocked) return;
    let field = "";
    if (manual && (!/^\d+$/.test(rate) || Number(rate) > 100))
      field = "actl_prgs_rt";
    else if (!completed.trim() || completed.trim().length > 200)
      field = "cmpl_work_desc";
    else if (!planned.trim() || planned.trim().length > 200)
      field = "plan_work_desc";
    else if (request.trim().length > 200) field = "rqst_desc";
    if (field) {
      setLocalError({
        field,
        message:
          field === "actl_prgs_rt"
            ? "실적 진척률은 0~100 사이 정수로 입력해 주세요."
            : field === "rqst_desc"
              ? "요청 사항은 200자 이내로 적어 주세요."
              : "한 일과 할 일을 적어 주세요.",
      });
      return;
    }
    const input = {
      actl_prgs_rt: manual ? Number(rate) : null,
      cmpl_work_desc: completed,
      plan_work_desc: planned,
      rqst_desc: request || null,
    };
    void write.save(
      () =>
        mode === "latest" && initial
          ? client.updateProjectMemo(project.prj_id, initial.memo_id, input)
          : client.saveProjectMemo(project.prj_id, input),
      onSaved,
    );
  };
  return (
    <Modal
      title={initial ? "이번 주 상태 고치기" : "이번 주 상태 적기"}
      onCancel={() => {
        if (!write.pending) onCancel();
      }}
    >
      <form
        ref={form}
        noValidate
        className={styles.form}
        data-testid="project-memo-form"
        data-screen-id="SCR-UI-01-M11"
        onSubmit={(e) => {
          e.preventDefault();
          save();
        }}
      >
        <p>
          {project.prj_nm} · {initial?.wrt_dt ?? memoToday()}
        </p>
        <ErrorBanner
          error={write.error}
          pending={write.pending}
          onRetry={() => void write.recover()}
        />
        {manual ? (
          <>
            <TextInput
              label="실적 진척률"
              name="actl_prgs_rt"
              required
              value={rate}
              onValueChange={changed(setRate)}
              error={fieldError("actl_prgs_rt")}
              disabled={blocked}
            />
            <small>계획 진척률은 {plannedRate}%입니다.</small>
            {previousRate != null &&
              rate.trim() !== "" &&
              Number(rate) < previousRate && (
                <p className={styles.warning}>
                  지난 메모({previousRate}%)보다 낮습니다.
                </p>
              )}
          </>
        ) : (
          <p className={styles.info}>
            운영형(월단위) 프로젝트는 진척률을 기간 기준으로 자동 계산합니다.
          </p>
        )}
        <TextareaInput
          label="이번 주 한 일"
          name="cmpl_work_desc"
          required
          maxLength={200}
          value={completed}
          onValueChange={changed(setCompleted)}
          error={fieldError("cmpl_work_desc")}
          disabled={blocked}
        />
        <TextareaInput
          label="다음 주 할 일"
          name="plan_work_desc"
          required
          maxLength={200}
          value={planned}
          onValueChange={changed(setPlanned)}
          error={fieldError("plan_work_desc")}
          disabled={blocked}
        />
        <TextareaInput
          label="요청 사항"
          name="rqst_desc"
          maxLength={200}
          value={request}
          onValueChange={changed(setRequest)}
          error={fieldError("rqst_desc")}
          disabled={blocked}
        />
        <small>없으면 비워 두세요</small>
        <div className={styles.actions}>
          <Button
            type="button"
            data-testid="project-memo-cancel"
            disabled={write.pending}
            onClick={onCancel}
          >
            취소
          </Button>
          <Can perm="UI-01.update">
            <Button
              type="submit"
              variant="primary"
              data-testid="project-memo-save"
              pending={write.pending}
              disabled={blocked}
            >
              저장
            </Button>
          </Can>
        </div>
      </form>
    </Modal>
  );
}
