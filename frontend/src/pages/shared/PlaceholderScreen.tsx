import { PeriodBar } from "./PeriodBar";
import styles from "./SharedScreens.module.css";

export function PlaceholderScreen({ screenId }: { screenId: string }) {
  return (
    <>
      <PeriodBar key={screenId} screenId={screenId} />
      <section className={styles.placeholder}>
        <p>이 화면은 다음 단계에서 만듭니다.</p>
      </section>
    </>
  );
}
