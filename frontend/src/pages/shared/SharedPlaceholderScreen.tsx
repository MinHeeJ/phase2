import { useCallback, useState } from "react";
import {
  ChevronLeft,
  ChevronRight,
  Construction,
  LockKeyhole,
} from "lucide-react";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { Badge, Button, Skeleton } from "../../shared/ui";
import { getMonthClose, useSharedRead } from "./sharedApi";
import styles from "./shared.module.css";

export type SharedPeriodType = "YEAR" | "MONTH" | "YEAR_CUM" | "NONE";
const yearScreens = new Set([
  "UI-01",
  "UI-12",
  "UI-05",
  "UI-11",
  "UI-28",
  "UI-17",
  "UI-13",
  "UI-03",
  "UI-04",
  "UI-10",
  "UI-21",
]);
const monthScreens = new Set(["UI-19", "UI-20", "UI-14"]);
const cumulativeScreens = new Set(["UI-07", "UI-08", "UI-09", "UI-18"]);
export function sharedPeriodType(screenId: string): SharedPeriodType {
  return yearScreens.has(screenId)
    ? "YEAR"
    : monthScreens.has(screenId)
      ? "MONTH"
      : cumulativeScreens.has(screenId)
        ? "YEAR_CUM"
        : "NONE";
}
export function seoulCurrentMonth(now = new Date()) {
  const parts = new Intl.DateTimeFormat("en", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
  }).formatToParts(now);
  return `${parts.find((part) => part.type === "year")!.value.padStart(4, "0")}-${parts.find((part) => part.type === "month")!.value}`;
}
function MonthStatus({ month }: { month: string }) {
  const load = useCallback(
    async () => ({ month, rows: await getMonthClose(month, month) }),
    [month],
  );
  const state = useSharedRead(load);
  const row =
    state.data?.month === month
      ? state.data.rows.find((item) => item.month === month)
      : undefined;
  if (state.error)
    return <ErrorBanner error={state.error} onRetry={state.retry} />;
  if (state.loading || !row)
    return <Skeleton label="월마감 상태 불러오는 중" />;
  return (
    <>
      <Badge tone={row.status === "CLOSED" ? "locked" : "info"}>
        {row.status === "CLOSED" ? "마감" : "작업중"}
      </Badge>
      {row.status === "CLOSED" && (
        <div className={styles.closed}>
          <LockKeyhole size={16} />
          {month}는 마감되었습니다. 고칠 내용은 다음 달 전월 조정으로
          입력하세요.
        </div>
      )}
    </>
  );
}
export function SharedPlaceholderScreen({
  screenId,
  now,
}: {
  screenId: string;
  now?: Date;
}) {
  const type = sharedPeriodType(screenId);
  const [period, setPeriod] = useState(() => seoulCurrentMonth(now));
  const [year, month] = period.split("-").map(Number);
  function shift(direction: number) {
    if (type !== "MONTH") {
      const next = year + direction;
      if (next >= 1 && next <= 9999)
        setPeriod(
          `${String(next).padStart(4, "0")}-${String(month).padStart(2, "0")}`,
        );
    } else {
      const next = year * 12 + month - 1 + direction;
      const nextYear = Math.floor(next / 12);
      if (nextYear >= 1 && nextYear <= 9999)
        setPeriod(
          `${String(nextYear).padStart(4, "0")}-${String((next % 12) + 1).padStart(2, "0")}`,
        );
    }
  }
  return (
    <>
      {type !== "NONE" && (
        <section className={styles.period} aria-label="조회 기간">
          <Button
            aria-label={type === "MONTH" ? "이전 월" : "이전 연도"}
            onClick={() => shift(-1)}
            disabled={year === 1 && (type !== "MONTH" || month === 1)}
          >
            <ChevronLeft size={16} />
          </Button>
          {type === "MONTH" ? (
            <label>
              조회월{" "}
              <input
                aria-label="조회월"
                type="month"
                min="0001-01"
                max="9999-12"
                value={period}
                onChange={(event) => {
                  if (
                    /^(?!0000)[0-9]{4}-(0[1-9]|1[0-2])$/.test(
                      event.target.value,
                    )
                  )
                    setPeriod(event.target.value);
                }}
              />
            </label>
          ) : (
            <strong>{year}년</strong>
          )}
          <Button
            aria-label={type === "MONTH" ? "다음 월" : "다음 연도"}
            onClick={() => shift(1)}
            disabled={year === 9999 && (type !== "MONTH" || month === 12)}
          >
            <ChevronRight size={16} />
          </Button>
          {type === "YEAR_CUM" && (
            <>
              <label>
                누계월{" "}
                <select
                  aria-label="누계월"
                  value={month}
                  onChange={(event) =>
                    setPeriod(
                      `${String(year).padStart(4, "0")}-${event.target.value.padStart(2, "0")}`,
                    )
                  }
                >
                  {Array.from({ length: 12 }, (_, index) => (
                    <option value={index + 1} key={index}>
                      {index + 1}월
                    </option>
                  ))}
                </select>
              </label>
              <span>1~{month}월 누계</span>
            </>
          )}
          {type === "MONTH" && <MonthStatus month={period} />}
        </section>
      )}
      <section className={styles.placeholder} aria-label="화면 준비 안내">
        <div className={styles.placeholderIcon}>
          <Construction size={28} aria-hidden="true" />
        </div>
        <p>이 화면은 다음 단계에서 만듭니다.</p>
      </section>
    </>
  );
}
