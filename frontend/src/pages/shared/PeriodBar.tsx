import { useEffect, useState } from "react";
import { Badge, Button, LockedBanner, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import { getMonthClose, monthStatus, type MonthClose } from "./api";
import { periodType } from "./registry";
import styles from "./SharedScreens.module.css";
import { useScreenPeriod } from "./useScreenPeriod";
export { seoulMonth } from "./useScreenPeriod";

export function PeriodBar({ screenId }: { screenId: string }) {
  const type = periodType(screenId);
  const [period, setPeriod] = useScreenPeriod(screenId);
  const [rows, setRows] = useState<MonthClose[]>([]);
  const [error, setError] = useState<ApiFailure | null>(null);
  const [loading, setLoading] = useState(false);
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    if (type !== "MONTH") return;
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    setRows([]);
    getMonthClose(period, period, controller.signal)
      .then((result) => {
        if (!controller.signal.aborted) setRows(result);
      })
      .catch((failure: ApiFailure) => {
        if (!controller.signal.aborted) setError(failure);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [type, period, revision]);
  if (type === "NONE") return null;
  const update = (value: string) => {
    setPeriod(value);
  };
  const year = Number(period.slice(0, 4));
  const shiftYear = (amount: number) =>
    update(`${String(year + amount).padStart(4, "0")}${period.slice(4)}`);
  const shiftMonth = (amount: number) => {
    const index = year * 12 + Number(period.slice(5)) - 1 + amount;
    update(
      `${String(Math.floor(index / 12)).padStart(4, "0")}-${String((index % 12) + 1).padStart(2, "0")}`,
    );
  };
  const closed = !loading && !error && monthStatus(rows, period) === "CLOSED";
  return (
    <section className={styles.period} aria-label="조회 기간">
      <div className={styles.periodControls}>
        {type === "MONTH" ? (
          <>
            <Button
              aria-label="이전 월"
              disabled={period <= "0001-01"}
              onClick={() => shiftMonth(-1)}
            >
              ‹ 이전 월
            </Button>
            <label>
              조회월{" "}
              <input
                type="month"
                aria-label="조회월"
                value={period}
                min="0001-01"
                max="9999-12"
                onChange={(event) => {
                  if (
                    /^(?!0000)[0-9]{4}-(0[1-9]|1[0-2])$/.test(
                      event.target.value,
                    )
                  )
                    update(event.target.value);
                }}
              />
            </label>
            <Button
              aria-label="다음 월"
              disabled={period >= "9999-12"}
              onClick={() => shiftMonth(1)}
            >
              다음 월 ›
            </Button>
          </>
        ) : (
          <>
            <Button
              aria-label="이전 연도"
              disabled={year <= 1}
              onClick={() => shiftYear(-1)}
            >
              ‹ 이전 연도
            </Button>
            <strong>{year}년</strong>
            <Button
              aria-label="다음 연도"
              disabled={year >= 9999}
              onClick={() => shiftYear(1)}
            >
              다음 연도 ›
            </Button>
            {type === "YEAR_CUM" && (
              <>
                <label>
                  누계월{" "}
                  <select
                    aria-label="누계월"
                    value={period.slice(5)}
                    onChange={(event) =>
                      update(`${period.slice(0, 5)}${event.target.value}`)
                    }
                  >
                    {Array.from({ length: 12 }, (_, index) =>
                      String(index + 1).padStart(2, "0"),
                    ).map((value) => (
                      <option key={value} value={value}>
                        {Number(value)}월
                      </option>
                    ))}
                  </select>
                </label>
                <span>1~{Number(period.slice(5))}월 누계</span>
              </>
            )}
          </>
        )}
        {type === "MONTH" && !loading && !error && (
          <Badge tone={closed ? "locked" : "info"}>
            {closed ? "마감" : "작업중"}
          </Badge>
        )}
      </div>
      {type === "MONTH" && loading && <Skeleton label="월마감 불러오는 중" />}
      <ErrorBanner
        error={error}
        onRetry={() => setRevision((value) => value + 1)}
        pending={loading}
      />
      {type === "MONTH" && closed && (
        <LockedBanner>
          {period}는 마감되었습니다. 고칠 내용은 다음 달 전월 조정으로
          입력하세요.
        </LockedBanner>
      )}
    </section>
  );
}
