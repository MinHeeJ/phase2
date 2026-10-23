import { useEffect, useState } from "react";
import { Button, Skeleton } from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import type { ApiFailure } from "../../shared/api";
import {
  listPartnerAssignments,
  listPartnerHistory,
  partnerFailure,
  type PartnerAssignment,
  type PartnerChange,
} from "./client";
import styles from "./Partners.module.css";

export function PartnerActivity({
  id,
  initialTab = "assignments",
}: {
  id: string;
  initialTab?: "assignments" | "history";
}) {
  const [tab, setTab] = useState(initialTab),
    [assignments, setAssignments] = useState<PartnerAssignment[]>([]),
    [history, setHistory] = useState<PartnerChange[]>([]),
    [pending, setPending] = useState(true),
    [error, setError] = useState<ApiFailure | null>(null),
    [revision, setRevision] = useState(0);
  useEffect(() => {
    let current = true;
    setPending(true);
    setError(null);
    const request =
      tab === "assignments"
        ? listPartnerAssignments(id).then((rows) => {
            if (current) setAssignments(rows);
          })
        : listPartnerHistory(id).then((rows) => {
            if (current) setHistory(rows);
          });
    request
      .catch((e) => {
        if (current) setError(partnerFailure(e));
      })
      .finally(() => {
        if (current) setPending(false);
      });
    return () => {
      current = false;
    };
  }, [id, tab, revision]);
  return (
    <Can perm="UI-15.read">
      <section data-testid="partner-activity" className={styles.region}>
        <div role="tablist">
          <Button
            data-testid="partner-assignments-tab"
            role="tab"
            aria-selected={tab === "assignments"}
            onClick={() => setTab("assignments")}
          >
            거래 내역
          </Button>
          <Button
            data-testid="partner-history-tab"
            role="tab"
            aria-selected={tab === "history"}
            onClick={() => setTab("history")}
          >
            변경 이력
            {tab === "history" && !pending && !error
              ? ` (${history.length})`
              : ""}
          </Button>
        </div>
        <ErrorBanner error={error} onRetry={() => setRevision((r) => r + 1)} />
        {pending ? (
          <Skeleton />
        ) : (
          !error && (
            <div
              role="tabpanel"
              data-testid={`partner-${tab}-panel`}
              className={styles.tableScroll}
            >
              {tab === "assignments" ? (
                assignments.length ? (
                  <table>
                    <thead>
                      <tr>
                        {[
                          "프로젝트",
                          "이름",
                          "소속",
                          "직무",
                          "투입률",
                          "철수일",
                        ].map((label) => (
                          <th
                            data-testid={`partner-activity-assignment-heading-${["프로젝트", "이름", "소속", "직무", "투입률", "철수일"].indexOf(label)}`}
                            key={label}
                          >
                            {label}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {assignments.map((row, i) => (
                        <tr
                          data-testid={`partner-assignment-${i}`}
                          key={`${row.projectName}-${row.developerName}-${i}`}
                        >
                          <td>{row.projectName}</td>
                          <td>{row.developerName}</td>
                          <td>{row.affiliation}</td>
                          <td>{row.jobName}</td>
                          <td>{row.assignmentRate}</td>
                          <td>{row.endDate}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                ) : (
                  <p>지금 투입 중인 내역이 없습니다.</p>
                )
              ) : history.length ? (
                <table>
                  <thead>
                    <tr>
                      {["일시", "항목", "이전", "이후", "처리자"].map(
                        (label) => (
                          <th
                            data-testid={`partner-activity-history-heading-${["일시", "항목", "이전", "이후", "처리자"].indexOf(label)}`}
                            key={label}
                          >
                            {label}
                          </th>
                        ),
                      )}
                    </tr>
                  </thead>
                  <tbody>
                    {history.map((row, i) => (
                      <tr
                        data-testid={`partner-history-${i}`}
                        key={`${row.changedAt}-${i}`}
                      >
                        <td>{row.changedAt}</td>
                        <td>{row.itemName}</td>
                        <td>{row.before ?? "–"}</td>
                        <td>{row.after ?? "–"}</td>
                        <td>{row.actorName}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              ) : (
                <p>아직 바꾼 기록이 없습니다.</p>
              )}
            </div>
          )
        )}
      </section>
    </Can>
  );
}
