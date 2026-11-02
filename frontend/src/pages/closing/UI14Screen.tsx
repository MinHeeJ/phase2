import { useCallback, useEffect, useState, type ReactNode } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { Badge, Button, LockedBanner, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { formatDateTime } from "../../lib/format";
import { useAdminRead } from "../admin/useAdminResource";
import { PeriodBar, seoulMonth } from "../shared/PeriodBar";
import { ScreenHeading } from "../shared/ScreenHeading";
import { useCodes } from "../shared/useCodes";
import { getClosing, type Closing } from "../apiops/closingClient";
import tables from "../developers/Developers.module.css";
import styles from "../apiops/Closing.module.css";

const destinations: Record<string, string> = {
  SALARY_UPLOAD: "/UI-02",
  MM_OVER: "/UI-12",
  DISPATCH_ATT: "/UI-13",
  BILL_UNCONFIRMED: "/UI-13",
  TAX_INVOICE_NO: "/UI-13",
  FIN_ENTRY: "/UI-10",
  PART_SETTLE: "/UI-19",
  PAYMENT: "/UI-20",
  BILL_MISSING: "/UI-13",
};
const tones = { OK: "success", WARN: "warning", FAIL: "danger" } as const;
const labels = { OK: "정상", WARN: "확인 필요", FAIL: "미완료" };
const validMonth = (value: unknown): value is string =>
  typeof value === "string" && /^(?!0000)[0-9]{4}-(0[1-9]|1[0-2])$/.test(value);
export interface ClosingWorkspaceContext {
  month: string;
  closing: Closing;
  refresh: () => Promise<Closing>;
}

export default function UI14Screen({
  name = "월 마감",
  groupName = "마감",
  renderActions,
  renderRelated,
}: {
  name?: string;
  groupName?: string;
  onDirtyChange?: (dirty: boolean) => void;
  renderActions?: (context: ClosingWorkspaceContext) => ReactNode;
  renderRelated?: (context: ClosingWorkspaceContext) => ReactNode;
}) {
  const location = useLocation();
  const navigate = useNavigate();
  const [month, setMonth] = useState(() => {
    const incoming = (location.state as { closingMonth?: unknown } | null)
      ?.closingMonth;
    const remembered = sessionStorage.getItem("monthly-closing-month");
    return validMonth(incoming)
      ? incoming
      : validMonth(remembered)
        ? remembered
        : seoulMonth();
  });
  useEffect(() => {
    if (location.pathname !== "/UI-14") return;
    const incoming = (location.state as { closingMonth?: unknown } | null)
      ?.closingMonth;
    if (validMonth(incoming)) {
      sessionStorage.setItem("monthly-closing-month", incoming);
      setMonth(incoming);
    }
  }, [location.key, location.pathname, location.state]);
  const read = useCallback(() => getClosing(month), [month]);
  const query = useAdminRead(read);
  const codes = useCodes("CLOSE_CHECK");
  const data =
    !query.loading && !query.error && query.data?.month === month
      ? query.data
      : null;
  const context = data ? { month, closing: data, refresh: query.reload } : null;
  const warnings =
    data?.checks.filter((row) => row.status === "WARN").length ?? 0;
  const failures =
    data?.checks.filter((row) => row.status === "FAIL").length ?? 0;
  const unchecked = codes.codes.filter(
    (code) => !data?.checks.some((row) => row.code === code.code),
  ).length;
  const selectMonth = (value: string) => {
    sessionStorage.setItem("monthly-closing-month", value);
    setMonth(value);
  };
  return (
    <section
      data-testid="closing-screen"
      aria-label="월 마감 화면"
      className={styles.workspace}
    >
      <ScreenHeading
        screenId="UI-14"
        name={name}
        groupName={groupName}
        actions={context && renderActions?.(context)}
      />
      <PeriodBar
        screenId="UI-14"
        month={month}
        status={data?.status ?? null}
        onMonthChange={selectMonth}
        onRefresh={() => {
          void query.reload().catch(() => {});
          codes.retry();
        }}
      />
      <ErrorBanner
        error={query.error}
        onRetry={() => void query.reload().catch(() => {})}
      />
      <ErrorBanner error={codes.error} onRetry={codes.retry} />
      {(query.loading || codes.loading) && (
        <Skeleton label="마감 점검 불러오는 중" />
      )}
      {data && (
        <>
          {data.status === "CLOSED" ? (
            <LockedBanner>
              {month}는 {data.processedAt ? formatDateTime(data.processedAt) : "일시 미확인"}{" "}
              {data.actorName || "시스템 처리"}가 마감했습니다.
              {!data.canReopen &&
                " 가장 최근에 마감한 달만 해제할 수 있으며 유효 조정 참조가 없어야 합니다."}
            </LockedBanner>
          ) : (
            <div role="status" className={styles.warning}>
              {month} 마감 전 점검 {codes.codes.length}개 중{" "}
              {unchecked + warnings + failures}개를 확인해야 합니다.
              {warnings > 0 && " WARN은 마감 요청 시 확인이 필요합니다."}
            </div>
          )}
          <section
            className={tables.card}
            aria-label="마감 점검"
            data-testid="closing-check-panel"
          >
            <div className={tables.toolbar}>
              <h2>마감 점검</h2>
              <div>
                <Badge tone="success">
                  OK {data.checks.filter((row) => row.status === "OK").length}
                </Badge>{" "}
                <Badge tone="warning">WARN {warnings}</Badge>{" "}
                <Badge tone="danger">FAIL {failures}</Badge>
              </div>
            </div>
            <p className={styles.timestamp}>
              최근 점검: {data.checkedAt ? formatDateTime(data.checkedAt) : "점검 미실행"}
            </p>
            {data.checks.length === 0 && (
              <p className={tables.empty}>
                아직 점검하지 않았습니다. 다시 점검을 실행해 주세요.
              </p>
            )}
            {!codes.loading && !codes.error && codes.codes.length === 0 && (
              <p className={tables.empty}>등록된 점검 항목이 없습니다.</p>
            )}
            <ul className={styles.checks}>
              {codes.codes.map((code) => {
                const result = data.checks.find(
                  (row) => row.code === code.code,
                );
                return (
                  <li
                    key={code.code}
                    data-testid={`closing-check-${code.code.toLowerCase().replaceAll("_", "-")}`}
                  >
                    <Badge tone={result ? tones[result.status] : "neutral"}>
                      {result ? labels[result.status] : "미점검"}
                    </Badge>
                    <div className={styles.description}>
                      <strong>{code.name}</strong>
                      <p>
                        {result
                          ? `${result.count}건 · ${result.description ?? "설명 없음"}`
                          : "저장된 점검 결과가 없습니다."}
                      </p>
                    </div>
                    {destinations[code.code] && (
                      <Button
                        variant="text"
                        data-testid={`closing-open-${code.code.toLowerCase().replaceAll("_", "-")}`}
                        aria-label={`${code.name} 확인하기`}
                        onClick={() =>
                          navigate(destinations[code.code], {
                            state: { closingMonth: month, closingCheck: code.code },
                          })
                        }
                      >
                        확인하기
                      </Button>
                    )}
                  </li>
                );
              })}
            </ul>
          </section>
          {context && renderRelated?.(context)}
        </>
      )}
    </section>
  );
}
