import { useEffect, useState, type ReactNode } from "react";
import { Construction } from "lucide-react";
import { Button } from "../../shared/Controls";
import type { MenuNode } from "./api";
import { PeriodBar } from "./PeriodBar";
import { ScreenHeading } from "./ScreenHeading";
import styles from "./SharedScreens.module.css";

export function useSharedMobile() {
  const [mobile, setMobile] = useState(() => window.innerWidth <= 767);
  useEffect(() => {
    const resize = () => setMobile(window.innerWidth <= 767);
    window.addEventListener("resize", resize);
    return () => window.removeEventListener("resize", resize);
  }, []);
  return mobile;
}
export function SharedMobileGuard({
  mobileAllowed,
  children,
  onDashboard,
}: {
  mobileAllowed: boolean;
  children: ReactNode;
  onDashboard?: () => void;
}) {
  const mobile = useSharedMobile();
  if (!mobileAllowed && mobile)
    return (
      <section className={styles.placeholder}>
        <p>PC에서 이용해 주세요</p>
        {onDashboard ? (
          <Button onClick={onDashboard}>대시보드로</Button>
        ) : (
          <a href="/UI-16">대시보드로</a>
        )}
      </section>
    );
  return <>{children}</>;
}
export function PlaceholderScreen({
  screen,
  group,
  onDashboard,
}: {
  screen: Pick<MenuNode, "name" | "screenId" | "mobile">;
  group?: MenuNode | null;
  onDashboard?: () => void;
}) {
  const id = screen.screenId!;
  return (
    <SharedMobileGuard mobileAllowed={screen.mobile} onDashboard={onDashboard}>
      <section aria-label={`${screen.name} 화면`}>
        <ScreenHeading
          screenId={id}
          name={screen.name}
          groupName={group?.name}
        />
        <PeriodBar key={id} screenId={id} />
        <div className={styles.placeholder}>
          <Construction size={28} aria-hidden="true" />
          <p>이 화면은 다음 단계에서 만듭니다.</p>
        </div>
      </section>
    </SharedMobileGuard>
  );
}
