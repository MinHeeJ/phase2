import { useState } from "react";
import { api } from "../../services/api";
import type { MenuNode } from "../../services/types";
import {
  Badge,
  Button,
  ConfirmDialog,
  LockedBanner,
  Modal,
  TextInput,
} from "../../shared";
import { AdminLoadState, useAdminLoad } from "./adminState";
import styles from "./Admin.module.css";

export type PlaceholderScreenProps = {
  screenId: string;
  name?: string;
  breadcrumb?: string;
  mobile?: boolean;
  periodType?: "YEAR" | "MONTH" | "YEAR_CUM" | "NONE";
  period?: string;
  onPeriodChange?: (value: string) => void;
  onFavorite?: () => void;
  favorite?: boolean;
  isMobile?: boolean;
};
export function PlaceholderScreen({
  screenId,
  name,
  breadcrumb,
  mobile = true,
  periodType = "NONE",
  period,
  onPeriodChange,
  onFavorite,
  favorite,
  isMobile = false,
}: PlaceholderScreenProps) {
  const menus = useAdminLoad(
    () =>
      name ? Promise.resolve<MenuNode[]>([]) : api<MenuNode[]>("/api/menus"),
    [name],
  );
  const find = (nodes: MenuNode[]): MenuNode | undefined => {
    for (const node of nodes) {
      if (node.screenId === screenId) return node;
      const result = find(node.children);
      if (result) return result;
    }
    return undefined;
  };
  const menu = find(menus.data ?? []);
  const title = name ?? menu?.name;
  const [localPeriod, setLocalPeriod] = useState(() =>
    new Intl.DateTimeFormat("sv-SE", {
      year: "numeric",
      month: "2-digit",
      timeZone: "Asia/Seoul",
    }).format(new Date()),
  );
  const chosen = period ?? localPeriod;
  const closes = useAdminLoad(
    () =>
      periodType === "MONTH"
        ? api<{ month: string; status: string }[]>(
            `/api/month-close?from=${encodeURIComponent(chosen)}&to=${encodeURIComponent(chosen)}`,
          )
        : Promise.resolve([]),
    [periodType, chosen],
  );
  if (isMobile && !(menu?.mobile ?? mobile))
    return (
      <section>
        <p>PC에서 이용해 주세요</p>
        <a href="/UI-16">대시보드로</a>
      </section>
    );
  const status = closes.data?.find((row) => row.month === chosen)?.status;
  return (
    <section className={styles.page}>
      <AdminLoadState
        loading={menus.loading}
        error={menus.error}
        retry={menus.reload}
      />
      {!menus.loading && !menus.error && (
        <>
          <p>{breadcrumb ?? ""}</p>
          <header className={styles.heading}>
            <h1>{title ?? screenId}</h1>
            {onFavorite && (
              <Button aria-label="즐겨찾기" onClick={onFavorite}>
                {favorite ? "★" : "☆"}
              </Button>
            )}
            <span>{screenId}</span>
          </header>
          {!title && screenId !== "UI-22" ? (
            <p>메뉴 정보를 찾을 수 없습니다. 메뉴를 다시 조회해 주세요.</p>
          ) : (
            <>
              {periodType !== "NONE" && (
                <label>
                  기간{" "}
                  <input
                    aria-label="기간"
                    type={periodType === "YEAR" ? "number" : "month"}
                    min={periodType === "YEAR" ? 1 : "0001-01"}
                    max={periodType === "YEAR" ? 9999 : "9999-12"}
                    value={periodType === "YEAR" ? chosen.slice(0, 4) : chosen}
                    onChange={(e) => {
                      setLocalPeriod(e.target.value);
                      onPeriodChange?.(e.target.value);
                    }}
                  />
                </label>
              )}
              {periodType === "YEAR_CUM" && (
                <p>1~{Number(chosen.slice(5, 7))}월 누계</p>
              )}
              {periodType === "MONTH" && (
                <>
                  <AdminLoadState
                    loading={closes.loading}
                    error={closes.error}
                    retry={closes.reload}
                  />
                  {!closes.loading && !closes.error && (
                    <Badge>{status === "CLOSED" ? "마감" : "작업중"}</Badge>
                  )}
                  {status === "CLOSED" && (
                    <LockedBanner>
                      {chosen}는 마감되었습니다. 고칠 내용은 다음 달 전월
                      조정으로 입력하세요.
                    </LockedBanner>
                  )}
                </>
              )}
              <p>이 화면은 다음 단계에서 만듭니다.</p>
            </>
          )}
        </>
      )}
    </section>
  );
}
export function AdminScreenSearch({
  menus,
  onSelect,
  onClose,
}: {
  menus: MenuNode[];
  onSelect: (menu: MenuNode) => void;
  onClose: () => void;
}) {
  const [text, setText] = useState("");
  const leaves = (nodes: MenuNode[]): MenuNode[] =>
    nodes.flatMap((node) => (node.screenId ? [node] : leaves(node.children)));
  const query = text.toLocaleLowerCase();
  const results = leaves(menus).filter(
    (menu) =>
      menu.name.toLocaleLowerCase().includes(query) ||
      menu.screenId?.toLocaleLowerCase().includes(query),
  );
  return (
    <Modal title="화면 검색" onClose={onClose}>
      <TextInput label="화면 이름 또는 ID" value={text} onChange={setText} />
      {results.length ? (
        results.map((menu) => (
          <Button key={menu.menuId} onClick={() => onSelect(menu)}>
            {menu.name} · {menu.screenId}
          </Button>
        ))
      ) : (
        <p>조건에 맞는 결과가 없습니다</p>
      )}
    </Modal>
  );
}
export function AdminTabLimitDialog({
  candidateName,
  onCancel,
  onConfirm,
}: {
  candidateName: string;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  return (
    <ConfirmDialog
      message={`가장 오래 안 본 탭 ${candidateName}을 닫고 새 화면을 여나요?`}
      onCancel={onCancel}
      onConfirm={onConfirm}
      confirmLabel="닫기 확인"
    />
  );
}
