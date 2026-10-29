import { useEffect, useState } from "react";
import { Badge, Button, Modal } from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ApiFailure } from "../../shared/api";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import { listProjectOptions, type ProjectOption } from "../projectsops/client";
import {
  downloadUploadFile,
  uploadFailure,
  type PersonnelIssue,
} from "../uploadsops/client";
import table from "../developers/Developers.module.css";
import styles from "../uploadsops/Uploads.module.css";
import {
  validateDispatchAttendanceUpload,
  applyDispatchAttendanceUpload,
  revalidateDispatchAttendanceUpload,
  listDispatchAttendanceIssues,
  listDispatchAttendanceRows,
  type DispatchAttendanceValidation,
  type DispatchAttendancePreview,
  type DispatchAttendanceApplied,
} from "./client";

export interface DispatchAttendanceUploadProps {
  year: number;
  currentYear?: number;
  initialMonth?: string;
  projectId?: number;
  onApplied: (result: DispatchAttendanceApplied) => void | Promise<unknown>;
}

export function DispatchAttendanceUploadActions(
  props: DispatchAttendanceUploadProps,
) {
  const [open, setOpen] = useState(false);
  const mobile = useDeveloperReadOnly();
  const currentYear =
    props.currentYear ??
    Number(
      new Intl.DateTimeFormat("en", {
        year: "numeric",
        timeZone: "Asia/Seoul",
      }).format(new Date()),
    );
  const readOnly = mobile || props.year < currentYear;
  useEffect(() => {
    if (readOnly) setOpen(false);
  }, [readOnly]);
  if (readOnly) return null;
  return (
    <section data-testid="attendance-upload-actions">
      <Can perm="UI-13.upload">
        <Button
          data-testid="attendance-upload-open"
          onClick={() => setOpen(true)}
        >
          엑셀 업로드
        </Button>
      </Can>
      {open && (
        <DispatchAttendanceUploadDialog
          {...props}
          onClose={() => setOpen(false)}
        />
      )}
    </section>
  );
}

export function DispatchAttendanceUploadDialog({
  year,
  initialMonth = "",
  projectId,
  onApplied,
  onClose,
}: DispatchAttendanceUploadProps & { onClose: () => void }) {
  const [month, setMonth] = useState(initialMonth);
  const [project, setProject] = useState(projectId ? String(projectId) : "");
  const [projects, setProjects] = useState<ProjectOption[]>([]);
  const [file, setFile] = useState<File | null>(null);
  const [result, setResult] = useState<DispatchAttendanceValidation | null>(
    null,
  );
  const [rows, setRows] = useState<DispatchAttendancePreview[]>([]);
  const [issues, setIssues] = useState<PersonnelIssue[]>([]);
  const [page, setPage] = useState(0),
    [issueTotal, setIssueTotal] = useState(0);
  const [rowTotal, setRowTotal] = useState(0),
    [ready, setReady] = useState(false);
  const [pending, setPending] = useState(false),
    [confirm, setConfirm] = useState(false);
  const [error, setError] = useState<ApiFailure | null>(null),
    [message, setMessage] = useState("");
  useEffect(() => {
    let active = true;
    listProjectOptions().then(
      (v) => {
        if (active) setProjects(v.filter((p) => p.type === "DP"));
      },
      (e) => {
        if (active) setError(uploadFailure(e));
      },
    );
    return () => {
      active = false;
    };
  }, []);
  const clear = () => {
    setResult(null);
    setRows([]);
    setIssues([]);
    setReady(false);
    setConfirm(false);
    setMessage("");
    setError(null);
  };
  const load = async (next: DispatchAttendanceValidation, nextPage = 0) => {
    setResult(next);
    setReady(false);
    setPage(nextPage);
    const [normal, errors] = await Promise.all([
      listDispatchAttendanceRows(next.upload_id, nextPage),
      listDispatchAttendanceIssues(next.upload_id, nextPage),
    ]);
    setRows(normal.items);
    setIssues(errors.items);
    setRowTotal(normal.total);
    setIssueTotal(errors.total);
    setReady(true);
  };
  const perform = async (action: () => Promise<void>) => {
    setPending(true);
    setError(null);
    try {
      await action();
    } catch (e) {
      setError(uploadFailure(e));
    } finally {
      setPending(false);
    }
  };
  const valid =
    file &&
    month.startsWith(`${year}-`) &&
    project &&
    file.size <= 10 * 1024 * 1024 &&
    /\.xlsx$/i.test(file.name);
  return (
    <Modal
      title="파견 근태 · B.3 엑셀 업로드"
      onCancel={() => {
        if (!pending) onClose();
      }}
    >
      <section data-testid="attendance-upload-dialog" aria-busy={pending}>
        <p className={styles.info}>
          선택 월·프로젝트의 근태 전체를 교체합니다. 오류가 하나라도 있으면
          반영하지 않습니다. 작성 청구는 자동 재계산하지 않습니다.
        </p>
        <div className={table.search}>
          <label>
            연월
            <input
              data-testid="attendance-upload-month"
              type="month"
              min={`${year}-01`}
              max={`${year}-12`}
              value={month}
              disabled={pending}
              onChange={(e) => {
                setMonth(e.target.value);
                clear();
              }}
            />
          </label>
          <label>
            프로젝트
            <select
              data-testid="attendance-upload-project"
              value={project}
              disabled={pending}
              onChange={(e) => {
                setProject(e.target.value);
                clear();
              }}
            >
              <option value="">선택</option>
              {projects.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.no} · {p.name}
                </option>
              ))}
            </select>
          </label>
        </div>
        <p>
          B.3 양식: 연월 · 프로젝트ID · 관리번호 · 이름 · 근무일수 · 정규
          근무시간 · 초과 근무시간 · 해당 월 근무일수
        </p>
        <p>
          DAY는 근무일수, HOUR는 정규 근무시간 필수입니다. 해당 월 근무일수는
          직접 입력합니다. xlsx 10MB · 10,000행 이하.
        </p>
        <div className={styles.dropzone}>
          <label className={styles.fileLabel}>
            파일 선택
            <input
              data-testid="attendance-upload-file"
              type="file"
              accept=".xlsx"
              disabled={pending}
              onChange={(e) => {
                setFile(e.target.files?.[0] ?? null);
                clear();
              }}
            />
          </label>
          {file && <p>{file.name}</p>}
          {file &&
            (file.size > 10 * 1024 * 1024 || !/\.xlsx$/i.test(file.name)) && (
              <p role="alert">10MB 이하의 xlsx 파일을 선택하세요.</p>
            )}
        </div>
        <div className={table.actions}>
          <Button
            data-testid="attendance-upload-template"
            disabled={pending}
            onClick={() =>
              void perform(async () => {
                await downloadUploadFile(
                  "/api/uploads/templates/B.3",
                  "파견근태_업로드양식.xlsx",
                );
              })
            }
          >
            양식 내려받기
          </Button>
          <Button
            data-testid="attendance-upload-validate"
            variant="primary"
            pending={pending}
            disabled={!valid}
            onClick={() =>
              void perform(async () => {
                if (!file) return;
                setConfirm(false);
                await load(
                  await validateDispatchAttendanceUpload(
                    file,
                    month,
                    Number(project),
                  ),
                );
              })
            }
          >
            검사
          </Button>
        </div>
        <ErrorBanner error={error} onRetry={() => setError(null)} />
        {pending && <p role="status">처리 중입니다.</p>}
        {result && (
          <section data-testid="attendance-upload-result">
            <h3>엑셀 업로드 결과 · 파견 근태</h3>
            <div className={styles.summary}>
              <div className={styles.success}>
                정상{" "}
                {result.row_count - result.error_count - result.warning_count}
              </div>
              <div className={styles.warning}>경고 {result.warning_count}</div>
              <div className={styles.danger}>오류 {result.error_count}</div>
            </div>
            <p
              className={result.error_count ? styles.dangerBanner : styles.info}
            >
              {result.overwrite_notice}
            </p>
            <div className={table.tableScroll}>
              <table className={table.table}>
                <thead>
                  <tr>
                    <th>구분</th>
                    <th>행</th>
                    <th>열</th>
                    <th>입력값</th>
                    <th>이유</th>
                  </tr>
                </thead>
                <tbody>
                  {issues.map((i, n) => (
                    <tr
                      key={`${i.row}-${i.column}-${n}`}
                      data-testid={`attendance-issue-${n}`}
                    >
                      <td>
                        <Badge
                          tone={i.severity === "ERROR" ? "danger" : "warning"}
                        >
                          {i.severity === "ERROR" ? "오류" : "경고"}
                        </Badge>
                      </td>
                      <td>{i.row}</td>
                      <td>{i.column}</td>
                      <td>{i.raw_value}</td>
                      <td>{i.message}</td>
                    </tr>
                  ))}
                  {ready && issues.length === 0 && (
                    <tr>
                      <td colSpan={5} className={styles.empty}>
                        오류·경고가 없습니다.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
            <h3>근태 · 월별 MM 미리보기</h3>
            <div className={table.tableScroll}>
              <table className={table.table}>
                <thead>
                  <tr>
                    <th>행</th>
                    <th>인력 ID</th>
                    <th>근무일수</th>
                    <th>정규 근무시간</th>
                    <th>초과 근무시간</th>
                    <th>해당 월 근무일수</th>
                    <th>산출 MM</th>
                    <th>⑦ 투입 MM</th>
                    <th>단가·근태 경고</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.row} data-testid={`attendance-preview-${r.row}`}>
                      <td>{r.row}</td>
                      <td>{r.attendance?.dev_id ?? "—"}</td>
                      <td>{r.attendance?.work_day_qty ?? "—"}</td>
                      <td>{r.attendance?.rglr_tm_qty ?? "—"}</td>
                      <td>{r.attendance?.ovtm_tm_qty ?? "—"}</td>
                      <td>{r.attendance?.mth_work_day_qty ?? "—"}</td>
                      <td>{r.calculated_mm ?? "산출 불가"}</td>
                      <td>{r.assignment_mm ?? "—"}</td>
                      <td>{r.warnings.join(" / ")}</td>
                    </tr>
                  ))}
                  {ready && rows.length === 0 && (
                    <tr>
                      <td colSpan={9} className={styles.empty}>
                        검사한 데이터가 없습니다.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
            <div className={table.actions}>
              <Button
                data-testid="attendance-review-prev"
                disabled={pending || page === 0}
                onClick={() => void perform(() => load(result, page - 1))}
              >
                이전 쪽
              </Button>
              <span>{page + 1}쪽</span>
              <Button
                data-testid="attendance-review-next"
                disabled={
                  pending || (page + 1) * 50 >= Math.max(rowTotal, issueTotal)
                }
                onClick={() => void perform(() => load(result, page + 1))}
              >
                다음 쪽
              </Button>
              <Button
                data-testid="attendance-upload-revalidate"
                disabled={pending || !!message}
                onClick={() =>
                  void perform(async () => {
                    setConfirm(false);
                    await load(
                      await revalidateDispatchAttendanceUpload(
                        result.upload_id,
                      ),
                    );
                  })
                }
              >
                다시 검사
              </Button>
              <Button
                data-testid="attendance-upload-errors"
                disabled={pending}
                onClick={() =>
                  void perform(async () => {
                    await downloadUploadFile(
                      `/api/uploads/${result.upload_id}/errors`,
                      "파견근태_오류.xlsx",
                    );
                  })
                }
              >
                오류 파일
              </Button>
              <Button
                data-testid="attendance-upload-apply"
                variant="primary"
                disabled={pending || !ready || !result.can_apply || !!message}
                onClick={() => setConfirm(true)}
              >
                반영
              </Button>
            </div>
            {confirm && (
              <section data-testid="attendance-upload-confirm">
                <p>선택 월·프로젝트의 기존 근태 전체를 교체하시겠습니까?</p>
                <Button
                  data-testid="attendance-upload-cancel-confirm"
                  disabled={pending}
                  onClick={() => setConfirm(false)}
                >
                  취소
                </Button>
                <Button
                  data-testid="attendance-upload-confirm-button"
                  pending={pending}
                  variant="primary"
                  onClick={() =>
                    void perform(async () => {
                      const saved = await applyDispatchAttendanceUpload(
                        result.upload_id,
                      );
                      setConfirm(false);
                      setResult({ ...result, can_apply: false });
                      setMessage(saved.message);
                      await onApplied(saved);
                    })
                  }
                >
                  교체 확인
                </Button>
              </section>
            )}
          </section>
        )}
        {message && (
          <p role="status" className={styles.success}>
            {message}
          </p>
        )}
        <footer className={styles.footer}>
          <span>검사는 업무 데이터 반영이 아닙니다.</span>
          <Button
            data-testid="attendance-upload-close"
            disabled={pending}
            onClick={onClose}
          >
            닫기
          </Button>
        </footer>
      </section>
    </Modal>
  );
}
