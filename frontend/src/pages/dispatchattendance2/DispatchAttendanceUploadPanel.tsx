import { useEffect, useRef, useState } from "react";
import { Badge, Button } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { Can } from "../../shared/Can";
import { UploadResultPanel } from "../apiops/UploadResultPanel";
import {
  downloadUploadFile,
  uploadFailure,
  type PersonnelIssue,
  type PersonnelPage,
} from "../uploadsops/client";
import {
  applyDispatchAttendanceUpload,
  listAttendanceUploadIssues,
  listAttendanceUploadRows,
  revalidateDispatchAttendanceUpload,
  validateDispatchAttendanceUpload,
  type AttendanceUploadApplication,
  type AttendanceUploadPreview,
  type AttendanceUploadResult,
} from "./client";
import { ApiFailure } from "../../shared/api";
import table from "../developers/Developers.module.css";
import styles from "../uploadsops/Uploads.module.css";

export interface AttendanceUploadProps {
  projectId: number;
  month: string;
  settlement: string;
  editable?: boolean;
  onApplied: (result: AttendanceUploadApplication) => void | Promise<unknown>;
}
export function DispatchAttendanceUploadPanel(props: AttendanceUploadProps) {
  return (
    <AttendanceUploadContent
      key={`${props.projectId}-${props.month}`}
      {...props}
    />
  );
}
function AttendanceUploadContent({
  projectId,
  month,
  settlement,
  editable = true,
  onApplied,
}: AttendanceUploadProps) {
  const [file, setFile] = useState<File | null>(null);
  const [result, setResult] = useState<AttendanceUploadResult | null>(null);
  const [error, setError] = useState<ApiFailure | null>(null);
  const [pending, setPending] = useState(false);
  const [tab, setTab] = useState("NORMAL");
  const [page, setPage] = useState(0);
  const [rows, setRows] =
    useState<PersonnelPage<AttendanceUploadPreview> | null>(null);
  const [issues, setIssues] = useState<PersonnelPage<PersonnelIssue> | null>(
    null,
  );
  const [reload, setReload] = useState(0);
  const [success, setSuccess] = useState("");
  const [mobile, setMobile] = useState(
    () =>
      typeof window.matchMedia === "function" &&
      window.matchMedia("(max-width: 767px)").matches,
  );
  const busy = useRef(false);
  useEffect(() => {
    if (typeof window.matchMedia !== "function") return;
    const media = window.matchMedia("(max-width: 767px)");
    const changed = () => setMobile(media.matches);
    media.addEventListener("change", changed);
    return () => media.removeEventListener("change", changed);
  }, []);
  useEffect(() => {
    if (!result) return;
    let active = true;
    setRows(null);
    setIssues(null);
    Promise.all([
      listAttendanceUploadRows(result.upload_id, tab, page),
      tab === "NORMAL"
        ? Promise.resolve(null)
        : listAttendanceUploadIssues(result.upload_id, tab, page),
    ])
      .then(([nextRows, nextIssues]) => {
        if (active) {
          setRows(nextRows);
          setIssues(nextIssues);
        }
      })
      .catch((e) => {
        if (active) setError(uploadFailure(e));
      });
    return () => {
      active = false;
    };
  }, [result, tab, page, reload]);
  const currentYear = new Intl.DateTimeFormat("en", {
    timeZone: "Asia/Seoul",
    year: "numeric",
  }).format(new Date());
  const writable = editable && !mobile && month.slice(0, 4) === currentYear;
  const eligible =
    Number.isSafeInteger(projectId) &&
    projectId > 0 &&
    /^\d{4}-(0[1-9]|1[0-2])$/.test(month) &&
    ["DAY", "HOUR"].includes(settlement);
  const choose = (chosen?: File) => {
    if (pending) return;
    setResult(null);
    setFile(null);
    setError(null);
    setSuccess("");
    if (!chosen) return;
    if (!/\.xlsx$/i.test(chosen.name)) {
      setError(
        new ApiFailure("xlsx 파일만 올릴 수 있습니다.", 400, null, "file"),
      );
      return;
    }
    if (chosen.size > 10 * 1024 * 1024) {
      setError(
        new ApiFailure(
          "파일은 10MB 이하만 올릴 수 있습니다.",
          413,
          null,
          "file",
        ),
      );
      return;
    }
    setFile(chosen);
  };
  const run = async (
    kind: "validate" | "recheck" | "apply" | "template" | "errors",
  ) => {
    if (busy.current || !writable || !eligible) return;
    busy.current = true;
    setPending(true);
    setError(null);
    try {
      if (kind === "template")
        await downloadUploadFile(
          "/api/uploads/templates/B.3",
          "파견근태_업로드양식.xlsx",
        );
      else if (kind === "errors" && result)
        await downloadUploadFile(
          `/api/uploads/${result.upload_id}/errors`,
          "파견근태_오류.xlsx",
        );
      else if (
        kind === "apply" &&
        result?.can_apply &&
        result.error_count === 0
      ) {
        const applied = await applyDispatchAttendanceUpload(result.upload_id);
        setResult({ ...result, can_apply: false });
        setSuccess(
          applied.message ??
            "근태를 반영했습니다. 연결 DRAFT는 다시 계산해 주세요.",
        );
        await onApplied(applied);
      } else {
        const next =
          kind === "recheck" && result
            ? await revalidateDispatchAttendanceUpload(result.upload_id)
            : file
              ? await validateDispatchAttendanceUpload(file, month, projectId)
              : null;
        if (next) {
          setResult(next);
          setTab(
            next.error_count
              ? "ERROR"
              : next.warning_count
                ? "WARNING"
                : "NORMAL",
          );
          setPage(0);
          setSuccess("");
        }
      }
    } catch (e) {
      const failure = uploadFailure(e);
      setError(failure);
      if (failure.status === 409 || failure.status === 410)
        setResult((r) => (r ? { ...r, can_apply: false } : r));
    } finally {
      busy.current = false;
      setPending(false);
    }
  };
  return (
    <section
      data-testid="attendance-upload-panel"
      data-screen-id="SCR-UI-13-M2"
      aria-busy={pending}
    >
      <h3>B.3 파견 근태 업로드</h3>
      <div className={table.search}>
        <label>
          프로젝트ID
          <input
            data-testid="attendance-upload-project"
            readOnly
            value={projectId}
          />
        </label>
        <label>
          연월
          <input data-testid="attendance-upload-month" readOnly value={month} />
        </label>
      </div>
      <p className={styles.info}>
        선택 월·프로젝트의 미확정 근태 전체를 교체합니다. 오류가 하나라도 있으면
        파일 전체를 반영하지 않습니다. 원본 투입 MM·단가와 연결 DRAFT 상세는
        변경하지 않습니다.
      </p>
      {!eligible && (
        <p role="alert" className={styles.dangerBanner}>
          DP DAY/HOUR 프로젝트와 연월을 선택해 주세요.
        </p>
      )}
      {!writable && (
        <p className={styles.info}>
          과거 연도·모바일 또는 읽기 전용 상태에서는 근태 업로드를 할 수
          없습니다.
        </p>
      )}
      {writable && eligible && (
        <Can perm="UI-13.upload">
          <div
            className={styles.dropzone}
            data-testid="attendance-upload-dropzone"
            onDragOver={(e) => e.preventDefault()}
            onDrop={(e) => {
              e.preventDefault();
              choose(e.dataTransfer.files[0]);
            }}
          >
            <p>파일을 끌어다 놓으세요</p>
            <label className={styles.fileLabel}>
              파일 선택
              <input
                data-testid="attendance-upload-file"
                aria-label="파일 선택"
                type="file"
                accept=".xlsx"
                disabled={pending}
                onChange={(e) => choose(e.target.files?.[0])}
              />
            </label>
            <p>xlsx · 10MB 이하 · 데이터 10,000행 이하</p>
            {file && <p>{file.name}</p>}
          </div>
          <div className={table.actions}>
            <Button
              data-testid="attendance-upload-template"
              disabled={pending}
              onClick={() => void run("template")}
            >
              양식 내려받기
            </Button>
            <Button
              data-testid="attendance-upload-validate"
              variant="primary"
              pending={pending}
              disabled={!file}
              onClick={() => void run("validate")}
            >
              검사
            </Button>
          </div>
        </Can>
      )}
      <ErrorBanner
        error={error}
        pending={pending}
        onRetry={() => {
          setError(null);
          setReload((n) => n + 1);
        }}
      />
      {error?.field && (
        <p data-testid="attendance-upload-field-error">
          {error.field}: {error.message}
        </p>
      )}
      {pending && <p role="status">처리 중</p>}
      {success && (
        <p
          role="status"
          data-testid="attendance-upload-success"
          className={styles.success}
        >
          {success}
        </p>
      )}
      {result && (
        <UploadResultPanel
          key={`${result.upload_id}-${success}`}
          uploadId={result.upload_id}
        >
          {(review) => (
            <>
              <div role="tablist" className={table.actions}>
                {[
                  { key: "NORMAL", label: "정상" },
                  { key: "WARNING", label: "경고" },
                  { key: "ERROR", label: "오류" },
                ].map((item) => (
                  <Button
                    key={item.key}
                    data-testid={`attendance-upload-tab-${item.key.toLowerCase()}`}
                    role="tab"
                    aria-selected={tab === item.key}
                    onClick={() => {
                      setTab(item.key);
                      setPage(0);
                    }}
                  >
                    {item.label}
                  </Button>
                ))}
              </div>
              <div
                role="tabpanel"
                data-testid="attendance-upload-preview"
                className={table.tableScroll}
              >
                {!rows ? (
                  <p role="status">조회 중</p>
                ) : (
                  <table className={table.table}>
                    <thead>
                      <tr>
                        {[
                          "행",
                          "상태",
                          "관리번호",
                          "이름",
                          "근무일수",
                          "정규 근무시간",
                          "초과 근무시간",
                          "해당 월 근무일수",
                          "산출 MM",
                          "투입 MM",
                        ].map((text) => (
                          <th key={text}>{text}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {rows.items.map((row) => (
                        <tr
                          key={row.row}
                          data-testid={`attendance-upload-row-${row.row}`}
                        >
                          <td>{row.row}</td>
                          <td>
                            <Badge
                              tone={
                                row.severity === "ERROR"
                                  ? "danger"
                                  : row.severity === "WARNING"
                                    ? "warning"
                                    : "success"
                              }
                            >
                              {row.severity === "ERROR"
                                ? "오류"
                                : row.severity === "WARNING"
                                  ? "경고"
                                  : "정상"}
                            </Badge>
                          </td>
                          <td>{row.attendance.mng_no}</td>
                          <td>{row.attendance.dev_nm}</td>
                          {[
                            row.attendance.work_day_qty,
                            row.attendance.rglr_tm_qty,
                            row.attendance.ovtm_tm_qty,
                            row.attendance.mth_work_day_qty,
                            row.attendance.calculated_mm,
                            row.attendance.assignment_mm,
                          ].map((value, index) => (
                            <td className={table.amount} key={index}>
                              {value ?? "–"}
                            </td>
                          ))}
                        </tr>
                      ))}
                      {rows.items.length === 0 && (
                        <tr>
                          <td colSpan={10} className={styles.empty}>
                            해당 행이 없습니다.
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                )}
              </div>
              {issues && (
                <div
                  data-testid="attendance-upload-issues"
                  className={table.tableScroll}
                >
                  <table className={table.table}>
                    <thead>
                      <tr>
                        {["행", "열", "입력값", "이유"].map((text) => (
                          <th key={text}>{text}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {issues.items.map((issue, index) => (
                        <tr
                          key={`${issue.row}-${issue.column}-${index}`}
                          data-testid={`attendance-upload-issue-${index}`}
                        >
                          <td>{issue.row}</td>
                          <td>{issue.column}</td>
                          <td>{issue.raw_value ?? "–"}</td>
                          <td>{issue.message}</td>
                        </tr>
                      ))}
                      {issues.items.length === 0 && (
                        <tr>
                          <td colSpan={4} className={styles.empty}>
                            해당 오류·경고가 없습니다.
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              )}
              <div className={table.actions}>
                <Button
                  data-testid="attendance-upload-prev"
                  disabled={page === 0 || !rows}
                  onClick={() => setPage((n) => n - 1)}
                >
                  이전
                </Button>
                <span>
                  {rows?.total ?? 0}행 · {page + 1}쪽
                </span>
                <Button
                  data-testid="attendance-upload-next"
                  disabled={
                    !rows ||
                    (page + 1) * 50 >= Math.max(rows.total, issues?.total ?? 0)
                  }
                  onClick={() => setPage((n) => n + 1)}
                >
                  다음
                </Button>
              </div>
              {writable && eligible && (
                <div className={table.actions}>
                  <Button
                    data-testid="attendance-upload-recheck"
                    pending={pending}
                    disabled={!!success}
                    onClick={() => void run("recheck")}
                  >
                    다시 검사
                  </Button>
                  <Button
                    data-testid="attendance-upload-errors"
                    disabled={pending}
                    onClick={() => void run("errors")}
                  >
                    오류 파일
                  </Button>
                  <Button
                    data-testid="attendance-upload-apply"
                    variant="primary"
                    pending={pending}
                    disabled={
                      !result.can_apply ||
                      !review.can_apply ||
                      review.error_count > 0 ||
                      result.error_count > 0 ||
                      !rows ||
                      !!error ||
                      !!success
                    }
                    onClick={() => void run("apply")}
                  >
                    반영
                  </Button>
                </div>
              )}
            </>
          )}
        </UploadResultPanel>
      )}
    </section>
  );
}
