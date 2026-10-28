import { useCallback, useEffect, useRef, useState } from "react";
import { Badge, Button, Modal, Skeleton } from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import { can } from "../../shared/types";
import { useAdminRead } from "../admin/useAdminResource";
import { getProject, listProjectOptions } from "../projects/client";
import { getMonthClose, monthStatus } from "../shared/api";
import { seoulMonth } from "../shared/PeriodBar";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import {
  downloadUploadFile,
  uploadFailure,
  type PersonnelPage,
  type PersonnelIssue,
} from "../uploadsops/client";
import {
  applyDispatchAttendanceUpload,
  validateDispatchAttendanceUpload,
  listAttendanceUploadRows,
  listAttendanceUploadIssues,
  type AttendanceUploadReview,
  type AttendanceUploadPreview,
  type AttendanceUploadApplied,
} from "./client";
import table from "../developers/Developers.module.css";
import uploads from "../uploadsops/Uploads.module.css";

export interface DispatchAttendanceUploadDialogProps {
  month?: string;
  projectId?: number;
  readOnly?: boolean;
  onClose: () => void;
  onApplied: (result: AttendanceUploadApplied) => void | Promise<unknown>;
}
export function DispatchAttendanceUploadDialog({
  month: initialMonth,
  projectId: initialProject,
  readOnly = false,
  onClose,
  onApplied,
}: DispatchAttendanceUploadDialogProps) {
  const [month, setMonth] = useState(initialMonth ?? seoulMonth());
  const [projectId, setProjectId] = useState<number | null>(
    initialProject ?? null,
  );
  const [file, setFile] = useState<File | null>(null);
  const [review, setReview] = useState<AttendanceUploadReview | null>(null);
  const [rows, setRows] =
    useState<PersonnelPage<AttendanceUploadPreview> | null>(null);
  const [issues, setIssues] = useState<PersonnelPage<PersonnelIssue> | null>(
    null,
  );
  const [page, setPage] = useState(0),
    [issuePage, setIssuePage] = useState(0);
  const [severity, setSeverity] = useState("");
  const [pending, setPending] = useState(false),
    [loadingRows, setLoadingRows] = useState(false);
  const [error, setError] = useState<ApiFailure | null>(null);
  const [rowsError, setRowsError] = useState<ApiFailure | null>(null);
  const [confirmed, setConfirmed] = useState(false);
  const [applied, setApplied] = useState<AttendanceUploadApplied | null>(null);
  const [reload, setReload] = useState(0);
  const busy = useRef(false);
  const mobile = useDeveloperReadOnly();
  const projects = useAdminRead(listProjectOptions);
  const selected = useAdminRead(
    useCallback(
      () => (projectId ? getProject(projectId) : Promise.resolve(null)),
      [projectId],
    ),
  );
  const closePeriod = useAdminRead(
    useCallback(
      () => getMonthClose(`${month.slice(0, 4)}-01`, `${month.slice(0, 4)}-12`),
      [month],
    ),
  );
  const closed = monthStatus(closePeriod.data ?? [], month) === "CLOSED";
  const past = month.slice(0, 4) < seoulMonth().slice(0, 4);
  const allowedProject =
    selected.data?.cntrct_ty_cd === "DP" &&
    ["DAY", "HOUR"].includes(selected.data.stlm_mthd_cd ?? "");
  const writable =
    !readOnly && !mobile && !past && !closed && can("UI-13.upload");
  const ready =
    writable &&
    allowedProject &&
    !selected.loading &&
    !closePeriod.loading &&
    !selected.error &&
    !closePeriod.error &&
    !projects.error;
  const invalidate = () => {
    setReview(null);
    setApplied(null);
    setRows(null);
    setIssues(null);
    setConfirmed(false);
    setError(null);
    setRowsError(null);
    setPage(0);
    setIssuePage(0);
  };
  useEffect(() => {
    if (!review) return;
    let active = true;
    setLoadingRows(true);
    setRowsError(null);
    Promise.all([
      listAttendanceUploadRows(review.upload_id, page, severity),
      listAttendanceUploadIssues(review.upload_id, issuePage),
    ])
      .then(([nextRows, nextIssues]) => {
        if (active) {
          setRows(nextRows);
          setIssues(nextIssues);
        }
      })
      .catch((e) => {
        if (active) setRowsError(uploadFailure(e));
      })
      .finally(() => {
        if (active) setLoadingRows(false);
      });
    return () => {
      active = false;
    };
  }, [review, page, issuePage, severity, reload]);
  const inspect = async () => {
    if (!file || !projectId || !ready || busy.current) return;
    busy.current = true;
    setPending(true);
    invalidate();
    try {
      setReview(await validateDispatchAttendanceUpload(file, month, projectId));
    } catch (e) {
      setError(uploadFailure(e));
    } finally {
      busy.current = false;
      setPending(false);
    }
  };
  const apply = async () => {
    if (!review?.can_apply || !confirmed || !ready || busy.current || applied)
      return;
    busy.current = true;
    setPending(true);
    setError(null);
    try {
      const result = await applyDispatchAttendanceUpload(review.upload_id);
      setApplied(result);
      setReview({ ...review, can_apply: false });
      try {
        await onApplied(result);
      } catch {
        setError(
          new ApiFailure(
            "반영은 완료했으나 화면 재조회에 실패했습니다. 다시 조회하세요.",
            null,
            null,
          ),
        );
      }
    } catch (e) {
      setError(uploadFailure(e));
      setReview({ ...review, can_apply: false });
      setConfirmed(false);
    } finally {
      busy.current = false;
      setPending(false);
    }
  };
  const choose = (next: File | null) => {
    invalidate();
    setFile(null);
    if (!next) return;
    if (
      !next.name.toLowerCase().endsWith(".xlsx") ||
      next.size > 10 * 1024 * 1024 ||
      next.size === 0
    ) {
      setError(
        new ApiFailure(
          "비어 있지 않은 10MB 이하 xlsx 파일을 선택하세요.",
          400,
          null,
          "file",
        ),
      );
      return;
    }
    setFile(next);
  };
  const tone = (value: string) =>
    value === "ERROR"
      ? ("danger" as const)
      : value === "WARNING"
        ? ("warning" as const)
        : ("success" as const);
  const label = (value: string) =>
    value === "ERROR" ? "오류" : value === "WARNING" ? "경고" : "정상";
  return (
    <Modal
      title="파견 근태 · B.3 엑셀 업로드"
      onCancel={() => {
        if (!pending) onClose();
      }}
    >
      <section
        data-testid="attendance-upload-dialog"
        data-screen-id="SCR-UI-13-M2"
        aria-busy={pending}
      >
        <div className={table.search}>
          <label>
            월
            <input
              data-testid="attendance-upload-month"
              type="month"
              value={month}
              disabled={pending}
              onChange={(e) => {
                setMonth(e.target.value);
                invalidate();
              }}
            />
          </label>
          <label>
            프로젝트
            <select
              data-testid="attendance-upload-project"
              aria-label="프로젝트"
              value={projectId ?? ""}
              disabled={pending || projects.loading}
              onChange={(e) => {
                setProjectId(e.target.value ? Number(e.target.value) : null);
                invalidate();
              }}
            >
              <option value="">선택해 주세요</option>
              {projects.data
                ?.filter((p) => p.type === "DP")
                .map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.no} · {p.name}
                  </option>
                ))}
            </select>
          </label>
        </div>
        {projects.loading && <Skeleton label="프로젝트 조회 중" />}
        <ErrorBanner
          error={projects.error ?? selected.error ?? closePeriod.error}
        />
        {(projects.error || selected.error || closePeriod.error) && (
          <Button
            data-testid="attendance-options-retry"
            onClick={() => {
              void projects.reload().catch(() => {});
              void selected.reload().catch(() => {});
              void closePeriod.reload().catch(() => {});
            }}
          >
            다시 조회
          </Button>
        )}
        {!projects.loading &&
          projects.data?.filter((p) => p.type === "DP").length === 0 && (
            <p className={table.empty}>파견 프로젝트가 없습니다.</p>
          )}
        {projectId &&
          !selected.loading &&
          !selected.error &&
          !allowedProject && (
            <p className={uploads.dangerBanner}>
              일·시간 단가 파견 프로젝트만 올릴 수 있습니다.
            </p>
          )}
        {!writable && (
          <p className={uploads.info}>
            {closed
              ? "마감된 달은 근태를 반영할 수 없습니다."
              : past
                ? "과거 연도는 조회·다운로드만 가능합니다."
                : "조회 전용입니다. 근태 업로드는 사용할 수 없습니다."}
          </p>
        )}
        <p className={uploads.info}>
          선택 월·프로젝트의 미확정 근태 전체를 교체합니다. 파일에서 빠진 인력은
          삭제됩니다. 연결 작성 청구와 원본 투입 MM·단가는 변경하지 않습니다.
        </p>
        <p className={table.help}>
          B.3 양식: 연월 · 프로젝트ID · 관리번호 · 이름 · 근무일수 · 정규
          근무시간 · 초과 근무시간 · 해당 월 근무일수. xlsx 10MB · 10,000행 이하
        </p>
        <div className={table.actions}>
          <Can perm="UI-13.download">
            <Button
              data-testid="A-13-B3-TEMPLATE"
              onClick={() => {
                void downloadUploadFile(
                  "/api/uploads/templates/B.3",
                  "파견근태_업로드양식.xlsx",
                ).catch((e) => setError(uploadFailure(e)));
              }}
            >
              양식 내려받기
            </Button>
          </Can>
          {writable && (
            <>
              <label className={uploads.fileLabel}>
                파일 선택
                <input
                  data-testid="A-13-B3-FILE"
                  type="file"
                  accept=".xlsx"
                  disabled={pending}
                  onChange={(e) => choose(e.target.files?.[0] ?? null)}
                />
              </label>
              <Button
                data-testid="A-13-B3-VALIDATE"
                pending={pending}
                disabled={!file || !ready}
                onClick={() => void inspect()}
              >
                {review ? "다시 검사" : "검사"}
              </Button>
            </>
          )}
        </div>
        <ErrorBanner error={error} />
        {error?.field && (
          <p
            className={uploads.dangerBanner}
            data-testid="attendance-upload-field-error"
          >
            {error.field}: {error.message}
          </p>
        )}
        {review && (
          <>
            <h3>엑셀 업로드 결과 · 파견 근태 {review.month}</h3>
            <div className={uploads.summary}>
              <div className={uploads.success}>
                정상{" "}
                <strong>
                  {review.row_count - review.error_count - review.warning_count}
                </strong>
              </div>
              <div className={uploads.warning}>
                경고 <strong>{review.warning_count}</strong>
              </div>
              <div className={uploads.danger}>
                오류 <strong>{review.error_count}</strong>
              </div>
            </div>
            <p
              className={
                review.error_count ? uploads.dangerBanner : uploads.info
              }
            >
              {review.error_count
                ? "오류가 하나라도 있으면 파일 전체를 반영하지 않습니다."
                : "검사가 완료되었습니다. 실제 행과 산출 MM을 확인한 뒤 반영하세요."}
            </p>
            {review.overwrite_notice && (
              <p className={uploads.warning}>{review.overwrite_notice}</p>
            )}
            <label>
              분류
              <select
                data-testid="attendance-upload-severity"
                aria-label="분류"
                value={severity}
                disabled={pending}
                onChange={(e) => {
                  setSeverity(e.target.value);
                  setPage(0);
                }}
              >
                <option value="">전체</option>
                <option value="NORMAL">정상</option>
                <option value="WARNING">경고</option>
                <option value="ERROR">오류</option>
              </select>
            </label>
            {loadingRows && <Skeleton label="검사 행 조회 중" />}
            <ErrorBanner error={rowsError} />
            {rowsError && (
              <Button
                data-testid="attendance-rows-retry"
                onClick={() => setReload((n) => n + 1)}
              >
                결과 다시 조회
              </Button>
            )}
            <div className={table.tableScroll}>
              <table className={table.table} aria-label="근태 검사 미리보기">
                <thead>
                  <tr>
                    {[
                      "행",
                      "구분",
                      "관리번호",
                      "이름",
                      "근무일수",
                      "정규 근무시간",
                      "초과 근무시간",
                      "해당 월 근무일수",
                      "산출 MM",
                      "⑦ 투입 MM",
                      "단가",
                    ].map((h) => (
                      <th key={h}>{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {!loadingRows && rows?.items.length === 0 && (
                    <tr>
                      <td colSpan={11} className={table.empty}>
                        조건에 맞는 결과가 없습니다.
                      </td>
                    </tr>
                  )}
                  {rows?.items.map((r) => (
                    <tr
                      key={r.row}
                      data-testid={`attendance-upload-row-${r.row}`}
                    >
                      <td>{r.row}</td>
                      <td>
                        <Badge tone={tone(r.severity)}>
                          {label(r.severity)}
                        </Badge>
                      </td>
                      <td>{r.management_number}</td>
                      <td>{r.name}</td>
                      <td>{r.attendance?.work_day_qty ?? "—"}</td>
                      <td>{r.attendance?.rglr_tm_qty ?? "—"}</td>
                      <td>{r.attendance?.ovtm_tm_qty ?? "—"}</td>
                      <td>{r.attendance?.mth_work_day_qty ?? "—"}</td>
                      <td>{r.calculated_mm ?? "—"}</td>
                      <td>{r.assignment_mm ?? "—"}</td>
                      <td>{r.price_available ? "있음" : "단가 없음"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className={table.actions}>
              <Button
                data-testid="attendance-rows-prev"
                disabled={page === 0 || loadingRows}
                onClick={() => setPage((p) => p - 1)}
              >
                이전 쪽
              </Button>
              <span>
                {page + 1}쪽 · 총 {rows?.total ?? 0}행
              </span>
              <Button
                data-testid="attendance-rows-next"
                disabled={!rows || (page + 1) * 50 >= rows.total || loadingRows}
                onClick={() => setPage((p) => p + 1)}
              >
                다음 쪽
              </Button>
            </div>
            <div className={table.tableScroll}>
              <table className={table.table} aria-label="행 오류와 경고">
                <thead>
                  <tr>
                    {["구분", "행", "열", "입력값", "이유"].map((h) => (
                      <th key={h}>{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {issues?.items.length === 0 && (
                    <tr>
                      <td colSpan={5} className={table.empty}>
                        오류·경고가 없습니다.
                      </td>
                    </tr>
                  )}
                  {issues?.items.map((i, n) => (
                    <tr
                      key={`${i.row}-${i.column}-${n}`}
                      data-testid={`attendance-upload-issue-${i.row}-${n}`}
                    >
                      <td>
                        <Badge tone={tone(i.severity)}>
                          {label(i.severity)}
                        </Badge>
                      </td>
                      <td>{i.row}</td>
                      <td>{i.column}</td>
                      <td>{i.raw_value}</td>
                      <td>{i.message}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className={table.actions}>
              <Button
                data-testid="attendance-issues-prev"
                disabled={issuePage === 0 || loadingRows}
                onClick={() => setIssuePage((p) => p - 1)}
              >
                이전 오류
              </Button>
              <Button
                data-testid="attendance-issues-next"
                disabled={
                  !issues || (issuePage + 1) * 50 >= issues.total || loadingRows
                }
                onClick={() => setIssuePage((p) => p + 1)}
              >
                다음 오류
              </Button>
              <Can perm="UI-13.download">
                <Button
                  data-testid="A-13-B3-ERROR"
                  onClick={() => {
                    void downloadUploadFile(
                      `/api/uploads/${review.upload_id}/error-file`,
                      "파견근태_오류.xlsx",
                    ).catch((e) => setError(uploadFailure(e)));
                  }}
                >
                  오류 파일
                </Button>
              </Can>
            </div>
            {writable && !applied && (
              <label>
                <input
                  data-testid="attendance-replace-confirm"
                  type="checkbox"
                  checked={confirmed}
                  disabled={!review.can_apply || pending}
                  onChange={(e) => setConfirmed(e.target.checked)}
                />
                선택 월·프로젝트 전체 교체를 확인했습니다.
              </label>
            )}
          </>
        )}
        {applied && (
          <p
            role="status"
            className={uploads.success}
            data-testid="attendance-upload-success"
          >
            {applied.message}
          </p>
        )}
        <div className={uploads.footer}>
          <span>검사만으로 근태·청구 상세는 바뀌지 않습니다.</span>
          <Button
            data-testid="A-13-M2-CLOSE"
            disabled={pending}
            onClick={onClose}
          >
            닫기
          </Button>
          {writable && (
            <Button
              data-testid="A-13-B3-APPLY"
              variant="primary"
              pending={pending}
              disabled={
                !ready ||
                !review?.can_apply ||
                !confirmed ||
                !!rowsError ||
                loadingRows ||
                !!applied
              }
              onClick={() => void apply()}
            >
              반영
            </Button>
          )}
        </div>
      </section>
    </Modal>
  );
}

export function DispatchAttendanceUploadActions(
  props: Omit<DispatchAttendanceUploadDialogProps, "onClose">,
) {
  const [open, setOpen] = useState(false);
  const mobile = useDeveloperReadOnly();
  const past = props.month
    ? props.month.slice(0, 4) < seoulMonth().slice(0, 4)
    : false;
  if (mobile || props.readOnly || past) return null;
  return (
    <Can perm="UI-13.upload">
      <div
        data-testid="attendance-upload-actions"
        className={table.writeActions}
      >
        <Button data-testid="A-13-UPLOAD" onClick={() => setOpen(true)}>
          엑셀 업로드
        </Button>
        {open && (
          <DispatchAttendanceUploadDialog
            {...props}
            onClose={() => setOpen(false)}
          />
        )}
      </div>
    </Can>
  );
}
