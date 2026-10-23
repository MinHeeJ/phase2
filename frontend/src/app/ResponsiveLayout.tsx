import { useEffect, useState, type ReactNode } from "react";
import { Menu, X } from "lucide-react";
import styles from "./ResponsiveLayout.module.css";

export function ResponsiveLayout({
  header,
  menu,
  tabs,
  children,
  collapsed = false,
  onCollapsedChange,
}: {
  header: ReactNode;
  menu: ReactNode;
  tabs?: ReactNode;
  children: ReactNode;
  collapsed?: boolean;
  onCollapsedChange?: (collapsed: boolean) => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const [width, setWidth] = useState(() => window.innerWidth);
  const menuExpanded =
    width >= 1280 && onCollapsedChange ? !collapsed : expanded;
  useEffect(() => {
    const resize = () => {
      setWidth(window.innerWidth);
      setExpanded(false);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setExpanded(false);
    };
    window.addEventListener("resize", resize);
    window.addEventListener("keydown", escape);
    return () => {
      window.removeEventListener("resize", resize);
      window.removeEventListener("keydown", escape);
    };
  }, []);
  return (
    <div
      data-testid="responsive-layout"
      className={`${styles.layout} ${collapsed ? styles.collapsed : ""} ${expanded ? styles.expanded : ""}`}
    >
      <header className={styles.header}>
        <button
          data-testid="responsive-menu-toggle"
          type="button"
          className={styles.menuToggle}
          aria-label={menuExpanded ? "메뉴 닫기" : "메뉴 열기"}
          aria-expanded={menuExpanded}
          onClick={() =>
            width >= 1280 && onCollapsedChange
              ? onCollapsedChange(!collapsed)
              : setExpanded(!expanded)
          }
        >
          {expanded ? <X /> : <Menu />}
        </button>
        {header}
      </header>
      <aside
        className={styles.menu}
        aria-label="업무 메뉴"
        data-compact={width >= 768 && (width < 1280 ? !expanded : collapsed)}
        onClick={(event) => {
          const button = (event.target as HTMLElement).closest("nav button");
          if (button && !button.hasAttribute("aria-expanded"))
            setExpanded(false);
        }}
      >
        {menu}
      </aside>
      <main data-testid="responsive-main" className={styles.main}>
        <div className={styles.screenTabs}>{tabs}</div>
        <div className={styles.content}>{children}</div>
      </main>
    </div>
  );
}
