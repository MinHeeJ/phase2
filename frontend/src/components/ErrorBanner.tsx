import { ApiRequestError } from "../services/api";
import styles from "./UiKit.module.css";

export function ErrorBanner({
  error,
  onRetry,
}: {
  error: Error;
  onRetry: () => void;
}) {
  const requestId = error instanceof ApiRequestError ? error.requestId : null;
  return (
    <section
      data-testid={`error-banner-${Math.random().toString(36).slice(2)}`}
      className={styles.errorBanner}
      role="alert"
    >
      <div>{error.message}</div>
      <div>문의 번호: {requestId ?? "서버 연결 전 실패"}</div>
      <button
        data-testid={`error-retry-${Math.random().toString(36).slice(2)}`}
        type="button"
        onClick={onRetry}
      >
        다시 시도
      </button>
    </section>
  );
}
