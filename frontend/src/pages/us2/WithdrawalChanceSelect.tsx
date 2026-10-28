import { useEffect, useRef, useState } from "react";
import { createAssignmentChange } from "../changes/client";
import { ApiFailure } from "../../shared/api";
import { ErrorBanner } from "../../shared/ErrorBanner";

export interface WithdrawalChanceRow {
  asgn_id: number;
  bgn_dt: string;
  end_dt: string;
  asgn_stts_cd: string;
  prj_stts_cd: string;
  use_yn: string;
  ext_psblty_cd: string | null;
  updated_at: string;
}

interface Props {
  row: WithdrawalChanceRow;
  year: number;
  options: readonly { code: string; name: string }[];
  reload: () => Promise<unknown>;
  today?: string;
}

function seoulToday() {
  return new Intl.DateTimeFormat("sv-SE", { timeZone: "Asia/Seoul" }).format(
    new Date(),
  );
}

export function withdrawalChanceLock(
  row: WithdrawalChanceRow,
  year: number,
  today: string,
): string | null {
  if (year < Number(today.slice(0, 4))) return "지난 해는 읽기 전용입니다.";
  if (row.use_yn !== "Y" || ["END", "LOST"].includes(row.prj_stts_cd))
    return "종료되었거나 사용하지 않는 프로젝트입니다.";
  if (row.asgn_stts_cd !== "ACTIVE") return "교체되었거나 취소된 투입입니다.";
  if (row.end_dt < today) return "이미 철수한 투입입니다.";
  if (row.bgn_dt > today) return "투입일 전에는 변경할 수 없습니다.";
  if (!row.updated_at) return "투입 정보를 다시 조회해 주세요.";
  return null;
}

// SCR-UI-05: inline EXT_CHANCE editor, integrated by the withdrawal list.
export function WithdrawalChanceSelect({
  row,
  year,
  options,
  reload,
  today = seoulToday(),
}: Props) {
  const [value, setValue] = useState(row.ext_psblty_cd ?? "");
  const [pending, setPending] = useState(false);
  const [needsReload, setNeedsReload] = useState(false);
  const [error, setError] = useState<ApiFailure | null>(null);
  const [message, setMessage] = useState("");
  const busy = useRef(false);
  useEffect(
    () => setValue(row.ext_psblty_cd ?? ""),
    [row.asgn_id, row.ext_psblty_cd, row.updated_at],
  );
  const lock = withdrawalChanceLock(row, year, today);
  async function refresh() {
    try {
      await reload();
      setNeedsReload(false);
    } catch {
      setNeedsReload(true);
      setError(
        new ApiFailure(
          "목록을 새로 고치지 못했습니다. 다시 조회해 주세요.",
          null,
          null,
        ),
      );
    }
  }
  async function change(next: string) {
    const previous = row.ext_psblty_cd ?? "";
    if (busy.current || pending || needsReload || lock || next === previous)
      return;
    if (
      next &&
      !options.some(
        (option) => option.code === next && ["H", "M", "L"].includes(next),
      )
    )
      return;
    busy.current = true;
    setPending(true);
    setError(null);
    setMessage("");
    setValue(next);
    try {
      await createAssignmentChange(row.asgn_id, {
        updated_at: row.updated_at,
        asgn_chg_ty_cd: "EXT_CHANCE",
        aply_dt: today,
        ext_psblty_cd: next || null,
      });
      const label = next
        ? (options.find((option) => option.code === next)?.name ?? next)
        : "미정";
      setMessage(`연장 가능성을 '${label}'(으)로 바꿨습니다.`);
      setNeedsReload(true);
      await refresh();
    } catch (failure) {
      setValue(previous);
      const apiError =
        failure instanceof ApiFailure
          ? failure
          : new ApiFailure("변경하지 못했습니다.", null, null);
      if (apiError.status === 409 && apiError.code === "STALE_ASSIGNMENT") {
        setError(
          new ApiFailure(
            "다른 사람이 먼저 바꿨습니다. 목록을 새로 고칩니다.",
            409,
            apiError.requestId,
            apiError.field,
            apiError.code,
          ),
        );
        setNeedsReload(true);
        await refresh();
      } else {
        setError(apiError);
        if (apiError.uncertainWrite) {
          setNeedsReload(true);
          await refresh();
        }
      }
    } finally {
      busy.current = false;
      setPending(false);
    }
  }
  return (
    <div>
      <select
        aria-label="연장 가능성"
        data-testid={`withdrawal-chance-${row.asgn_id}`}
        value={value}
        disabled={!!lock || pending || needsReload}
        title={
          lock ?? (needsReload ? "목록을 다시 조회해 주세요." : "연장 가능성")
        }
        onChange={(event) => void change(event.target.value)}
      >
        <option value="">미정</option>
        {options
          .filter((option) => ["H", "M", "L"].includes(option.code))
          .map((option) => (
            <option key={option.code} value={option.code}>
              {option.name}
            </option>
          ))}
      </select>
      {message && <span role="status">{message}</span>}
      <ErrorBanner
        error={error}
        onRetry={needsReload ? () => void refresh() : undefined}
        pending={pending}
      />
    </div>
  );
}
