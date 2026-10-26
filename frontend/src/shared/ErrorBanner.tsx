import { ApiFailure } from "./http";
import styles from "./ui.module.css";

export function ErrorBanner({
  error,
  onRetry,
}: {
  error: Error | null;
  onRetry: () => void;
}) {
  if (!error) return null;
  const requestId = error instanceof ApiFailure ? error.requestId : undefined;
  return (
    <div role="alert" className={styles.errorBanner}>
      <span>{error.message}</span>
      {requestId && <span>문의 번호: {requestId}</span>}
      <button type="button" onClick={onRetry}>
        다시 시도
      </button>
    </div>
  );
}
