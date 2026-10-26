import { useCallback, useEffect, useState } from "react";
import {
  Badge,
  Button,
  LockedBanner,
  Modal,
  Skeleton,
  SuccessToast,
} from "../../shared/Controls";
import { TextInput } from "../../shared/Inputs";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { Can } from "../../shared/Can";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import * as client from "./client";
import type { ProjectPhase, ProjectPhaseProject } from "./client";
import styles from "./ProjectPhases.module.css";

type PhaseAction = {
  row: ProjectPhase;
  kind: "complete" | "uncomplete" | "period";
};

// SCR-UI-01: embedded in the progress tab; the progress owner can pass its existing phases to ProjectPhaseTable.
export function ProjectPhasePanel({
  project,
  onChanged,
}: {
  project: ProjectPhaseProject;
  onChanged: () => Promise<unknown>;
}) {
  const read = useCallback(
    () => client.getProjectPhaseProgress(project.prj_id),
    [project.prj_id],
  );
  const state = useAdminRead(read);
  const refresh = async () => {
    await state.reload();
    await onChanged();
  };
  return (
    <section data-testid="project-phase-panel" data-screen-id="SCR-UI-01">
      <ErrorBanner
        error={state.error}
        onRetry={() => void state.reload().catch(() => {})}
      />
      {state.loading && !state.data ? (
        <Skeleton />
      ) : (
        state.data && (
          <ProjectPhaseTable
            project={project}
            phases={state.data.phases}
            onChanged={refresh}
          />
        )
      )}
    </section>
  );
}

export function ProjectPhaseTable({
  project,
  phases,
  onChanged,
}: {
  project: ProjectPhaseProject;
  phases: ProjectPhase[];
  onChanged: () => Promise<unknown>;
}) {
  const mobile = useDeveloperReadOnly();
  const writable =
    project.use_yn === "Y" &&
    ["PLAN", "RUN", "HOLD"].includes(project.prj_stts_cd);
  const [action, setAction] = useState<PhaseAction | null>(null);
  const [message, setMessage] = useState("");
  const today = new Intl.DateTimeFormat("sv-SE", {
    timeZone: "Asia/Seoul",
  }).format(new Date());
  useEffect(() => {
    setAction(null);
    setMessage("");
  }, [project.prj_id, writable]);
  useEffect(() => {
    if (mobile) setAction((a) => (a?.kind === "period" ? null : a));
  }, [mobile]);
  return (
    <section data-testid="project-phase-table" data-screen-id="SCR-UI-01">
      <h3>단계</h3>
      {!writable && (
        <LockedBanner>
          입력할 수 없는 프로젝트입니다. 조회만 가능합니다.
        </LockedBanner>
      )}
      {message && (
        <SuccessToast message={message} onDismiss={() => setMessage("")} />
      )}
      {phases.length === 0 ? (
        <p className={styles.empty}>단계 표가 없습니다.</p>
      ) : (
        <div className={styles.tableScroll}>
          <table className={styles.table}>
            <thead>
              <tr>
                {["단계", "비중", "예정 기간", "완료일", "상태", "작업"].map(
                  (c) => (
                    <th
                      data-testid={`project-phase-column-${["단계", "비중", "예정 기간", "완료일", "상태", "작업"].indexOf(c)}`}
                      key={c}
                    >
                      {c}
                    </th>
                  ),
                )}
              </tr>
            </thead>
            <tbody>
              {phases.map((row) => (
                <tr
                  key={row.phs_id}
                  data-testid={`project-phase-row-${row.phs_id}`}
                >
                  <td>{row.phs_nm}</td>
                  <td>{row.wght_rt}%</td>
                  <td>
                    {row.plan_bgn_dt ?? "–"} ~ {row.plan_end_dt ?? "–"}
                  </td>
                  <td>{row.cmpl_dt ?? "–"}</td>
                  <td>
                    <Badge
                      tone={
                        row.status === "완료"
                          ? "success"
                          : row.status === "지연"
                            ? "danger"
                            : row.status === "진행 중"
                              ? "info"
                              : "neutral"
                      }
                    >
                      {row.status}
                    </Badge>
                  </td>
                  <td>
                    {writable && (
                      <Can perm="UI-01.update">
                        <div className={styles.actions}>
                          {row.cmpl_dt ? (
                            <Button
                              small
                              data-testid={`phase-uncomplete-${row.phs_id}`}
                              onClick={() =>
                                setAction({ row, kind: "uncomplete" })
                              }
                            >
                              완료 취소
                            </Button>
                          ) : (
                            <>
                              {row.plan_bgn_dt && row.plan_bgn_dt <= today && (
                                <Button
                                  small
                                  data-testid={`phase-complete-${row.phs_id}`}
                                  onClick={() =>
                                    setAction({ row, kind: "complete" })
                                  }
                                >
                                  완료
                                </Button>
                              )}
                              {!mobile && (
                                <Button
                                  small
                                  data-testid={`phase-period-${row.phs_id}`}
                                  onClick={() =>
                                    setAction({ row, kind: "period" })
                                  }
                                >
                                  기간 수정
                                </Button>
                              )}
                            </>
                          )}
                        </div>
                      </Can>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {writable && action && (
        <ProjectPhaseDialog
          key={`${project.prj_id}-${action.row.phs_id}-${action.kind}`}
          project={project}
          action={action}
          reload={onChanged}
          onCancel={() => setAction(null)}
          onSaved={() => {
            setMessage(
              action.kind === "complete"
                ? `${action.row.phs_nm} 단계를 완료했습니다.`
                : action.kind === "uncomplete"
                  ? `${action.row.phs_nm} 단계 완료를 취소했습니다.`
                  : "예정 기간을 저장했습니다.",
            );
            setAction(null);
          }}
        />
      )}
    </section>
  );
}

export function ProjectPhaseDialog({
  project,
  action,
  reload,
  onCancel,
  onSaved,
}: {
  project: ProjectPhaseProject;
  action: PhaseAction;
  reload: () => Promise<unknown>;
  onCancel: () => void;
  onSaved: () => void;
}) {
  const period = action.kind === "period";
  const complete = action.kind === "complete";
  const [start, setStart] = useState(action.row.plan_bgn_dt ?? "");
  const [end, setEnd] = useState(action.row.plan_end_dt ?? "");
  const [localError, setLocalError] = useState<{
    field: string;
    message: string;
  } | null>(null);
  const write = useAdminWrite(reload);
  const error = localError ?? write.error;
  const cancel = () => {
    if (!write.pending) onCancel();
  };
  const blocked = write.pending || write.uncertain;
  const save = () => {
    if (period && (!start || !end || end < start)) {
      setLocalError({
        field: !start ? "plan_bgn_dt" : "plan_end_dt",
        message:
          !start || !end
            ? "예정 시작일과 종료일을 입력하세요."
            : "예정 종료일은 시작일과 같거나 뒤여야 합니다.",
      });
      return;
    }
    void write.save(async () => {
      try {
        if (period)
          await client.updateProjectPhase(project.prj_id, action.row.phs_id, {
            plan_bgn_dt: start,
            plan_end_dt: end,
          });
        else if (complete)
          await client.updateProjectPhaseCompletion(
            project.prj_id,
            action.row.phs_id,
          );
        else
          await client.updateProjectPhaseUncompletion(
            project.prj_id,
            action.row.phs_id,
          );
      } catch (e) {
        if (e instanceof Error && "code" in e && e.code === "ALREADY_DONE")
          await reload().catch(() => {});
        throw e;
      }
    }, onSaved);
  };
  return (
    <Modal
      title={
        period
          ? `단계 예정 기간 수정 — ${action.row.phs_nm}`
          : complete
            ? `「${action.row.phs_nm}」 단계를 완료로 바꿀까요?`
            : "완료를 취소할까요?"
      }
      confirmation={!period}
      onCancel={cancel}
    >
      <form
        noValidate
        className={styles.form}
        data-testid="project-phase-dialog"
        data-screen-id={
          period
            ? "SCR-UI-01-M14"
            : complete
              ? "SCR-UI-01-M12"
              : "SCR-UI-01-M13"
        }
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
        {period ? (
          <>
            <TextInput
              name="plan_bgn_dt"
              label="예정 시작일"
              type="date"
              required
              value={start}
              disabled={blocked}
              error={error?.field === "plan_bgn_dt" ? error.message : null}
              onValueChange={(v) => {
                setStart(v);
                setLocalError(null);
                write.clear();
              }}
            />
            <TextInput
              name="plan_end_dt"
              label="예정 종료일"
              type="date"
              required
              value={end}
              disabled={blocked}
              error={error?.field === "plan_end_dt" ? error.message : null}
              onValueChange={(v) => {
                setEnd(v);
                setLocalError(null);
                write.clear();
              }}
            />
          </>
        ) : (
          <p>
            {complete
              ? "완료일은 서버 기준 오늘(Asia/Seoul)로 들어갑니다."
              : action.row.phs_nm}
          </p>
        )}
        <div className={styles.actions}>
          <Button
            type="button"
            data-testid="phase-dialog-cancel"
            disabled={write.pending}
            onClick={cancel}
          >
            취소
          </Button>
          <Button
            type="submit"
            variant="primary"
            data-testid="phase-dialog-submit"
            pending={write.pending}
            disabled={write.uncertain}
          >
            {period ? "저장" : complete ? "완료" : "완료 취소"}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
