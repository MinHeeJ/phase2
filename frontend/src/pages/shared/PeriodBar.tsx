import { useEffect, useState } from "react";
import { Badge, Button, LockedBanner, Skeleton } from "../../components/UiKit";
import { ErrorBanner } from "../../components/ErrorBanner";
import { isValidMonth } from "../../lib/format";
import { getMonthClose, type MonthClose } from "./api";
import { periodType } from "./registry";
import styles from "./SharedScreens.module.css";

const periods = new Map<string, string>();
function todayMonth() {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
  }).formatToParts(new Date());
  return `${parts.find((part) => part.type === "year")!.value}-${parts.find((part) => part.type === "month")!.value}`;
}
export function PeriodBar({ screenId }: { screenId: string }) {
  const type = periodType(screenId);
  const [month, setMonth] = useState(
    () => periods.get(screenId) ?? todayMonth(),
  );
  const [rows, setRows] = useState<MonthClose[] | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [loading, setLoading] = useState(false);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    setMonth(periods.get(screenId) ?? todayMonth());
  }, [screenId]);
  useEffect(() => {
    if (type !== "MONTH") return;
    let active = true;
    setRows(null);
    setError(null);
    setLoading(true);
    getMonthClose(month, month)
      .then((value) => {
        if (active) setRows(value);
      })
      .catch((reason: Error) => {
        if (active) setError(reason);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [month, screenId, type, revision]);
  const choose = (value: string) => {
    if (isValidMonth(value)) {
      periods.set(screenId, value);
      setMonth(value);
    }
  };
  const year = Number(month.slice(0, 4));
  const shift = (delta: number) => {
    const index =
      (year - 1) * 12 +
      Number(month.slice(5)) -
      1 +
      (type === "MONTH" ? delta : delta * 12);
    if (index >= 0 && index < 9999 * 12)
      choose(
        `${String(Math.floor(index / 12) + 1).padStart(4, "0")}-${String((index % 12) + 1).padStart(2, "0")}`,
      );
  };
  if (type === "NONE") return null;
  const closed = rows?.find((row) => row.month === month)?.status === "CLOSED";
  return (
    <section aria-label="조회 기간" className={styles.period}>
      {error && (
        <ErrorBanner
          error={error}
          onRetry={() => setRevision((value) => value + 1)}
        />
      )}
      <div className={styles.periodControls}>
        <Button
          onClick={() => shift(-1)}
          disabled={type === "MONTH" ? month === "0001-01" : year === 1}
        >
          이전 {type === "MONTH" ? "월" : "연도"}
        </Button>
        {type === "MONTH" ? (
          <input
            aria-label="조회 연월"
            type="month"
            min="0001-01"
            max="9999-12"
            value={month}
            onChange={(event) => choose(event.target.value)}
          />
        ) : (
          <span>{year}년</span>
        )}
        <Button
          onClick={() => shift(1)}
          disabled={type === "MONTH" ? month === "9999-12" : year === 9999}
        >
          다음 {type === "MONTH" ? "월" : "연도"}
        </Button>
        {type === "YEAR_CUM" && (
          <>
            <label>
              누계월{" "}
              <input
                aria-label="누계월"
                type="month"
                min={`${month.slice(0, 4)}-01`}
                max={`${month.slice(0, 4)}-12`}
                value={month}
                onChange={(event) => choose(event.target.value)}
              />
            </label>
            <span>1~{Number(month.slice(5))}월 누계</span>
          </>
        )}
        {type === "MONTH" &&
          (loading ? (
            <Skeleton label="월 상태 불러오는 중" />
          ) : rows && !error ? (
            <Badge tone={closed ? "locked" : "info"}>
              {closed ? "마감" : "작업중"}
            </Badge>
          ) : null)}
      </div>
      {type === "MONTH" && rows && !error && closed && (
        <LockedBanner>
          {month}는 마감되었습니다. 고칠 내용은 다음 달 전월 조정으로
          입력하세요.
        </LockedBanner>
      )}
    </section>
  );
}
