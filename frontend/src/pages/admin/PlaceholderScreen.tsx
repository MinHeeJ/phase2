import { useCallback, useState } from "react";
import { readData } from "../../shared/api";
import { Button, Skeleton, LockedBanner } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "./useAdminResource";
import styles from "./CommonCodes.module.css";

export interface PlaceholderMenu {
  menuId: number;
  name: string;
  screenId: string | null;
  mobile: boolean;
  children: PlaceholderMenu[];
}
export type PlaceholderPeriod = "YEAR" | "MONTH" | "YEAR_CUM" | "NONE";
function locate(
  menus: PlaceholderMenu[],
  id: string,
  parents: string[] = [],
): { menu: PlaceholderMenu; parents: string[] } | null {
  for (const menu of menus) {
    if (menu.screenId === id) return { menu, parents };
    const child = locate(menu.children, id, [...parents, menu.name]);
    if (child) return child;
  }
  return null;
}
export default function PlaceholderScreen({
  screenId,
  periodType = "NONE",
  onFavorite,
}: {
  screenId: string;
  periodType?: PlaceholderPeriod;
  onFavorite?: (id: string) => void;
}) {
  const read = useCallback(() => readData<PlaceholderMenu[]>("/api/menus"), []),
    menus = useAdminRead(read);
  const [period, setPeriod] = useState(() =>
    new Intl.DateTimeFormat("sv-SE", {
      timeZone: "Asia/Seoul",
      year: "numeric",
      month: "2-digit",
    }).format(new Date()),
  );
  const found = locate(menus.data ?? [], screenId),
    compat = screenId === "UI-22";
  if (menus.loading && !menus.data) return <Skeleton />;
  if (menus.error)
    return (
      <ErrorBanner
        error={menus.error}
        onRetry={() => {
          void menus.reload().catch(() => {});
        }}
      />
    );
  if (!found && !compat) return <p>화면을 찾을 수 없습니다.</p>;
  const content = (
    <>
      <div className={styles.heading}>
        <h1>
          {found?.menu.name ?? screenId} <small>{screenId}</small>
        </h1>
        {found && (
          <Button aria-label="즐겨찾기" onClick={() => onFavorite?.(screenId)}>
            ☆
          </Button>
        )}
      </div>
      <p>{found?.parents.join(" › ")}</p>
      {periodType !== "NONE" && (
        <label>
          {periodType === "YEAR" ? "선택 연도" : "선택 연월"}{" "}
          <input
            aria-label="선택 기간"
            type={periodType === "YEAR" ? "number" : "month"}
            min={periodType === "YEAR" ? "1" : undefined}
            max={periodType === "YEAR" ? "9999" : undefined}
            value={periodType === "YEAR" ? period.slice(0, 4) : period}
            onChange={(e) =>
              setPeriod(
                periodType === "YEAR"
                  ? `${e.target.value.padStart(4, "0")}-01`
                  : e.target.value,
              )
            }
          />
        </label>
      )}
      {periodType === "YEAR_CUM" && <p>1~{Number(period.slice(5))}월 누계</p>}
      {periodType === "MONTH" && <PlaceholderMonth month={period} />}
      <p>이 화면은 다음 단계에서 만듭니다.</p>
    </>
  );
  return found?.menu.mobile === false ? (
    <>
      <div className={styles.mobileOnly}>
        <p>PC에서 이용해 주세요</p>
        <a href="/UI-16">대시보드로</a>
      </div>
      <section className={styles.desktopOnly}>{content}</section>
    </>
  ) : (
    <section>{content}</section>
  );
}
function PlaceholderMonth({ month }: { month: string }) {
  const read = useCallback(
      () =>
        readData<{ month: string; status: string }[]>(
          `/api/month-close?from=${encodeURIComponent(month)}&to=${encodeURIComponent(month)}`,
        ),
      [month],
    ),
    resource = useAdminRead(read);
  if (resource.loading) return <Skeleton />;
  if (resource.error)
    return (
      <ErrorBanner
        error={resource.error}
        onRetry={() => {
          void resource.reload().catch(() => {});
        }}
      />
    );
  const closed = resource.data?.some(
    (r) => r.month === month && r.status === "CLOSED",
  );
  return (
    <>
      <p>{closed ? "마감" : "작업중"}</p>
      {closed && (
        <LockedBanner>
          {month}는 마감되었습니다. 고칠 내용은 다음 달 전월 조정으로
          입력하세요.
        </LockedBanner>
      )}
    </>
  );
}
