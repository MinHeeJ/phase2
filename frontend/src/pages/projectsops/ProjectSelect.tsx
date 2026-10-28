import { useCallback, useState } from "react";
import { Button, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "../admin/useAdminResource";
import { listProjectOptions } from "./client";
import styles from "./ProjectLifecycle.module.css";

export function ProjectSelect({
  value,
  onChange,
  label = "프로젝트",
  disabled = false,
}: {
  value: number | null;
  onChange: (id: number | null) => void;
  label?: string;
  disabled?: boolean;
}) {
  const [draft, setDraft] = useState(""),
    [query, setQuery] = useState("");
  const state = useAdminRead(
    useCallback(() => listProjectOptions(query), [query]),
  );
  return (
    <section data-testid="project-select" className={styles.select}>
      <label>
        프로젝트 검색
        <input
          data-testid="project-option-search"
          value={draft}
          disabled={disabled}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.preventDefault();
              setQuery(draft);
            }
          }}
        />
      </label>
      <Button
        data-testid="project-option-query"
        disabled={disabled}
        onClick={() => setQuery(draft)}
      >
        검색
      </Button>
      <ErrorBanner
        error={state.error}
        onRetry={() => void state.reload().catch(() => {})}
      />
      {state.loading && <Skeleton />}
      <label>
        {label}
        <select
          data-testid="project-option-value"
          aria-label={label}
          value={value ?? ""}
          disabled={disabled || state.loading || !!state.error}
          onChange={(e) =>
            onChange(e.target.value ? Number(e.target.value) : null)
          }
        >
          <option value="">선택해 주세요</option>
          {state.data?.map((row) => (
            <option
              key={row.id}
              value={row.id}
              data-testid={`project-option-${row.id}`}
            >
              {row.no} · {row.name}
              {row.custName ? ` · ${row.custName}` : ""}
            </option>
          ))}
        </select>
      </label>
      {!state.loading && !state.error && state.data?.length === 0 && (
        <p>조건에 맞는 결과가 없습니다.</p>
      )}
    </section>
  );
}
