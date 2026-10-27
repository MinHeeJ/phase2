import { useCallback, useEffect, useRef, useState } from "react";
import { Button, Modal } from "../../shared/Controls";
import { TextInput, SelectInput, TextareaInput } from "../../shared/Inputs";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { Can } from "../../shared/Can";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import * as client from "./client";
import type { IssueInput, IssueProject, IssueRow } from "./client";
import styles from "./Issues.module.css";

// SCR-UI-01-M15, SCR-UI-01-M16 and SCR-UI-31-M1 share one immutable-identity editor.
export function IssueForm({
  project,
  initial,
  reload,
  onSaved,
  onCancel,
}: {
  project?: IssueProject;
  initial?: IssueRow;
  reload: () => Promise<unknown>;
  onSaved: (row: IssueRow) => void;
  onCancel: () => void;
}) {
  const [projectId, setProjectId] = useState<number | null>(
    initial?.prj_id ?? project?.prj_id ?? null,
  );
  const [draft, setDraft] = useState<IssueInput>(() =>
    initial ? client.issuePayload(initial) : client.issueDefaults(),
  );
  const [ownerMode, setOwnerMode] = useState(initial?.chrg_nm ? "direct" : "");
  const projects = useAdminRead(
    useCallback(
      () =>
        project
          ? Promise.resolve([
              { prj_id: project.prj_id, prj_nm: project.prj_nm },
            ])
          : client.issueBoardProjects(),
      [project],
    ),
  );
  const owners = useAdminRead(
    useCallback(
      () =>
        projectId
          ? client.getIssueOwnerOptions(projectId)
          : Promise.resolve([]),
      [projectId],
    ),
  );
  const write = useAdminWrite(reload);
  const form = useRef<HTMLFormElement>(null);
  const blocked = write.pending || write.uncertain;
  const writable = !project || client.issueWritable(project);
  const fieldError = (key: string) =>
    write.error?.field === key ? write.error.message : null;
  useEffect(() => {
    if (write.error?.field)
      form.current
        ?.querySelector<HTMLElement>(
          `[name="${write.error.field.replace(/[^a-z_]/g, "")}"]`,
        )
        ?.focus();
  }, [write.error]);
  const change = <K extends keyof IssueInput>(key: K, value: IssueInput[K]) => {
    write.clear();
    setDraft((current) => ({ ...current, [key]: value }));
  };
  const cancel = () => {
    if (!write.pending) onCancel();
  };
  return (
    <Modal title={initial ? "이슈 수정" : "이슈 등록"} onCancel={cancel}>
      <form
        ref={form}
        noValidate
        className={styles.form}
        data-testid="issue-form"
        data-screen-id={
          initial ? "SCR-UI-01-M16" : project ? "SCR-UI-01-M15" : "SCR-UI-31-M1"
        }
        onSubmit={(event) => {
          event.preventDefault();
          if (!writable) return;
          let result: IssueRow;
          void write.save(
            async () => {
              result = initial
                ? await client.updateIssue(initial.issue_id, draft)
                : await client.createIssue(projectId, draft);
            },
            () => onSaved(result),
          );
        }}
      >
        <ErrorBanner
          error={write.error}
          pending={write.pending}
          onRetry={() => void write.recover()}
        />
        <ErrorBanner
          error={projects.error}
          onRetry={() => void projects.reload().catch(() => {})}
        />
        {project || initial ? (
          <TextInput
            label="프로젝트"
            name="prj_id"
            readOnly
            value={project?.prj_nm ?? String(initial!.prj_id)}
            onValueChange={() => {}}
          />
        ) : (
          <SelectInput
            label="프로젝트"
            name="prj_id"
            required
            value={projectId == null ? "" : String(projectId)}
            disabled={blocked || projects.loading || !!projects.error}
            error={fieldError("prj_id")}
            options={(projects.data ?? []).map((row) => ({
              value: String(row.prj_id),
              label: row.prj_nm,
            }))}
            onValueChange={(value) => {
              setProjectId(value ? Number(value) : null);
              setOwnerMode("");
              change("chrg_nm", null);
            }}
          />
        )}
        <TextInput
          label="제목"
          name="ttl_nm"
          required
          maxLength={100}
          value={draft.ttl_nm}
          disabled={blocked || !writable}
          error={fieldError("ttl_nm")}
          onValueChange={(value) => change("ttl_nm", value)}
        />
        <SelectInput
          label="중요도"
          name="svrt_cd"
          required
          value={draft.svrt_cd}
          disabled={blocked || !writable}
          error={fieldError("svrt_cd")}
          options={[
            { value: "HIGH", label: "높음" },
            { value: "MID", label: "중간" },
            { value: "LOW", label: "낮음" },
          ]}
          onValueChange={(value) =>
            change("svrt_cd", value as IssueInput["svrt_cd"])
          }
        />
        <p className={styles.hint}>
          높음: 일정·원가에 바로 영향 · 중간: 2주 안에 풀어야 함 · 낮음: 그 밖
        </p>
        <ErrorBanner
          error={owners.error}
          onRetry={() => void owners.reload().catch(() => {})}
        />
        {!projectId && <p className={styles.hint}>프로젝트를 먼저 고르세요</p>}
        <SelectInput
          label="담당자"
          name="owner_option"
          value={ownerMode}
          disabled={!projectId || blocked || !writable}
          options={[
            ...(owners.data ?? []).map((owner) => ({
              value: `owner:${owner.name}`,
              label: owner.name,
            })),
            { value: "direct", label: "직접 입력" },
          ]}
          onValueChange={(value) => {
            setOwnerMode(value);
            change(
              "chrg_nm",
              value.startsWith("owner:") ? value.slice(6) : null,
            );
          }}
        />
        {ownerMode === "direct" && (
          <TextInput
            label="담당자 직접 입력"
            name="chrg_nm"
            maxLength={50}
            value={draft.chrg_nm ?? ""}
            disabled={blocked || !writable}
            error={fieldError("chrg_nm")}
            onValueChange={(value) => change("chrg_nm", value)}
          />
        )}
        <div className={styles.dates}>
          <TextInput
            label="기한"
            name="ddln_dt"
            type="date"
            required
            value={draft.ddln_dt}
            disabled={blocked || !writable}
            error={fieldError("ddln_dt")}
            onValueChange={(value) => change("ddln_dt", value)}
          />
          <SelectInput
            label="상태"
            name="issue_stts_cd"
            required
            value={draft.issue_stts_cd}
            disabled={blocked || !writable}
            error={fieldError("issue_stts_cd")}
            options={[
              { value: "OPEN", label: "열림" },
              { value: "DOING", label: "조치 중" },
              { value: "CLOSED", label: "종료" },
            ]}
            onValueChange={(value) =>
              change("issue_stts_cd", value as IssueInput["issue_stts_cd"])
            }
          />
        </div>
        <TextareaInput
          label="조치 내용"
          name="actn_desc"
          maxLength={300}
          value={draft.actn_desc ?? ""}
          disabled={blocked || !writable}
          error={fieldError("actn_desc")}
          onValueChange={(value) => change("actn_desc", value)}
        />
        <div className={styles.actions}>
          <Button
            type="button"
            data-testid="issue-cancel"
            disabled={write.pending}
            onClick={cancel}
          >
            취소
          </Button>
          {writable && (
            <Can perm={!project && !initial ? "UI-31.create" : "UI-01.update"}>
              <Button
                type="submit"
                data-testid="issue-save"
                variant="primary"
                pending={write.pending}
                disabled={
                  write.uncertain ||
                  (!project &&
                    !initial &&
                    (projects.loading || !!projects.error))
                }
              >
                저장
              </Button>
            </Can>
          )}
        </div>
      </form>
    </Modal>
  );
}
