import { useCallback, useEffect, useState, type ReactNode } from "react";
import {
  Button,
  Pagination,
  Skeleton,
  SuccessToast,
} from "../../shared/Controls";
import { SelectInput } from "../../shared/Inputs";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { Can } from "../../shared/Can";
import { useAdminRead } from "../admin/useAdminResource";
import { getProject, listProjectOptions } from "../projects/client";
import { ScreenHeading } from "../shared/ScreenHeading";
import { IssueForm } from "./IssueForm";
import { IssueTable } from "./IssueTable";
import {
  listIssues,
  type IssueFilters,
  type IssueRow,
  type IssueProject,
} from "./client";
import styles from "./Issues.module.css";

export function IssueListPanel({
  onChanged,
  onOpenCount,
}: {
  onChanged?: () => Promise<unknown>;
  onOpenCount?: (count: number) => void;
}) {
  const [filters, setFilters] = useState<IssueFilters>({
    status: "OPEN_DOING",
    severity: "",
    projectId: "",
    page: 0,
    size: 50,
  });
  const state = useAdminRead(useCallback(() => listIssues(filters), [filters]));
  const projects = useAdminRead(listProjectOptions);
  const [editing, setEditing] = useState<IssueRow | "new" | null>(null);
  const [message, setMessage] = useState("");
  useEffect(() => {
    if (state.data) onOpenCount?.(state.data.open_count);
  }, [state.data, onOpenCount]);
  const change = <K extends keyof IssueFilters>(
    key: K,
    value: IssueFilters[K],
  ) => setFilters((current) => ({ ...current, [key]: value, page: 0 }));
  const reload = async () => {
    await state.reload();
    await onChanged?.();
  };
  return (
    <section
      role="tabpanel"
      aria-label="이슈 모아 보기"
      data-testid="issue-list-panel"
    >
      <div className={styles.filters}>
        <SelectInput
          label="상태"
          name="status"
          value={filters.status}
          options={[
            { value: "OPEN_DOING", label: "열림·조치 중" },
            { value: "OVERDUE", label: "기한 지남" },
            { value: "CLOSED", label: "종료" },
          ]}
          onValueChange={(value) => {
            if (value) change("status", value as IssueFilters["status"]);
          }}
        />
        <SelectInput
          label="중요도"
          name="severity"
          value={filters.severity || "ALL"}
          options={[
            { value: "ALL", label: "전체" },
            { value: "HIGH", label: "높음" },
            { value: "MID", label: "중간" },
            { value: "LOW", label: "낮음" },
          ]}
          onValueChange={(value) =>
            change(
              "severity",
              (value === "ALL" ? "" : value) as IssueFilters["severity"],
            )
          }
        />
        <SelectInput
          label="프로젝트"
          name="projectId"
          value={filters.projectId || "ALL"}
          disabled={projects.loading || !!projects.error}
          options={[
            { value: "ALL", label: "전체" },
            ...(projects.data ?? []).map((project) => ({
              value: String(project.id),
              label: project.name,
            })),
          ]}
          onValueChange={(value) =>
            change("projectId", value === "ALL" ? "" : value)
          }
        />
        <Can perm="UI-31.create">
          <Button
            data-testid="issue-list-add"
            onClick={() => setEditing("new")}
          >
            + 이슈 등록
          </Button>
        </Can>
      </div>
      <ErrorBanner
        error={projects.error}
        onRetry={() => void projects.reload().catch(() => {})}
      />
      <ErrorBanner
        error={state.error}
        onRetry={() => void state.reload().catch(() => {})}
      />
      {message && (
        <SuccessToast message={message} onDismiss={() => setMessage("")} />
      )}
      {state.loading ? (
        <Skeleton />
      ) : (
        !state.error &&
        state.data && (
          <>
            <IssueTable
              items={state.data.items}
              aggregate
              onEdit={setEditing}
            />
            <Pagination
              page={filters.page + 1}
              size={filters.size}
              total={state.data.total}
              onPage={(page) =>
                setFilters((current) => ({ ...current, page: page - 1 }))
              }
              onSize={(size) => change("size", size)}
            />
          </>
        )
      )}
      {editing === "new" && (
        <IssueForm
          reload={reload}
          onCancel={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            setMessage("이슈를 등록했습니다.");
          }}
        />
      )}
      {editing && editing !== "new" && (
        <IssueListEditor
          row={editing}
          reload={reload}
          onCancel={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            setMessage("이슈를 저장했습니다.");
          }}
        />
      )}
    </section>
  );
}
function IssueListEditor({
  row,
  reload,
  onCancel,
  onSaved,
}: {
  row: IssueRow;
  reload: () => Promise<unknown>;
  onCancel: () => void;
  onSaved: () => void;
}) {
  const project = useAdminRead(
    useCallback(() => getProject(row.prj_id), [row.prj_id]),
  );
  return (
    <>
      <ErrorBanner
        error={project.error}
        onRetry={() => void project.reload().catch(() => {})}
      />
      {project.loading ? (
        <Skeleton />
      ) : (
        project.data &&
        !project.error && (
          <IssueForm
            project={project.data as IssueProject}
            initial={row}
            reload={reload}
            onSaved={onSaved}
            onCancel={onCancel}
          />
        )
      )}
    </>
  );
}

// SCR-UI-31: board content stays with its owner; the shared registration supplies it here.
export default function UI31Screen({
  name = "프로젝트 현황판",
  groupName = "프로젝트·투입",
  board,
  onChanged,
}: {
  name?: string;
  groupName?: string;
  board?: ReactNode;
  onChanged?: () => Promise<unknown>;
}) {
  const [tab, setTab] = useState(board ? "board" : "issues");
  const [count, setCount] = useState<number | null>(null);
  return (
    <section data-testid="issues-screen" data-screen-id="SCR-UI-31">
      <ScreenHeading screenId="UI-31" name={name} groupName={groupName} />
      <div role="tablist" className={styles.tabs}>
        {board && (
          <button
            type="button"
            role="tab"
            data-testid="issue-board-tab"
            aria-selected={tab === "board"}
            onClick={() => setTab("board")}
          >
            현황판
          </button>
        )}
        <button
          type="button"
          role="tab"
          data-testid="issue-list-tab"
          aria-selected={tab === "issues"}
          onClick={() => setTab("issues")}
        >
          이슈 모아 보기 ({count ?? "–"})
        </button>
      </div>
      {board && (
        <div
          role="tabpanel"
          data-testid="issue-board-panel"
          hidden={tab !== "board"}
        >
          {board}
        </div>
      )}
      <div hidden={tab !== "issues"}>
        <IssueListPanel onChanged={onChanged} onOpenCount={setCount} />
      </div>
    </section>
  );
}
