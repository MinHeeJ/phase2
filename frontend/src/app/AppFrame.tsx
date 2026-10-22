import { useEffect, useState, type ReactNode } from "react";
import { Menu, PanelLeftClose } from "lucide-react";
import styles from "./AppFrame.module.css";

export function AppFrame({
  header,
  navigation,
  screenTabs,
  children,
  collapsed: controlledCollapsed,
  onCollapsedChange,
}: {
  header: ReactNode;
  navigation: ReactNode;
  screenTabs?: ReactNode;
  children: ReactNode;
  collapsed?: boolean;
  onCollapsedChange?: (value: boolean) => void;
}) {
  const [open, setOpen] = useState(false);
  const [localCollapsed, setCollapsed] = useState(false);
  const collapsed = controlledCollapsed ?? localCollapsed;
  const [tablet, setTablet] = useState(
    () =>
      window.matchMedia?.("(min-width: 768px) and (max-width: 1279px)")
        .matches ?? false,
  );
  useEffect(() => {
    const media = window.matchMedia?.(
      "(min-width: 768px) and (max-width: 1279px)",
    );
    if (!media) return;
    const update = () => setTablet(media.matches);
    media.addEventListener("change", update);
    return () => media.removeEventListener("change", update);
  }, []);
  return (
    <div
      data-testid="app-frame"
      data-navigation-collapsed={(collapsed || tablet) && !open}
      className={`${styles.frame} ${collapsed || tablet ? styles.collapsed : ""} ${open ? styles.open : ""}`}
    >
      <header className={styles.header}>
        <button
          data-testid="mobile-menu-toggle"
          type="button"
          className={styles.mobileToggle}
          aria-label="메뉴 열기"
          aria-expanded={open}
          onClick={() => setOpen(!open)}
        >
          <Menu size={20} />
        </button>
        {header}
      </header>
      <aside
        className={styles.sidebar}
        aria-label="화면 탐색"
        onClick={(event) => {
          if ((event.target as HTMLElement).closest("a")) setOpen(false);
        }}
      >
        <button
          data-testid="sidebar-menu-toggle"
          type="button"
          aria-label={open ? "메뉴 닫기" : "메뉴 열기·접기"}
          onClick={() => {
            setCollapsed(!collapsed);
            onCollapsedChange?.(!collapsed);
            setOpen(!open);
          }}
        >
          <PanelLeftClose size={20} />
        </button>
        {navigation}
      </aside>
      <div className={styles.main}>
        <div className={styles.screenTabs}>{screenTabs}</div>
        <main data-testid="app-content" className={styles.content}>
          {children}
        </main>
      </div>
    </div>
  );
}
