import { useCallback, useEffect, useId, useRef, useState } from "react";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "../admin/useAdminResource";
import { getIssueOwnerOptions } from "./client";
import styles from "../../shared/Inputs.module.css";

export interface IssueOwnerSelectProps {
  projectId: number | null;
  value: string | null;
  onChange: (name: string | null) => void;
  disabled?: boolean;
  error?: string | null;
}

// SCR-UI-31: owner field embedded in the issue registration/edit modal, also used by SCR-UI-01.
export function IssueOwnerSelect(props: IssueOwnerSelectProps) {
  const previousProject = useRef(props.projectId);
  useEffect(() => {
    if (previousProject.current !== props.projectId) {
      previousProject.current = props.projectId;
      props.onChange(null);
    }
  }, [props.projectId, props.onChange]);
  if (props.projectId == null)
    return (
      <section data-testid="issue-owner-select" data-screen-id="SCR-UI-31">
        <label>
          담당자
          <select
            className={styles.input}
            data-testid="issue-owner-value"
            disabled
            aria-label="담당자"
          >
            <option>프로젝트를 먼저 고르세요</option>
          </select>
        </label>
        <p>프로젝트를 먼저 고르세요</p>
      </section>
    );
  return (
    <ProjectIssueOwnerSelect
      key={props.projectId}
      {...props}
      projectId={props.projectId}
    />
  );
}

function ProjectIssueOwnerSelect({
  projectId,
  value,
  onChange,
  disabled = false,
  error,
}: IssueOwnerSelectProps & { projectId: number }) {
  const state = useAdminRead(
    useCallback(() => getIssueOwnerOptions(projectId), [projectId]),
  );
  const [direct, setDirect] = useState(false);
  const id = useId();
  const listed = state.data?.some((row) => row.name === value) ?? false;
  const directInput = direct || (!!value && !listed);
  return (
    <section data-testid="issue-owner-select" data-screen-id="SCR-UI-31">
      <ErrorBanner
        error={state.error}
        onRetry={() => void state.reload().catch(() => {})}
      />
      {state.loading && <p role="status">담당자 목록을 불러오는 중입니다.</p>}
      <div className={styles.field}>
        <label htmlFor={id}>담당자</label>
        <select
          id={id}
          aria-label="담당자"
          className={styles.input}
          data-testid="issue-owner-value"
          disabled={disabled}
          value={directInput ? "direct" : value ? `name:${value}` : ""}
          onChange={(event) => {
            const next = event.target.value;
            setDirect(next === "direct");
            onChange(next.startsWith("name:") ? next.slice(5) : null);
          }}
        >
          <option value="">선택해 주세요</option>
          {!state.loading &&
            !state.error &&
            state.data?.map((row) => (
              <option
                data-testid={`issue-owner-option-${state.data?.indexOf(row)}`}
                key={row.name}
                value={`name:${row.name}`}
              >
                {row.name}
              </option>
            ))}
          <option value="direct">직접 입력</option>
        </select>
      </div>
      {!state.loading && !state.error && state.data?.length === 0 && (
        <p>선택할 담당자가 없습니다. 직접 입력할 수 있습니다.</p>
      )}
      {directInput && (
        <div className={styles.field}>
          <label htmlFor={`${id}-direct`}>담당자 직접 입력</label>
          <div className={styles.control}>
            <input
              id={`${id}-direct`}
              name="chrg_nm"
              data-testid="issue-owner-direct"
              className={styles.input}
              value={value ?? ""}
              disabled={disabled}
              maxLength={50}
              aria-invalid={!!error}
              aria-describedby={error ? `${id}-error` : undefined}
              onChange={(event) => {
                if (event.target.value.length <= 50)
                  onChange(event.target.value || null);
              }}
            />
            <span className={styles.counter}>{value?.length ?? 0}/50</span>
          </div>
        </div>
      )}
      {error && (
        <p id={`${id}-error`} role="alert" className={styles.error}>
          {error}
        </p>
      )}
    </section>
  );
}
