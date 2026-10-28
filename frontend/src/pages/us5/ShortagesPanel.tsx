import { useCallback, useEffect, useState } from "react";
import { Badge, Button, DataTable } from "../../shared/Controls";
import {
  downloadShortages,
  listShortages,
  type ShortageFilter,
  type ShortagePage,
  type ShortageRow,
} from "./client";

// SCR-UI-11: the monthly shortages panel is mounted by the annual comparison screen.
export function ShortagesPanel({
  year,
  projectId,
  job,
  onReset,
  onOpenProject,
}: {
  year: number;
  projectId?: number;
  job?: string;
  onReset: () => void;
  onOpenProject: (id: number) => void;
}) {
  const [page, setPage] = useState(1);
  const [data, setData] = useState<ShortagePage | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    setPage(1);
  }, [year, projectId, job]);
  useEffect(() => {
    let active = true;
    setLoading(true);
    setError("");
    listShortages({ year, projectId, job, page })
      .then((value) => {
        if (active) setData(value);
      })
      .catch(() => {
        if (active) setError("목록을 불러오지 못했습니다.");
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [year, projectId, job, page, retry]);
  const exportFile = useCallback(async () => {
    try {
      await downloadShortages({ year, projectId, job } as ShortageFilter);
    } catch {
      setError("다운로드에 실패했습니다.");
    }
  }, [year, projectId, job]);
  const columns = [
    {
      key: "project",
      label: "프로젝트",
      render: (r: ShortageRow) => (
        <Button
          variant="text"
          data-testid={`shortage-project-${r.projectId}-${r.job}-${r.month}`}
          onClick={() => onOpenProject(r.projectId)}
        >
          {r.projectName}
        </Button>
      ),
    },
    { key: "job", label: "직무", render: (r: ShortageRow) => r.jobName },
    { key: "month", label: "월", render: (r: ShortageRow) => r.month },
    {
      key: "plan",
      label: "계획 MM",
      render: (r: ShortageRow) => Number(r.planMm).toFixed(2),
    },
    {
      key: "actual",
      label: "실제·예정 MM",
      render: (r: ShortageRow) => Number(r.actualMm).toFixed(2),
    },
    {
      key: "shortage",
      label: "부족",
      render: (r: ShortageRow) => (
        <Badge tone="danger">부족 {Number(r.shortageMm).toFixed(2)}</Badge>
      ),
    },
  ];
  return (
    <section
      data-testid="shortages-panel"
      role="tabpanel"
      aria-label="미충원 직무"
    >
      <Button data-testid="shortages-export" onClick={exportFile}>
        엑셀 다운로드
      </Button>
      {loading ? (
        <p>불러오는 중입니다.</p>
      ) : error ? (
        <div role="alert">
          {error} <Button onClick={() => setRetry(retry + 1)}>다시 시도</Button>
        </div>
      ) : data && data.rows.length ? (
        <>
          <DataTable
            columns={columns}
            rows={data.rows}
            rowKey={(r) => `${r.projectId}-${r.job}-${r.month}`}
            total={data.total}
          />
          <div>
            <Button disabled={page <= 1} onClick={() => setPage(page - 1)}>
              이전
            </Button>{" "}
            {page}{" "}
            <Button
              disabled={page * 50 >= data.total}
              onClick={() => setPage(page + 1)}
            >
              다음
            </Button>
          </div>
        </>
      ) : (
        <div>
          {projectId || job ? (
            <>
              조건에 맞는 결과가 없습니다.{" "}
              <Button onClick={onReset}>조건 초기화</Button>
            </>
          ) : (
            "미충원 직무가 없습니다."
          )}
        </div>
      )}
    </section>
  );
}
