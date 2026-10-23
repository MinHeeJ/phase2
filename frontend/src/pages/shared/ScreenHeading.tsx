import { useState, type ReactNode } from "react";
import { ChevronRight, Star } from "lucide-react";
import { Button } from "../../shared/Controls";
import { useSharedPreferences } from "./preferences";
import styles from "./SharedScreens.module.css";

export function ScreenHeading({
  screenId,
  name,
  groupName,
  actions,
}: {
  screenId: string;
  name: string;
  groupName?: string;
  actions?: ReactNode;
}) {
  const favorites = useSharedPreferences((state) => state.favorites);
  const toggle = useSharedPreferences((state) => state.toggleFavorite);
  const [limit, setLimit] = useState(false);
  const favorite = favorites.includes(screenId);
  return (
    <header className={styles.pageHeading}>
      <nav className={styles.breadcrumb} aria-label="현재 위치">
        {groupName && (
          <>
            <span>{groupName}</span>
            <ChevronRight size={12} aria-hidden="true" />
          </>
        )}
        <span>{name}</span>
      </nav>
      <div className={styles.title}>
        <h1>{name}</h1>
        <Button
          variant="text"
          aria-label="즐겨찾기"
          aria-pressed={favorite}
          onClick={() => setLimit(!toggle(screenId))}
        >
          <Star
            size={18}
            aria-hidden="true"
            fill={favorite ? "currentColor" : "none"}
          />
        </Button>
        <span className={styles.screenId}>{screenId}</span>
        {actions && <div className={styles.headingActions}>{actions}</div>}
      </div>
      {limit && (
        <p role="status">즐겨찾기는 최대 10개까지 등록할 수 있습니다.</p>
      )}
    </header>
  );
}
