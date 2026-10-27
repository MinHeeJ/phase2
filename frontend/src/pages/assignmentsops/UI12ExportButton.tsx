import { useState } from "react";
import { Button } from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { failure } from "../admin/client";
import { downloadAssignments, type AssignmentFilter } from "./client";

export function UI12ExportButton({ filter }: { filter: AssignmentFilter }) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ReturnType<typeof failure> | null>(null);
  const [message, setMessage] = useState("");
  const run = async () => {
    if (pending) return;
    setPending(true);
    setError(null);
    setMessage("");
    try {
      await downloadAssignments(filter);
      setMessage("엑셀 파일을 내려받았습니다.");
    } catch (e) {
      setError(failure(e));
    } finally {
      setPending(false);
    }
  };
  return (
    <Can perm="UI-12.download">
      <section
        data-screen-id="SCR-UI-12#export"
        data-testid="assignment-export-control"
      >
        <Button
          data-testid="assignment-export"
          pending={pending}
          onClick={() => void run()}
        >
          엑셀 다운로드
        </Button>
        {message && <span role="status">{message}</span>}
        <ErrorBanner
          error={error}
          pending={pending}
          onRetry={() => void run()}
        />
      </section>
    </Can>
  );
}
