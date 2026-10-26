import { useState } from "react";
import { Button } from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { failure } from "../admin/client";
import { downloadProjectBoard, type ProjectBoardExportFilter } from "./client";

// SCR-UI-31: title-row download region. The board supplies its applied filters, not draft filters or paging.
export function ProjectBoardExportButton({
  filter,
}: {
  filter: ProjectBoardExportFilter;
}) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ReturnType<typeof failure> | null>(null);
  const [message, setMessage] = useState("");
  const run = async () => {
    if (pending) return;
    setPending(true);
    setError(null);
    setMessage("");
    try {
      await downloadProjectBoard(filter);
      setMessage("엑셀 파일을 내려받았습니다.");
    } catch (e) {
      setError(failure(e));
    } finally {
      setPending(false);
    }
  };
  return (
    <Can perm="UI-31.download">
      <section
        data-screen-id="SCR-UI-31"
        data-testid="project-board-export-control"
      >
        <Button
          data-testid="project-board-export"
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
