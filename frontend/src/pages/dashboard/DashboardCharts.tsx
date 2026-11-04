import type { DashboardData } from "../apiops/dashboardClient";
import styles from "../apiops/Dashboard.module.css";

type WorkforcePoint = DashboardData["workforceForecast"]["months"][number];
type CashPoint = DashboardData["cashForecast"]["months"][number];

export function WorkforceChart({ months }: { months: WorkforcePoint[] }) {
  const values = months
    .flatMap((point) => [point.availableMm, point.requiredMm])
    .filter((v): v is number => v !== null);
  if (!values.length) return <p>인력 전망 자료 없음</p>;
  const maximum = Math.max(1, ...values.map(Math.abs));
  return (
    <figure className={styles.figure}>
      <figcaption>가용 인력(파랑) · 필요 인력(주황) · 단위 MM</figcaption>
      <svg
        className={styles.chart}
        viewBox="0 0 600 210"
        role="img"
        aria-label="6개월 가용 인력과 필요 인력 막대 차트"
      >
        <line x1="20" y1="165" x2="580" y2="165" stroke="var(--divider)" />
        {months.map((point, index) => (
          <g
            key={point.month}
            tabIndex={0}
            data-testid={`dashboard-workforce-point-${point.month}`}
            aria-label={`${point.month} 가용 ${point.availableMm ?? "자료 없음"} MM, 필요 ${point.requiredMm ?? "자료 없음"} MM${point.isShortage ? ", 부족" : ""}`}
          >
            <title>
              {point.month} · 가용 {point.availableMm ?? "자료 없음"} MM · 필요{" "}
              {point.requiredMm ?? "자료 없음"} MM · 차이{" "}
              {point.shortageMm ?? "자료 없음"} MM
            </title>
            {point.isShortage && (
              <rect
                x={25 + index * 94}
                y="15"
                width="80"
                height="158"
                fill="var(--danger-bg)"
              />
            )}
            {[point.availableMm, point.requiredMm].map((value, series) =>
              value === null ? null : (
                <rect
                  key={series}
                  x={38 + index * 94 + series * 24}
                  y={165 - (Math.max(0, value) / maximum) * 140}
                  width="19"
                  height={(Math.max(0, value) / maximum) * 140}
                  fill={series === 0 ? "var(--primary)" : "var(--warning)"}
                />
              ),
            )}
            <text
              x={65 + index * 94}
              y="192"
              textAnchor="middle"
              fontSize="11"
              fill="var(--text-sub)"
            >
              {point.month}
            </text>
          </g>
        ))}
      </svg>
    </figure>
  );
}

export function CashChart({
  months,
  money,
}: {
  months: CashPoint[];
  money: (value: number | null) => string;
}) {
  const values = months
    .flatMap((point) => [
      point.expectedFinalBalance,
      point.minimumOperatingCash,
    ])
    .filter((v): v is number => v !== null);
  if (!values.length) return <p>자금 전망 자료 없음</p>;
  const min = Math.min(0, ...values),
    max = Math.max(1, ...values),
    range = max - min;
  const y = (value: number) => 155 - ((value - min) / range) * 130;
  return (
    <figure className={styles.figure}>
      <figcaption>예상 최종 잔액(실선) · 최소 운영자금(점선)</figcaption>
      <svg
        className={styles.chart}
        viewBox="0 0 360 200"
        role="img"
        aria-label="3개월 예상 최종 잔액과 최소 운영자금 차트"
      >
        <line x1="25" y1={y(0)} x2="335" y2={y(0)} stroke="var(--divider)" />
        {(["expectedFinalBalance", "minimumOperatingCash"] as const).map(
          (key) => (
            <g
              key={key}
              stroke={
                key === "expectedFinalBalance"
                  ? "var(--primary)"
                  : "var(--danger)"
              }
            >
              {months.map((point, index) => {
                const value = point[key],
                  previous = months[index - 1]?.[key],
                  x = 45 + index * 135;
                if (value === null) return null;
                return (
                  <g
                    key={point.month}
                    tabIndex={0}
                    data-testid={`dashboard-cash-point-${key === "expectedFinalBalance" ? "balance" : "minimum"}-${point.month}`}
                    aria-label={`${point.month} ${key === "expectedFinalBalance" ? "예상 최종 잔액" : "최소 운영자금"} ${money(value)}`}
                  >
                    <title>
                      {point.month} ·{" "}
                      {key === "expectedFinalBalance"
                        ? "예상 최종 잔액"
                        : "최소 운영자금"}{" "}
                      {money(value)}
                    </title>
                    {previous != null && (
                      <line
                        x1={x - 135}
                        y1={y(previous)}
                        x2={x}
                        y2={y(value)}
                        strokeWidth="2"
                        strokeDasharray={
                          key === "minimumOperatingCash" ? "5 4" : undefined
                        }
                      />
                    )}
                    <circle
                      cx={x}
                      cy={y(value)}
                      r="4"
                      fill={
                        point.isShortage && key === "expectedFinalBalance"
                          ? "var(--danger)"
                          : "var(--surface)"
                      }
                    />
                  </g>
                );
              })}
            </g>
          ),
        )}
        {months.map((point, index) => (
          <text
            key={point.month}
            x={45 + index * 135}
            y="185"
            textAnchor="middle"
            fontSize="12"
            fill="var(--text-sub)"
          >
            {point.month}
          </text>
        ))}
      </svg>
    </figure>
  );
}
