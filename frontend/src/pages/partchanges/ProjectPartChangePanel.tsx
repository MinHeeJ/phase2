import { useCallback, useEffect, useRef, useState } from "react";
import {
  Badge,
  Button,
  LockedBanner,
  Modal,
  Skeleton,
  SuccessToast,
} from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import { isMonth } from "../../lib/format";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { useAdminGuard } from "../admin/AdminDialogs";
import { getMonthClose } from "../shared/api";
import { seoulMonth } from "../shared/PeriodBar";
import { useSharedMobile } from "../shared/PlaceholderScreen";
import { listSalesPartOptions } from "../salespartsops/client";
import {
  createProjectPartChange,
  listProjectPartChanges,
  type PartChangeProjectView,
} from "./client";
import fields from "../../shared/Inputs.module.css";
import styles from "./PartChanges.module.css";

export function ProjectPartChangePanel({
  project,
  onChanged,
}: {
  project: PartChangeProjectView;
  onChanged: () => Promise<unknown>;
}) {
  const read = useCallback(
    () => listProjectPartChanges(project.prj_id),
    [project.prj_id],
  );
  const resource = useAdminRead(read);
  const mobile = useSharedMobile();
  const [open, setOpen] = useState(false),
    [message, setMessage] = useState("");
  const canChange =
    project.cntrct_ty_cd !== "IN" && project.use_yn === "Y" && !mobile;
  useEffect(() => {
    setOpen(false);
    setMessage("");
  }, [project.prj_id]);
  useEffect(() => {
    if (!canChange) setOpen(false);
  }, [canChange]);
  const refresh = async () => {
    await resource.reload();
    await onChanged();
  };
  return (
    <section data-screen-id="SCR-UI-01" data-testid="project-part-change-panel">
      <div className={styles.heading}>
        <h3>영업 파트</h3>
        {canChange && (
          <Can perm="UI-01.update">
            <Button
              small
              data-testid="project-part-change-open"
              onClick={() => setOpen(true)}
            >
              변경
            </Button>
          </Can>
        )}
      </div>
      <p>
        대표 파트:{" "}
        {project.cntrct_ty_cd === "IN"
          ? "없음"
          : (project.partName ?? "미지정")}
      </p>
      {project.use_yn === "N" && (
        <LockedBanner>
          사용 안 함 프로젝트입니다. 다시 사용한 뒤 변경할 수 있습니다.
        </LockedBanner>
      )}
      <ErrorBanner
        error={resource.error}
        onRetry={() => {
          void resource.reload().catch(() => {});
        }}
      />
      {resource.loading && <Skeleton />}
      {!resource.loading && !resource.error && resource.data?.length === 0 && (
        <p className={styles.empty}>등록된 영업 파트 이력이 없습니다.</p>
      )}
      {resource.data && resource.data.length > 0 && (
        <ol className={styles.history} aria-label="영업 파트 적용 이력">
          {resource.data.map((row) => (
            <li
              key={row.aply_bgn_ym}
              data-testid={`project-part-change-row-${row.aply_bgn_ym}`}
            >
              <span>
                {row.aply_bgn_ym} {row.partName ?? "미지정"}
              </span>
              {row.aply_bgn_ym > seoulMonth() && (
                <Badge tone="info">적용 예정 · {row.aply_bgn_ym}부터</Badge>
              )}
              {row.rsn_desc && <small>{row.rsn_desc}</small>}
            </li>
          ))}
        </ol>
      )}
      {open && canChange && (
        <ProjectPartChangeDialog
          key={project.prj_id}
          project={project}
          reload={refresh}
          onCancel={() => setOpen(false)}
          onSaved={(text) => {
            setOpen(false);
            setMessage(text);
          }}
        />
      )}
      {message && (
        <SuccessToast message={message} onDismiss={() => setMessage("")} />
      )}
    </section>
  );
}

export function ProjectPartChangeDialog({
  project,
  reload,
  onCancel,
  onSaved,
}: {
  project: PartChangeProjectView;
  reload: () => Promise<unknown>;
  onCancel: () => void;
  onSaved: (message: string) => void;
}) {
  const readChoices = useCallback(async () => {
    const [parts, months] = await Promise.all([
      listSalesPartOptions(),
      getMonthClose("0001-01", "9999-12"),
    ]);
    const closed =
      months
        .filter((row) => row.status === "CLOSED")
        .map((row) => row.month)
        .sort()
        .at(-1) ?? null;
    let first = seoulMonth();
    if (closed) {
      const [year, month] = closed.split("-").map(Number);
      first =
        month === 12
          ? `${String(year + 1).padStart(4, "0")}-01`
          : `${year}-${String(month + 1).padStart(2, "0")}`;
    }
    return {
      parts: parts.filter((p) => String(p.id) !== String(project.bsn_part_id)),
      closed,
      first,
    };
  }, [project.bsn_part_id]);
  const choices = useAdminRead(readChoices);
  const [draft, setDraft] = useState({
    bsn_part_id: "",
    aply_bgn_ym: "",
    rsn_desc: "",
  });
  const [dirty, setDirty] = useState(false),
    [validation, setValidation] = useState<ApiFailure | null>(null);
  const initialized = useRef(false),
    form = useRef<HTMLFormElement>(null);
  const write = useAdminWrite(reload),
    guard = useAdminGuard(dirty);
  const mobile = useSharedMobile();
  useEffect(() => {
    if (choices.data && !initialized.current) {
      initialized.current = true;
      setDraft((old) => ({ ...old, aply_bgn_ym: choices.data!.first }));
    }
  }, [choices.data]);
  const error = validation ?? write.error;
  useEffect(() => {
    if (error?.field)
      (
        form.current?.elements.namedItem(error.field) as HTMLElement | null
      )?.focus();
  }, [error]);
  const change = (key: keyof typeof draft, value: string) => {
    setDirty(true);
    setValidation(null);
    write.clear();
    setDraft((old) => ({ ...old, [key]: value }));
  };
  const invalid = (field: string, text: string) =>
    setValidation(new ApiFailure(text, 400, null, field));
  const submit = () => {
    if (
      write.pending ||
      write.uncertain ||
      choices.loading ||
      !choices.data ||
      choices.error ||
      mobile ||
      project.use_yn === "N" ||
      project.cntrct_ty_cd === "IN"
    )
      return;
    const part = choices.data.parts.find(
      (p) => String(p.id) === draft.bsn_part_id,
    );
    if (!part) return invalid("bsn_part_id", "새 영업 파트를 골라 주세요.");
    if (!isMonth(draft.aply_bgn_ym))
      return invalid("aply_bgn_ym", "올바른 연월을 입력하세요.");
    if (choices.data.closed && draft.aply_bgn_ym <= choices.data.closed)
      return invalid(
        "aply_bgn_ym",
        `적용 시작 월은 ${choices.data.closed} 이후로 골라 주세요.`,
      );
    if (!draft.rsn_desc.trim())
      return invalid("rsn_desc", "사유를 입력해 주세요.");
    if (draft.rsn_desc.trim().length > 200)
      return invalid("rsn_desc", "사유는 200자 이하로 입력해 주세요.");
    void write.save(
      () =>
        createProjectPartChange(project.prj_id, {
          bsn_part_id: Number(part.id),
          aply_bgn_ym: draft.aply_bgn_ym,
          rsn_desc: draft.rsn_desc,
        }),
      () =>
        onSaved(
          `${part.hq ? "본사(직접 영업)" : part.name} 파트로 변경했습니다. ${draft.aply_bgn_ym}부터 적용됩니다.`,
        ),
    );
  };
  const fieldError = (key: string) =>
    error?.field === key && (
      <span
        id={`project-part-error-${key}`}
        className={fields.error}
        role="alert"
      >
        {error.message}
      </span>
    );
  if (mobile || project.use_yn === "N" || project.cntrct_ty_cd === "IN")
    return null;
  return (
    <>
      <Modal
        title="영업 파트 변경"
        onCancel={() => {
          if (!write.pending) guard.request(onCancel);
        }}
      >
        <form
          ref={form}
          data-screen-id="SCR-UI-01-M3"
          data-testid="project-part-change-dialog"
          className={styles.form}
          noValidate
          onSubmit={(e) => {
            e.preventDefault();
            submit();
          }}
        >
          {choices.loading && <Skeleton label="변경 정보를 불러오는 중" />}
          <ErrorBanner
            error={choices.error}
            onRetry={() => {
              void choices.reload().catch(() => {});
            }}
          />
          {choices.data && (
            <p className={styles.notice}>
              「{project.prj_nm}」의 영업 파트를 바꿉니다. 적용 시작 월부터 새
              파트로 잡히고,{" "}
              {choices.data.closed
                ? `마감한 달(${choices.data.closed}까지)의 실적·정산은 바뀌지 않습니다.`
                : "마감한 달이 없습니다."}
            </p>
          )}
          <div className={fields.field}>
            <label htmlFor="project-part-choice">새 영업 파트 *</label>
            <div className={fields.control}>
              <select
                id="project-part-choice"
                name="bsn_part_id"
                aria-label="새 영업 파트"
                data-testid="project-part-choice"
                className={fields.input}
                required
                value={draft.bsn_part_id}
                disabled={choices.loading || write.pending || !!choices.error}
                aria-invalid={error?.field === "bsn_part_id"}
                aria-describedby={
                  error?.field === "bsn_part_id"
                    ? "project-part-error-bsn_part_id"
                    : undefined
                }
                onChange={(e) => change("bsn_part_id", e.target.value)}
              >
                <option value="">선택해 주세요</option>
                {choices.data?.parts.map((part) => (
                  <option value={part.id} key={part.id}>
                    {part.hq ? "본사(직접 영업)" : part.name}
                  </option>
                ))}
              </select>
              {fieldError("bsn_part_id")}
              {!choices.loading && choices.data?.parts.length === 0 && (
                <small>선택할 수 있는 다른 영업 파트가 없습니다.</small>
              )}
            </div>
          </div>
          <div className={fields.field}>
            <label htmlFor="project-part-month">적용 시작 월 *</label>
            <div className={fields.control}>
              <input
                id="project-part-month"
                name="aply_bgn_ym"
                aria-label="적용 시작 월"
                data-testid="project-part-month"
                type="month"
                className={fields.input}
                required
                min={choices.data?.closed ? choices.data.first : undefined}
                value={draft.aply_bgn_ym}
                disabled={choices.loading || write.pending}
                aria-invalid={error?.field === "aply_bgn_ym"}
                aria-describedby="project-part-month-help project-part-error-aply_bgn_ym"
                onChange={(e) => change("aply_bgn_ym", e.target.value)}
              />
              <small id="project-part-month-help">
                {choices.data?.closed
                  ? `마감 안 된 달(${choices.data.first})부터 고를 수 있습니다.`
                  : "마감 이력이 없습니다. 적용 시작 월을 골라 주세요."}
              </small>
              {fieldError("aply_bgn_ym")}
            </div>
          </div>
          <div className={fields.field}>
            <label htmlFor="project-part-reason">사유 *</label>
            <div className={fields.control}>
              <textarea
                id="project-part-reason"
                name="rsn_desc"
                aria-label="사유"
                data-testid="project-part-reason"
                className={fields.input}
                required
                maxLength={200}
                value={draft.rsn_desc}
                disabled={write.pending}
                aria-invalid={error?.field === "rsn_desc"}
                aria-describedby={
                  error?.field === "rsn_desc"
                    ? "project-part-error-rsn_desc"
                    : undefined
                }
                onChange={(e) => change("rsn_desc", e.target.value)}
              />
              <small className={fields.counter}>
                {draft.rsn_desc.length}/200
              </small>
              {fieldError("rsn_desc")}
            </div>
          </div>
          <ErrorBanner
            error={write.error}
            onRetry={() => {
              void write.recover();
            }}
            pending={write.pending}
          />
          <div className={styles.actions}>
            <Button
              type="button"
              data-testid="project-part-cancel"
              disabled={write.pending}
              onClick={() => guard.request(onCancel)}
            >
              취소
            </Button>
            <Button
              type="submit"
              data-testid="project-part-save"
              variant="primary"
              pending={write.pending}
              disabled={
                choices.loading ||
                !!choices.error ||
                !choices.data?.parts.length ||
                write.uncertain
              }
            >
              변경
            </Button>
          </div>
        </form>
      </Modal>
      {guard.confirmation}
    </>
  );
}
