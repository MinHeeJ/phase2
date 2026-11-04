import { useEffect, useRef, useState } from "react";
import { Button } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import { downloadDashboard } from "./dashboardExportClient";

// SCR-UI-16 / A-16-EXPORT: mounted in the existing dashboard, not a second route or shell.
export function DashboardDownload({
  month,
  disabled = false,
}: {
  month: string;
  disabled?: boolean;
}) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ApiFailure | null>(null);
  const active = useRef<AbortController | null>(null);
  useEffect(() => {
    active.current?.abort();
    active.current = null;
    setPending(false);
    setError(null);
    return () => {
      active.current?.abort();
      active.current = null;
    };
  }, [month]);
  const download = async () => {
    if (active.current || disabled) return;
    const controller = new AbortController();
    active.current = controller;
    setPending(true);
    setError(null);
    try {
      await downloadDashboard(month, controller.signal);
    } catch (failure) {
      if (!controller.signal.aborted)
        setError(
          failure instanceof ApiFailure
            ? failure
            : new ApiFailure("엑셀 다운로드에 실패했습니다.", null, null),
        );
    } finally {
      if (active.current === controller) {
        active.current = null;
        setPending(false);
      }
    }
  };
  return (
    <div data-testid="dashboard-download" data-screen-id="SCR-UI-16">
      <Button
        data-testid="dashboard-export-button"
        disabled={disabled}
        pending={pending}
        onClick={() => void download()}
      >
        엑셀 다운로드
      </Button>
      <ErrorBanner error={error} />
      {error && (
        <Button
          data-testid="dashboard-export-retry"
          disabled={disabled}
          pending={pending}
          onClick={() => void download()}
        >
          다시 시도
        </Button>
      )}
      <p>
        선택 기준월 {month} · 원 단위 · 오늘 기준 예측 · OPEN 자료는 조회와
        다운로드 사이에 변경될 수 있습니다.
      </p>
    </div>
  );
}
