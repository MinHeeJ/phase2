import { useEffect, useState } from "react";
import {
  LayoutGrid,
  Database,
  Folder,
  Calendar,
  Receipt,
  ChartNoAxesCombined,
  Wallet,
  LockKeyhole,
  Sparkles,
  Settings,
  ChevronDown,
  Star,
  History,
  type LucideIcon,
} from "lucide-react";
import type { MenuNode } from "./api";
import { locateScreen } from "./registry";
import { useSharedMobile } from "./PlaceholderScreen";
import { useSharedPreferences } from "./preferences";
import styles from "./SharedScreens.module.css";

const icons: Record<string, LucideIcon> = {
  grid: LayoutGrid,
  db: Database,
  folder: Folder,
  calendar: Calendar,
  receipt: Receipt,
  chart: ChartNoAxesCombined,
  wallet: Wallet,
  lock: LockKeyhole,
  sparkle: Sparkles,
  gear: Settings,
};
export function MenuNavigation({
  menus,
  active,
  onNavigate,
}: {
  menus: MenuNode[];
  active: string;
  onNavigate: (id: string) => void;
}) {
  const mobile = useSharedMobile();
  const [expanded, setExpanded] = useState<number | null>(null);
  useEffect(() => {
    setExpanded(
      menus.find((group) =>
        group.children.some((child) => child.screenId === active),
      )?.menuId ?? null,
    );
  }, [active, menus]);
  const favorites = useSharedPreferences((state) => state.favorites);
  const recent = useSharedPreferences((state) => state.recent);
  const move = useSharedPreferences((state) => state.moveFavorite);
  const visible = (node: MenuNode) => !mobile || node.mobile;
  const shortcut = (id: string, draggable = false) => {
    const location = locateScreen(menus, id);
    if (!location || !visible(location.screen)) return null;
    return (
      <button
        type="button"
        key={id}
        draggable={draggable}
        onDragStart={(event) => event.dataTransfer.setData("text/plain", id)}
        onDragOver={draggable ? (event) => event.preventDefault() : undefined}
        onDrop={
          draggable
            ? (event) => {
                event.preventDefault();
                move(event.dataTransfer.getData("text/plain"), id);
              }
            : undefined
        }
        onClick={() => onNavigate(id)}
        aria-current={id === active ? "page" : undefined}
      >
        {draggable ? (
          <Star size={16} aria-hidden="true" />
        ) : (
          <History size={16} aria-hidden="true" />
        )}
        <span>{location.screen.name}</span>
      </button>
    );
  };
  return (
    <nav className={styles.navigation} aria-label="화면 탐색">
      {favorites.length > 0 && (
        <section aria-label="즐겨찾기 목록">
          <h2>즐겨찾기</h2>
          {favorites.map((id) => shortcut(id, true))}
        </section>
      )}
      {recent.length > 0 && (
        <section aria-label="최근 본 화면">
          <h2>최근 본 화면</h2>
          {recent.map((id) => shortcut(id))}
        </section>
      )}
      {menus
        .filter(
          (group) =>
            visible(group) && (group.screenId || group.children.some(visible)),
        )
        .map((group) => {
          const open =
            expanded === null
              ? group.children.some((child) => child.screenId === active)
              : expanded === group.menuId;
          const Icon = group.icon ? icons[group.icon] : null;
          const target =
            group.screenId ??
            (group.children.length === 1 &&
            group.children[0].screenId === "UI-16"
              ? "UI-16"
              : null);
          return (
            <section
              key={group.menuId}
              className={styles.menuGroup}
              onMouseEnter={(event) =>
                event.currentTarget.style.setProperty(
                  "--flyout-top",
                  `${event.currentTarget.getBoundingClientRect().top}px`,
                )
              }
              onFocus={(event) =>
                event.currentTarget.style.setProperty(
                  "--flyout-top",
                  `${event.currentTarget.getBoundingClientRect().top}px`,
                )
              }
            >
              <button
                type="button"
                aria-expanded={target ? undefined : open}
                className={styles.groupButton}
                title={group.name}
                aria-label={group.name}
                aria-current={target === active ? "page" : undefined}
                onClick={() =>
                  target
                    ? onNavigate(target)
                    : setExpanded(open ? -1 : group.menuId)
                }
              >
                {Icon && <Icon aria-hidden="true" size={20} />}
                <span>{group.name}</span>
                {!target && (
                  <ChevronDown
                    className={styles.groupChevron}
                    size={14}
                    aria-hidden="true"
                  />
                )}
              </button>
              {!target && (
                <div
                  className={`${styles.menuChildren} ${open ? styles.menuOpen : ""}`}
                >
                  {group.children.filter(visible).map((child) => {
                    const ChildIcon = icons[child.icon ?? group.icon ?? ""];
                    return (
                      <button
                        type="button"
                        key={child.menuId}
                        onClick={() => {
                          setExpanded(group.menuId);
                          if (child.screenId) onNavigate(child.screenId);
                        }}
                        aria-current={
                          child.screenId === active ? "page" : undefined
                        }
                      >
                        {ChildIcon && (
                          <ChildIcon aria-hidden="true" size={16} />
                        )}
                        <span>{child.name}</span>
                      </button>
                    );
                  })}
                </div>
              )}
            </section>
          );
        })}
    </nav>
  );
}
