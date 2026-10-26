import { useCallback, useState } from "react";
import { useAdminRead } from "../admin/useAdminResource";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { Skeleton } from "../../shared/Controls";
import { listDeveloperOptions } from "./client";

export function DeveloperSelect({
  value,
  onChange,
  disabled = false,
}: {
  value: number | null;
  onChange: (id: number | null) => void;
  disabled?: boolean;
}) {
  const [q, setQ] = useState("");
  const state = useAdminRead(useCallback(() => listDeveloperOptions(q), [q]));
  return (
    <section data-testid="developer-option-picker">
      <label>
        개발자 검색
        <input
          data-testid="developer-option-query"
          value={q}
          disabled={disabled}
          onChange={(e) => setQ(e.target.value)}
          placeholder="이름 또는 관리번호"
        />
      </label>
      <ErrorBanner
        error={state.error}
        onRetry={() => {
          void state.reload().catch(() => {});
        }}
      />
      {state.loading ? (
        <Skeleton />
      ) : (
        !state.error && (
          <label>
            개발자
            <select
              data-testid="developer-option-select"
              value={value ?? ""}
              disabled={disabled}
              onChange={(e) =>
                onChange(e.target.value ? Number(e.target.value) : null)
              }
            >
              <option value="">선택</option>
              {state.data?.map((row) => (
                <option
                  data-testid={`developer-option-${row.id}`}
                  key={row.id}
                  value={row.id}
                >
                  {row.name} · {row.mngNo} · {row.mainJob}
                </option>
              ))}
            </select>
          </label>
        )
      )}
      {!state.loading && !state.error && state.data?.length === 0 && (
        <p>선택할 개발자가 없습니다.</p>
      )}
    </section>
  );
}
