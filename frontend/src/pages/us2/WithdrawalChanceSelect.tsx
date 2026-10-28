import { useRef, useState } from "react";
import { createAssignmentChange } from "../changes/client";
import { useCodes } from "../shared/useCodes";
import { ApiFailure } from "../../shared/api";
import { SuccessToast } from "../../shared/Controls";
import styles from "./WithdrawalChance.module.css";

export interface WithdrawalChanceRow {
  asgn_id: number;
  updated_at: string;
  ext_psblty_cd: string | null;
  bgn_dt: string;
  end_dt: string;
  asgn_stts_cd: string;
  chance_locked: boolean;
  chance_lock_reason: string | null;
}
export interface WithdrawalChanceSelectProps {
  row: WithdrawalChanceRow;
  today: string;
  year: number;
  onChanged: () => Promise<unknown>;
}

export function withdrawalChanceLockReason(
  row: WithdrawalChanceRow,
  today: string,
  year: number,
) {
  if (year < Number(today.slice(0, 4))) return "지난 해 · 읽기 전용";
  if (row.end_dt < today) return "이미 철수한 투입입니다.";
  if (row.asgn_stts_cd === "REPLACED") return "교체된 투입입니다.";
  if (row.asgn_stts_cd === "CANCELLED" || row.asgn_stts_cd === "ENDED")
    return "변경할 수 없는 투입 상태입니다.";
  if (row.bgn_dt > today)
    return `투입일(${row.bgn_dt}) 전에는 변경할 수 없습니다.`;
  if (row.chance_locked)
    return row.chance_lock_reason || "변경할 수 없는 투입입니다.";
  return null;
}

// SCR-UI-05: the withdrawals table supplies its server date, selected year and full-list refresh.
export function WithdrawalChanceSelect({
  row,
  today,
  year,
  onChanged,
}: WithdrawalChanceSelectProps) {
  const chances = useCodes("EXT_POSSIBILITY");
  const [saved, setSaved] = useState<{
    source: string;
    value: string | null;
    version: string;
  } | null>(null);
  const source = `${row.asgn_id}:${row.updated_at}:${row.ext_psblty_cd ?? ""}`;
  const current =
    saved?.source === source
      ? saved
      : { value: row.ext_psblty_cd, version: row.updated_at };
  const [pending, setPending] = useState<string | null | undefined>(undefined);
  const [message, setMessage] = useState("");
  const [succeeded, setSucceeded] = useState(false);
  const [refreshFailed, setRefreshFailed] = useState(false);
  const [stale, setStale] = useState<string | null>(null);
  const busy = useRef(false);
  const reason = withdrawalChanceLockReason(row, today, year);
  const id = `withdrawal-chance-${row.asgn_id}`;
  async function refresh() {
    try {
      await onChanged();
      setRefreshFailed(false);
      setStale(null);
    } catch {
      setRefreshFailed(true);
    }
  }
  async function change(value: string | null) {
    if (
      busy.current ||
      reason ||
      stale === source ||
      value === current.value ||
      chances.loading ||
      chances.error
    )
      return;
    if (value !== null && !chances.codes.some((code) => code.code === value))
      return;
    busy.current = true;
    setPending(value);
    setMessage("");
    setSucceeded(false);
    setRefreshFailed(false);
    try {
      const result = await createAssignmentChange(row.asgn_id, {
        asgn_chg_ty_cd: "EXT_CHANCE",
        aply_dt: today,
        ext_psblty_cd: value,
        updated_at: current.version,
      });
      setSaved({
        source,
        value: result.assignment.ext_psblty_cd,
        version: result.assignment.updated_at,
      });
      setSucceeded(true);
      const name =
        value === null
          ? "미정"
          : chances.codes.find((code) => code.code === value)!.name;
      setMessage(`연장 가능성을 '${name}'(으)로 바꿨습니다.`);
      await refresh();
    } catch (error) {
      setPending(undefined);
      if (error instanceof ApiFailure && error.code === "STALE_ASSIGNMENT") {
        setStale(source);
        setMessage("다른 사람이 먼저 바꿨습니다. 목록을 새로 고칩니다.");
        await refresh();
      } else {
        setMessage(
          error instanceof Error
            ? error.message
            : "연장 가능성을 바꾸지 못했습니다.",
        );
      }
    } finally {
      setPending(undefined);
      busy.current = false;
    }
  }
  return (
    <div
      className={styles.cell}
      data-testid={`${id}-cell`}
      onClick={(event) => event.stopPropagation()}
      onKeyDown={(event) => event.stopPropagation()}
    >
      <select
        data-testid={id}
        aria-label="연장 가능성"
        aria-describedby={reason ? `${id}-reason` : undefined}
        title={reason ?? undefined}
        disabled={
          !!reason ||
          pending !== undefined ||
          chances.loading ||
          !!chances.error ||
          stale === source
        }
        value={(pending !== undefined ? pending : current.value) ?? ""}
        onChange={(event) => void change(event.target.value || null)}
      >
        <option value="">미정</option>
        {chances.codes
          .filter((code) => ["H", "M", "L"].includes(code.code))
          .map((code) => (
            <option key={code.code} value={code.code}>
              {code.name}
            </option>
          ))}
      </select>
      {reason && (
        <small id={`${id}-reason`} className={styles.reason}>
          {reason}
        </small>
      )}
      {pending !== undefined && <small>저장 중…</small>}
      {message &&
        (succeeded ? (
          <SuccessToast message={message} onDismiss={() => setMessage("")} />
        ) : (
          <div
            role="alert"
            className={styles.failure}
            data-testid={`${id}-message`}
          >
            {message}
          </div>
        ))}
      {chances.error && (
        <small role="alert">
          {chances.error.message}{" "}
          <button
            type="button"
            data-testid={`${id}-codes-retry`}
            onClick={chances.retry}
          >
            다시 시도
          </button>
        </small>
      )}
      {refreshFailed && (
        <small role="alert">
          목록을 다시 조회하지 못했습니다.{" "}
          <button
            type="button"
            data-testid={`${id}-refresh`}
            onClick={() => void refresh()}
          >
            목록 새로고침
          </button>
        </small>
      )}
    </div>
  );
}
