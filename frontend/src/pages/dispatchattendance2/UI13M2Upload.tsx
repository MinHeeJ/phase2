import { useEffect, useRef, useState } from "react";
import { Badge, Button } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import { Can } from "../../shared/Can";
import { UploadResultPanel } from "../apiops/UploadResultPanel";
import type { UploadResult } from "../apiops/client";
import {
  downloadUploadFile,
  uploadFailure,
  type PersonnelIssue,
  type PersonnelPage,
} from "../uploadsops/client";
import table from "../developers/Developers.module.css";
import styles from "../uploadsops/Uploads.module.css";
import {
  validateDispatchAttendanceUpload,
  applyDispatchAttendanceUpload,
  revalidateDispatchAttendanceUpload,
  listDispatchAttendanceUploadRows,
  listDispatchAttendanceUploadIssues,
  type AttendancePreview,
  type AttendanceApplied,
} from "./client";

export interface AttendanceUploadProps {
  month: string;
  projectId: number;
  currentYear: number;
  closed?: boolean;
  confirmed?: boolean;
  onApplied: (result: AttendanceApplied) => void | Promise<unknown>;
  onClose?: () => void;
}
export function UI13M2Upload(props: AttendanceUploadProps) {
  return (
    <AttendanceUpload key={`${props.month}:${props.projectId}`} {...props} />
  );
}
function AttendanceUpload({
  month,
  projectId,
  currentYear,
  closed = false,
  confirmed = false,
  onApplied,
  onClose,
}: AttendanceUploadProps) {
  const [file, setFile] = useState<File | null>(null);
  const [review, setReview] = useState<UploadResult | null>(null);
  const [error, setError] = useState<ApiFailure | null>(null);
  const [pending, setPending] = useState(false),
    [notice, setNotice] = useState("");
  const [tab, setTab] = useState("NORMAL"),
    [page, setPage] = useState(0);
  const [preview, setPreview] =
    useState<PersonnelPage<AttendancePreview> | null>(null);
  const [issues, setIssues] = useState<PersonnelPage<PersonnelIssue> | null>(
    null,
  );
  const [mobile, setMobile] = useState(
    () =>
      typeof window.matchMedia === "function" &&
      window.matchMedia("(max-width: 767px)").matches,
  );
  const busy = useRef(false);
  useEffect(() => {
    if (typeof window.matchMedia !== "function") return;
    const media = window.matchMedia("(max-width: 767px)"),
      changed = () => setMobile(media.matches);
    media.addEventListener("change", changed);
    return () => media.removeEventListener("change", changed);
  }, []);
  const readonly = mobile || Number(month.slice(0, 4)) < currentYear;
  const locked = closed || confirmed;
  useEffect(() => {
    if (!review) return;
    const controller = new AbortController();
    setPreview(null);
    setIssues(null);
    listDispatchAttendanceUploadRows(
      review.upload_id,
      tab,
      page,
      controller.signal,
    )
      .then(setPreview)
      .catch((e) => {
        if (!controller.signal.aborted) setError(uploadFailure(e));
      });
    if (tab !== "NORMAL")
      listDispatchAttendanceUploadIssues(
        review.upload_id,
        tab,
        page,
        controller.signal,
      )
        .then(setIssues)
        .catch((e) => {
          if (!controller.signal.aborted) setError(uploadFailure(e));
        });
    return () => controller.abort();
  }, [review, tab, page]);
  const run = async (action: () => Promise<void>) => {
    if (busy.current) return;
    busy.current = true;
    setPending(true);
    setError(null);
    setNotice("");
    try {
      await action();
    } catch (e) {
      const failure = uploadFailure(e);
      setError(failure);
      if (
        [
          "CLOSED_MONTH",
          "ATTENDANCE_BILLED",
          "VALIDATION_ERROR",
          "VALIDATION_EXPIRED",
          "STALE_VALIDATION",
        ].includes(failure.code ?? "")
      )
        setReview((r) => (r ? { ...r, can_apply: false } : r));
    } finally {
      busy.current = false;
      setPending(false);
    }
  };
  const choose = (chosen?: File) => {
    setReview(null);
    setFile(null);
    setError(null);
    setNotice("");
    if (!chosen) return;
    if (
      !/\.xlsx$/i.test(chosen.name) ||
      chosen.size === 0 ||
      chosen.size > 10 * 1024 * 1024
    ) {
      setError(
        new ApiFailure(
          "xlsx · 10MB 이하의 파일을 선택하세요.",
          400,
          null,
          "file",
        ),
      );
      return;
    }
    setFile(chosen);
  };
  const show = (result: UploadResult) => {
    setReview(result);
    setTab(
      result.error_count
        ? "ERROR"
        : result.warning_count
          ? "WARNING"
          : "NORMAL",
    );
    setPage(0);
  };
  const download = (path: string, name: string) =>
    void run(async () => {
      await downloadUploadFile(path, name);
    });
  return (
    <section data-testid="attendance-upload" data-screen-id="SCR-UI-13-M2">
      <h3>B.3 파견 근태 업로드</h3>
      <p>
        선택 월 {month} · 프로젝트ID {projectId}
      </p>
      <p className={styles.info}>
        연월 · 프로젝트ID · 관리번호 · 이름 · 근무일수 · 정규 근무시간 · 초과
        근무시간 · 해당 월 근무일수
      </p>
      <p className={styles.info}>
        검사 후 선택 월·프로젝트의 미확정 근태 전체를 교체합니다. 오류가
        하나라도 있으면 전체 미반영합니다. ⑦ 투입 MM·단가는 변경하지 않으며 연결
        DRAFT는 자동 재계산하지 않습니다.
      </p>
      {readonly && (
        <p className={styles.info}>
          과거 연도·모바일에서는 조회·다운로드만 가능합니다.
        </p>
      )}
      {locked && (
        <p role="alert" className={styles.dangerBanner}>
          {closed
            ? "마감된 달은 근태를 반영할 수 없습니다."
            : "확정 청구에 연결된 근태는 교체할 수 없습니다."}
        </p>
      )}
      <Can perm="UI-13.download">
        <Button
          data-testid="attendance-template"
          disabled={pending}
          onClick={() =>
            download("/api/uploads/templates/B.3", "파견근태_업로드양식.xlsx")
          }
        >
          양식 내려받기
        </Button>
      </Can>
      {!readonly && (
        <Can perm="UI-13.upload">
          <div className={styles.dropzone}>
            <label className={styles.fileLabel}>
              파일 선택
              <input
                data-testid="attendance-file"
                aria-label="파일 선택"
                type="file"
                accept=".xlsx"
                disabled={pending || locked}
                onChange={(e) => choose(e.target.files?.[0])}
              />
            </label>
            <p>xlsx · 10MB 이하 · 데이터 10,000행 이하</p>
            {file && <p>{file.name}</p>}
            <Button
              data-testid="attendance-validate"
              variant="primary"
              pending={pending}
              disabled={!file || locked || !month || !projectId}
              onClick={() =>
                void run(async () => {
                  if (file)
                    show(
                      await validateDispatchAttendanceUpload(
                        file,
                        month,
                        projectId,
                      ),
                    );
                })
              }
            >
              검사
            </Button>
          </div>
        </Can>
      )}
      <ErrorBanner error={error} />
      {error?.field && (
        <p data-testid="attendance-field-error">
          {error.field}: {error.message}
        </p>
      )}
      {pending && <p role="status">처리 중</p>}
      {notice && (
        <p
          role="status"
          data-testid="attendance-success"
          className={styles.success}
        >
          {notice}
        </p>
      )}
      {review && (
        <UploadResultPanel key={review.upload_id} uploadId={review.upload_id}>
          {(result) => (
            <>
              <div role="tablist" className={styles.tools}>
                {["NORMAL", "WARNING", "ERROR"].map((key, i) => (
                  <button
                    key={key}
                    data-testid={`attendance-tab-${key.toLowerCase()}`}
                    type="button"
                    role="tab"
                    aria-selected={tab === key}
                    onClick={() => {
                      setTab(key);
                      setPage(0);
                    }}
                  >
                    {["정상", "경고", "오류"][i]}
                  </button>
                ))}
              </div>
              <div
                role="tabpanel"
                data-testid="attendance-review"
                className={table.tableScroll}
              >
                {!preview ? (
                  <p role="status">검사 행 조회 중</p>
                ) : (
                  <table className={table.table}>
                    <thead>
                      <tr>
                        {[
                          "실제 행",
                          "인력 ID",
                          "근무일수",
                          "정규 근무시간",
                          "초과 근무시간",
                          "해당 월 근무일수",
                          "산출 MM",
                          "⑦ 투입 MM",
                          "단가·근태 경고",
                        ].map((h) => (
                          <th key={h}>{h}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {preview.items.map((row) => (
                        <tr
                          key={row.row}
                          data-testid={`attendance-row-${row.row}`}
                        >
                          <td>{row.row}</td>
                          <td>{row.attendance?.dev_id ?? "–"}</td>
                          <td>{row.attendance?.work_day_qty ?? "–"}</td>
                          <td>{row.attendance?.rglr_tm_qty ?? "–"}</td>
                          <td>{row.attendance?.ovtm_tm_qty ?? "–"}</td>
                          <td>{row.attendance?.mth_work_day_qty ?? "–"}</td>
                          <td>{row.attendance?.calculated_mm ?? "–"}</td>
                          <td>{row.attendance?.assignment_mm ?? "–"}</td>
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
                            </Badge>{" "}
                            {row.attendance?.warnings.join(" / ")}
                          </td>
                        </tr>
                      ))}
                      {preview.items.length === 0 && (
                        <tr>
                          <td colSpan={9} className={styles.empty}>
                            해당 행이 없습니다.
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                )}
              </div>
              {tab !== "NORMAL" && (
                <div className={table.tableScroll}>
                  {!issues ? (
                    <p role="status">이슈 조회 중</p>
                  ) : (
                    <table className={table.table}>
                      <thead>
                        <tr>
                          <th>행</th>
                          <th>열</th>
                          <th>이유</th>
                        </tr>
                      </thead>
                      <tbody>
                        {issues.items.map((issue, i) => (
                          <tr
                            key={`${issue.row}-${i}`}
                            data-testid={`attendance-issue-${i}`}
                          >
                            <td>{issue.row}</td>
                            <td>{issue.column}</td>
                            <td>{issue.message}</td>
                          </tr>
                        ))}
                        {issues.items.length === 0 && (
                          <tr>
                            <td colSpan={3} className={styles.empty}>
                              해당 이슈가 없습니다.
                            </td>
                          </tr>
                        )}
                      </tbody>
                    </table>
                  )}
                </div>
              )}
              <div className={styles.tools}>
                <Button
                  data-testid="attendance-prev"
                  disabled={page === 0 || pending}
                  onClick={() => setPage((p) => p - 1)}
                >
                  이전
                </Button>
                <span>
                  {page + 1}쪽 · {preview?.total ?? 0}행
                </span>
                <Button
                  data-testid="attendance-next"
                  disabled={
                    pending ||
                    Math.max(preview?.total ?? 0, issues?.total ?? 0) <=
                      (page + 1) * 50
                  }
                  onClick={() => setPage((p) => p + 1)}
                >
                  다음
                </Button>
              </div>
              <div className={styles.footer}>
                {!readonly && (
                  <Can perm="UI-13.upload">
                    <Button
                      data-testid="attendance-revalidate"
                      disabled={pending || locked || !!notice}
                      onClick={() =>
                        void run(async () =>
                          show(
                            await revalidateDispatchAttendanceUpload(
                              review.upload_id,
                            ),
                          ),
                        )
                      }
                    >
                      다시 검사
                    </Button>
                  </Can>
                )}
                <Can perm="UI-13.download">
                  <Button
                    data-testid="attendance-errors"
                    disabled={pending}
                    onClick={() =>
                      download(
                        `/api/uploads/${review.upload_id}/errors`,
                        "파견근태_오류.xlsx",
                      )
                    }
                  >
                    오류 파일
                  </Button>
                </Can>
                <span />
                {!readonly && (
                  <Can perm="UI-13.upload">
                    <Button
                      data-testid="attendance-apply"
                      variant="primary"
                      pending={pending}
                      disabled={
                        locked ||
                        !result.can_apply ||
                        !review.can_apply ||
                        result.error_count > 0 ||
                        !!notice
                      }
                      onClick={() =>
                        void run(async () => {
                          const applied = await applyDispatchAttendanceUpload(
                            review.upload_id,
                          );
                          setReview((r) =>
                            r ? { ...r, can_apply: false } : r,
                          );
                          setNotice(
                            applied.message ??
                              `${applied.applied_count}건을 반영했습니다.`,
                          );
                          await onApplied(applied);
                        })
                      }
                    >
                      반영
                    </Button>
                  </Can>
                )}
              </div>
            </>
          )}
        </UploadResultPanel>
      )}
      {onClose && (
        <Button
          data-testid="attendance-close"
          disabled={pending}
          onClick={onClose}
        >
          닫기
        </Button>
      )}
    </section>
  );
}
