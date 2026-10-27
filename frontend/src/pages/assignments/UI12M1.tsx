import { useCallback, useEffect, useRef, useState } from "react";
import { Button, Modal, Skeleton } from "../../shared/Controls";
import {
  TextInput,
  SelectInput,
  MoneyInput,
  TextareaInput,
} from "../../shared/Inputs";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { failure } from "../admin/client";
import { useCodes } from "../shared/useCodes";
import { listProjectOptions, getProject } from "../projects/client";
import { listDeveloperOptions } from "../developersops/client";
import { getDeveloper } from "../developers/client";
import { getMonthClose } from "../shared/api";
import {
  createAssignment,
  checkAssignments,
  type AssignmentInput,
  type AssignmentSave,
} from "./client";
import { AssignmentWarnings } from "./UI12DetailPanel";
import styles from "./Assignments.module.css";

const developers = () => listDeveloperOptions();
export function UI12M1({
  projectId,
  onCancel,
  onSaved,
  reload,
  onDirtyChange,
}: {
  projectId?: number;
  onCancel: () => void;
  onSaved: (result: AssignmentSave) => void;
  reload: () => Promise<unknown>;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const [draft, setDraft] = useState<AssignmentInput>({
    prj_id: projectId ?? 0,
    dev_id: 0,
    job_cd: "",
    bgn_dt: "",
    end_dt: "",
    asgn_rt: 1,
    asgn_se_cd: "NEW",
    bill_untprc_amt: null,
    untprc_base_cd: null,
    cntrct_dev_id: null,
    ext_psblty_cd: null,
    rmrk_desc: null,
  });
  const [dirty, setDirty] = useState(false),
    [leaving, setLeaving] = useState(false),
    [local, setLocal] = useState<{ field: string; message: string } | null>(
      null,
    );
  const manualJob = useRef(false),
    selectedDeveloper = useRef(0);
  const projects = useAdminRead(listProjectOptions),
    people = useAdminRead(developers);
  const project = useAdminRead(
    useCallback(
      () => (draft.prj_id ? getProject(draft.prj_id) : Promise.resolve(null)),
      [draft.prj_id],
    ),
  );
  const jobs = useCodes("JOB"),
    kinds = useCodes("ASSIGN_KIND"),
    bases = useCodes("RATE_BASIS"),
    chances = useCodes("EXT_POSSIBILITY"),
    reasons = useCodes("ASGN_CHG_RSN");
  const write = useAdminWrite(reload);
  const priced =
    project.data?.cntrct_ty_cd === "MO" || project.data?.cntrct_ty_cd === "DP";
  useEffect(() => {
    onDirtyChange?.(dirty);
    return () => onDirtyChange?.(false);
  }, [dirty, onDirtyChange]);
  useEffect(() => {
    if (!project.data) return;
    const p = project.data;
    setDraft((d) => ({
      ...d,
      bill_untprc_amt:
        p.cntrct_ty_cd === "TK" || p.cntrct_ty_cd === "IN"
          ? null
          : d.bill_untprc_amt,
      untprc_base_cd:
        p.cntrct_ty_cd === "TK" || p.cntrct_ty_cd === "IN"
          ? null
          : p.stlm_mthd_cd,
    }));
  }, [project.data]);
  const closes = useAdminRead(
    useCallback(
      () =>
        draft.bgn_dt
          ? getMonthClose(
              draft.bgn_dt.slice(0, 7),
              draft.end_dt.slice(0, 7) || draft.bgn_dt.slice(0, 7),
            )
          : Promise.resolve([]),
      [draft.bgn_dt, draft.end_dt],
    ),
  );
  const complete = !!(
    draft.prj_id &&
    draft.dev_id &&
    draft.job_cd &&
    draft.bgn_dt &&
    draft.end_dt &&
    draft.end_dt >= draft.bgn_dt &&
    draft.asgn_rt > 0 &&
    draft.asgn_rt <= 1 &&
    (!priced || (draft.bill_untprc_amt && draft.untprc_base_cd)) &&
    (draft.asgn_se_cd !== "SUB" || draft.cntrct_dev_id)
  );
  const preview = useAdminRead(
    useCallback(
      () => (complete ? checkAssignments(draft) : Promise.resolve(null)),
      [complete, draft],
    ),
  );
  const error = local ?? write.error;
  const fieldError = (key: string) =>
    error?.field === key ? error.message : null;
  const change = <K extends keyof AssignmentInput>(
    key: K,
    value: AssignmentInput[K],
  ) => {
    setDirty(true);
    setLocal(null);
    write.clear();
    setDraft((d) => ({
      ...d,
      [key]: value,
      ...(key === "asgn_se_cd" && value !== "SUB"
        ? { cntrct_dev_id: null }
        : {}),
    }));
  };
  const pickDeveloper = async (id: number) => {
    selectedDeveloper.current = id;
    change("dev_id", id);
    if (!id) return;
    try {
      const person = await getDeveloper(id);
      if (selectedDeveloper.current === id && !manualJob.current)
        setDraft((d) => ({ ...d, job_cd: person.main_job_cd }));
    } catch (e) {
      setLocal({ field: "dev_id", message: failure(e).message });
    }
  };
  const cancel = () => {
    if (!write.pending) {
      if (dirty) setLeaving(true);
      else onCancel();
    }
  };
  const options = (codes: typeof jobs) =>
    codes.codes.map((c) => ({ value: c.code, label: c.name }));
  return (
    <div data-testid="assignment-create-root" className={styles.scrim}>
      <aside
        role="dialog"
        aria-modal="true"
        aria-label="배정"
        className={styles.drawer}
        data-testid="assignment-create"
      >
        <header>
          <h2>배정</h2>
          <Button
            data-testid="assignment-create-close"
            onClick={cancel}
            disabled={write.pending}
          >
            닫기
          </Button>
        </header>
        <form
          data-testid="assignment-create-form"
          className={styles.form}
          noValidate
          onSubmit={(e) => {
            e.preventDefault();
            if (!complete) {
              setLocal({
                field: !draft.prj_id
                  ? "prj_id"
                  : !draft.dev_id
                    ? "dev_id"
                    : !draft.job_cd
                      ? "job_cd"
                      : !draft.bgn_dt
                        ? "bgn_dt"
                        : draft.end_dt < draft.bgn_dt || !draft.end_dt
                          ? "end_dt"
                          : draft.asgn_rt <= 0 || draft.asgn_rt > 1
                            ? "asgn_rt"
                            : draft.asgn_se_cd === "SUB" && !draft.cntrct_dev_id
                              ? "cntrct_dev_id"
                              : "bill_untprc_amt",
                message: "필수 항목과 입력 범위를 확인하세요.",
              });
              return;
            }
            let result: AssignmentSave;
            void write.save(
              async () => {
                result = await createAssignment(draft);
              },
              () => onSaved(result),
            );
          }}
        >
          <ErrorBanner
            error={write.error}
            onRetry={() => void write.recover()}
          />
          {[
            projects,
            people,
            project,
            closes,
            preview,
            jobs,
            kinds,
            bases,
            chances,
            reasons,
          ].map((s, i) => (
            <ErrorBanner key={i} error={s.error} />
          ))}
          {preview.data && (
            <AssignmentWarnings warnings={preview.data.warnings} />
          )}
          {closes.data?.some((c) => c.status === "CLOSED") && (
            <div className={styles.warning}>
              마감한 달의 MM은 만들지 않습니다. 첫 미마감월부터 반영합니다.
            </div>
          )}
          {projects.loading || people.loading ? (
            <Skeleton />
          ) : (
            <>
              <SelectInput
                name="prj_id"
                label="프로젝트"
                required
                value={String(draft.prj_id || "")}
                options={(projects.data ?? []).map((p) => ({
                  value: String(p.id),
                  label: `${p.no} · ${p.name}`,
                }))}
                error={fieldError("prj_id")}
                onValueChange={(v) => change("prj_id", Number(v))}
              />
              <SelectInput
                name="dev_id"
                label="개발자"
                required
                value={String(draft.dev_id || "")}
                options={(people.data ?? []).map((p) => ({
                  value: String(p.id),
                  label: `${p.name} · ${p.mngNo}`,
                }))}
                error={fieldError("dev_id")}
                onValueChange={(v) => void pickDeveloper(Number(v))}
              />
              <SelectInput
                name="job_cd"
                label="직무"
                required
                value={draft.job_cd}
                options={options(jobs)}
                error={fieldError("job_cd")}
                onValueChange={(v) => {
                  manualJob.current = true;
                  change("job_cd", v);
                }}
              />
              <div className={styles.fields}>
                <TextInput
                  name="bgn_dt"
                  label="투입일"
                  type="date"
                  required
                  value={draft.bgn_dt}
                  error={fieldError("bgn_dt")}
                  onValueChange={(v) => change("bgn_dt", v)}
                />
                <TextInput
                  name="end_dt"
                  label="철수일"
                  type="date"
                  required
                  value={draft.end_dt}
                  error={fieldError("end_dt")}
                  onValueChange={(v) => change("end_dt", v)}
                />
              </div>
              <TextInput
                name="asgn_rt"
                label="투입률"
                required
                value={String(draft.asgn_rt)}
                error={fieldError("asgn_rt")}
                onValueChange={(v) => change("asgn_rt", Number(v))}
              />
              <MoneyInput
                name="bill_untprc_amt"
                label="청구 단가"
                required={priced}
                disabled={!priced}
                value={draft.bill_untprc_amt}
                error={fieldError("bill_untprc_amt")}
                onValueChange={(v) => change("bill_untprc_amt", v)}
              />
              <SelectInput
                name="untprc_base_cd"
                label="단가 기준"
                required={priced}
                disabled={!priced}
                value={draft.untprc_base_cd ?? ""}
                options={options(bases)}
                error={fieldError("untprc_base_cd")}
                onValueChange={(v) => change("untprc_base_cd", v || null)}
              />
              <SelectInput
                name="asgn_se_cd"
                label="투입 구분"
                required
                value={draft.asgn_se_cd}
                options={options(kinds)}
                onValueChange={(v) => change("asgn_se_cd", v)}
              />
              {draft.asgn_se_cd === "SUB" && (
                <SelectInput
                  name="cntrct_dev_id"
                  label="계약상 인력"
                  required
                  value={String(draft.cntrct_dev_id ?? "")}
                  options={(people.data ?? []).map((p) => ({
                    value: String(p.id),
                    label: `${p.name} · ${p.mngNo}`,
                  }))}
                  error={fieldError("cntrct_dev_id")}
                  onValueChange={(v) =>
                    change("cntrct_dev_id", v ? Number(v) : null)
                  }
                />
              )}
              <SelectInput
                name="ext_psblty_cd"
                label="연장 가능성"
                value={draft.ext_psblty_cd ?? ""}
                options={options(chances)}
                onValueChange={(v) => change("ext_psblty_cd", v || null)}
              />
              <TextareaInput
                name="rmrk_desc"
                label="비고"
                maxLength={500}
                value={draft.rmrk_desc ?? ""}
                error={fieldError("rmrk_desc")}
                onValueChange={(v) => change("rmrk_desc", v || null)}
              />
              <SelectInput
                name="rsn_cd"
                label="사유 코드"
                value={draft.rsn_cd ?? ""}
                options={options(reasons)}
                error={fieldError("rsn_cd")}
                onValueChange={(v) => change("rsn_cd", v || undefined)}
              />
              <TextareaInput
                name="rsn_desc"
                label="사유 설명"
                maxLength={200}
                value={draft.rsn_desc ?? ""}
                error={fieldError("rsn_desc")}
                onValueChange={(v) => change("rsn_desc", v || null)}
              />
              <p>
                소급 배정 또는 마지막 마감월 이하의 투입일에는 사유 코드를
                선택하세요.
              </p>
            </>
          )}
          {local && <p role="alert">{local.message}</p>}
          <div className={styles.actions}>
            <Button
              data-testid="assignment-create-cancel"
              type="button"
              onClick={cancel}
              disabled={write.pending}
            >
              취소
            </Button>
            <Button
              data-testid="assignment-create-save"
              type="submit"
              variant="primary"
              pending={write.pending}
              disabled={
                write.uncertain ||
                projects.loading ||
                people.loading ||
                project.loading ||
                !project.data ||
                !!project.error
              }
            >
              저장
            </Button>
          </div>
        </form>
        {leaving && (
          <Modal
            title="작성 중인 내용을 버릴까요?"
            confirmation
            onCancel={() => setLeaving(false)}
          >
            <Button
              data-testid="assignment-keep"
              onClick={() => setLeaving(false)}
            >
              계속 작성
            </Button>
            <Button data-testid="assignment-discard" onClick={onCancel}>
              나가기
            </Button>
          </Modal>
        )}
      </aside>
    </div>
  );
}
