import { useState, type ReactNode } from "react";
import { Menu, X } from "lucide-react";
import styles from "./layout.module.css";

export function ShellLayout({
  header,
  navigation,
  tabs,
  children,
  collapsed,
  onToggle,
}: {
  header: ReactNode;
  navigation: ReactNode;
  tabs: ReactNode;
  children: ReactNode;
  collapsed: boolean;
  onToggle: () => void;
}) {
  const [mobileOpen, setMobileOpen] = useState(false);
  return (
    <div
      className={`${styles.shell} ${collapsed ? styles.collapsed : ""} ${mobileOpen ? styles.mobileOpen : ""}`}
    >
      <header className={styles.header}>
        <button
          type="button"
          className={styles.desktopToggle}
          aria-label="메뉴 접기/펼치기"
          onClick={onToggle}
        >
          <Menu size={20} />
        </button>
        <button
          type="button"
          className={styles.mobileToggle}
          aria-label={mobileOpen ? "메뉴 닫기" : "메뉴 열기"}
          aria-expanded={mobileOpen}
          onClick={() => setMobileOpen((value) => !value)}
        >
          {mobileOpen ? <X size={20} /> : <Menu size={20} />}
        </button>
        {header}
      </header>
      <aside
        className={styles.navigation}
        onClick={(event) => {
          if ((event.target as HTMLElement).closest("a")) setMobileOpen(false);
        }}
      >
        {navigation}
      </aside>
      <main className={styles.main}>
        <div className={styles.screenTabs}>{tabs}</div>
        <div className={styles.content}>{children}</div>
      </main>
    </div>
  );
}
