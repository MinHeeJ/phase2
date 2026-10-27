import { Badge } from "../../shared/Controls";
import { Link } from "react-router-dom";
import { issueToday, type IssueRow, type IssueListRow } from "./client";
import styles from "./Issues.module.css";

export function IssueTable({
  items,
  aggregate = false,
  onEdit,
  readOnly = false,
}: {
  items: (IssueRow | IssueListRow)[];
  aggregate?: boolean;
  onEdit: (row: IssueRow | IssueListRow) => void;
  readOnly?: boolean;
}) {
  const today = issueToday();
  return (
    <div data-testid="issue-table" className={styles.tableScroll}>
      <table className={styles.table}>
        <thead>
          <tr>
            {[
              "중요도",
              aggregate ? "제목" : "제목 · 조치 내용",
              ...(aggregate ? ["프로젝트"] : []),
              "담당자",
              "기한",
              "상태",
              ...(aggregate ? ["조치 내용"] : []),
              "등록일",
            ].map((label) => (
              <th
                data-testid={`issue-column-${["중요도", "제목", "제목 · 조치 내용", "프로젝트", "담당자", "기한", "상태", "조치 내용", "등록일"].indexOf(label)}`}
                key={label}
              >
                {label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {items.map((row) => {
            const overdue =
              row.issue_stts_cd !== "CLOSED" &&
              !!row.ddln_dt &&
              row.ddln_dt < today;
            const days = overdue
              ? Math.floor(
                  (Date.parse(`${today}T00:00:00Z`) -
                    Date.parse(`${row.ddln_dt}T00:00:00Z`)) /
                    86400000,
                )
              : 0;
            return (
              <tr
                key={row.issue_id}
                data-testid={`issue-row-${row.issue_id}`}
                tabIndex={readOnly ? undefined : 0}
                className={readOnly ? undefined : styles.editable}
                onClick={() => {
                  if (!readOnly) onEdit(row);
                }}
                onKeyDown={(event) => {
                  if (
                    !readOnly &&
                    event.target === event.currentTarget &&
                    (event.key === "Enter" || event.key === " ")
                  ) {
                    event.preventDefault();
                    onEdit(row);
                  }
                }}
              >
                <td>
                  <Badge
                    tone={
                      row.svrt_cd === "HIGH"
                        ? "danger"
                        : row.svrt_cd === "MID"
                          ? "warning"
                          : "neutral"
                    }
                  >
                    {{ HIGH: "높음", MID: "중간", LOW: "낮음" }[row.svrt_cd]}
                  </Badge>
                </td>
                <td className={styles.description}>
                  {row.ttl_nm}
                  {!aggregate && row.actn_desc && (
                    <small>{row.actn_desc}</small>
                  )}
                </td>
                {aggregate && (
                  <td>
                    <Link
                      data-testid={`issue-project-${row.issue_id}`}
                      className={styles.link}
                      to={`/UI-01?open=${encodeURIComponent(row.prj_id)}&tab=issues`}
                      onClick={(event) => event.stopPropagation()}
                    >
                      {"prj_nm" in row ? row.prj_nm : row.prj_id}
                    </Link>
                  </td>
                )}
                <td>{row.chrg_nm || "–"}</td>
                <td className={overdue ? styles.overdue : undefined}>
                  {row.ddln_dt?.slice(5) ?? "–"}
                  {overdue && <small>{days}일 지남</small>}
                </td>
                <td>
                  <Badge
                    tone={
                      row.issue_stts_cd === "OPEN"
                        ? "info"
                        : row.issue_stts_cd === "DOING"
                          ? "warning"
                          : "locked"
                    }
                  >
                    {
                      { OPEN: "열림", DOING: "조치 중", CLOSED: "종료" }[
                        row.issue_stts_cd
                      ]
                    }
                  </Badge>
                </td>
                {aggregate && (
                  <td className={styles.description}>{row.actn_desc || "–"}</td>
                )}
                <td>{row.reg_dt}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
      {!items.length && (
        <p className={styles.empty}>
          {aggregate
            ? "조건에 맞는 결과가 없습니다."
            : "등록된 이슈가 없습니다."}
        </p>
      )}
    </div>
  );
}
