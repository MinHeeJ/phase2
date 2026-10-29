import { useEffect, useState } from "react";
import { Button, Modal } from "../../shared/Controls";
import { api, ApiFailure, readData } from "../../shared/api";
import { listProjectOptions, type ProjectOption } from "../projectsops/client";
import {
  downloadUploadFile,
  uploadTemplatePath,
  uploadErrorPath,
} from "../uploadsops/client";
import type { ApiError, ApiResponse } from "../../shared/types";
import table from "../developers/Developers.module.css";
import styles from "./BillingReview.module.css";

interface AttendanceRow {
  asgn_id: number;
  dev_id: number;
  work_day_qty: number | null;
  rglr_tm_qty: number | null;
  ovtm_tm_qty: number | null;
  mth_work_day_qty: number | null;
  calculated_mm: number | null;
  assignment_mm: number | null;
  price_available: boolean;
  warnings: string[];
}
interface Attendance {
  month: string;
  project_id: number;
  items: AttendanceRow[];
  warnings: string[];
}
interface Inspection {
  upload_id: number;
  row_count: number;
  error_count: number;
  warning_count: number;
  can_apply: boolean;
  overwrite_notice: string | null;
}
interface Issue {
  row: number;
  column: string;
  message: string;
  severity: string;
}
export function BillingAttendanceDialog({
  onClose,
  onChanged,
  initial,
}: {
  onClose: () => void;
  onChanged: () => void;
  initial?: { projectId: number; month: string };
}) {
  const [projects, setProjects] = useState<ProjectOption[]>([]),
    [project, setProject] = useState(initial ? String(initial.projectId) : "");
  const [month, setMonth] = useState(initial?.month ?? ""),
    [data, setData] = useState<Attendance | null>(null);
  const [file, setFile] = useState<File | null>(null),
    [inspection, setInspection] = useState<Inspection | null>(null),
    [issues, setIssues] = useState<Issue[]>([]);
  const [pending, setPending] = useState(false),
    [error, setError] = useState(""),
    [message, setMessage] = useState("");
  useEffect(() => {
    listProjectOptions()
      .then((p) => setProjects(p.filter((p) => p.type === "DP")))
      .catch((e) => setError(e.message));
  }, []);
  const query = () =>
    new URLSearchParams({ month, projectId: project }).toString();
  async function load() {
    setPending(true);
    setError("");
    try {
      setData(
        await readData<Attendance>(`/api/dispatch-attendance?${query()}`),
      );
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setPending(false);
    }
  }
  async function save() {
    if (!data) return;
    if (
      data.items.some(
        (r) => r.mth_work_day_qty === null || r.mth_work_day_qty <= 0,
      )
    ) {
      setError("해당 월 근무일수는 0보다 커야 합니다.");
      return;
    }
    setPending(true);
    setError("");
    try {
      await api(`/api/dispatch-attendance?${query()}`, {
        method: "PUT",
        body: JSON.stringify({
          items: data.items.map((r) => ({
            asgn_id: r.asgn_id,
            dev_id: r.dev_id,
            work_day_qty: r.work_day_qty,
            rglr_tm_qty: r.rglr_tm_qty,
            ovtm_tm_qty: r.ovtm_tm_qty ?? 0,
            mth_work_day_qty: r.mth_work_day_qty,
          })),
        }),
      });
      setMessage("근태를 저장했습니다. 청구 상세는 다시 계산할 때만 바뀝니다.");
      onChanged();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setPending(false);
    }
  }
  async function validate() {
    if (!file || !project || !month) {
      setError("월·프로젝트와 파일을 선택하세요.");
      return;
    }
    if (
      !file.name.toLowerCase().endsWith(".xlsx") ||
      file.size > 10 * 1024 * 1024
    ) {
      setError("10MB 이하의 xlsx 파일을 선택하세요.");
      return;
    }
    setPending(true);
    setError("");
    setInspection(null);
    setIssues([]);
    try {
      const body = new FormData();
      body.append("file", file);
      body.append("month", month);
      body.append("projectId", project);
      const response = await fetch(
        "/api/uploads/dispatch-attendance/validate",
        { method: "POST", body },
      );
      const result = (await response.json()) as
        | ApiResponse<Inspection>
        | ApiError;
      if (!response.ok || !result.success) {
        const e = result as ApiError;
        throw new ApiFailure(
          e.message,
          response.status,
          e.requestId,
          e.field,
          e.code,
        );
      }
      const next = (result as ApiResponse<Inspection>).data!;
      setInspection(next);
      const all: Issue[] = [];
      let page = 0,
        total = 0;
      do {
        const batch = await readData<{ items: Issue[]; total: number }>(
          `/api/uploads/${next.upload_id}/issues?page=${page}&size=100&sort=row,asc`,
        );
        all.push(...batch.items);
        total = batch.total;
        page++;
        if (batch.items.length === 0) break;
      } while (all.length < total);
      setIssues(all);
    } catch (e) {
      setError(
        e instanceof ApiFailure ? e.message : "검사 요청을 처리할 수 없습니다.",
      );
    } finally {
      setPending(false);
    }
  }
  async function apply() {
    if (!inspection?.can_apply) return;
    setPending(true);
    setError("");
    try {
      await api("/api/uploads/dispatch-attendance/apply", {
        method: "POST",
        body: JSON.stringify({
          upload_id: inspection.upload_id,
          confirm: true,
        }),
      });
      setInspection({ ...inspection, can_apply: false });
      setData(null);
      setMessage("선택 범위의 파견 근태를 반영했습니다.");
      onChanged();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setPending(false);
    }
  }
  const change = (id: number, key: keyof AttendanceRow, value: string) => {
    if (data)
      setData({
        ...data,
        items: data.items.map((r) =>
          r.asgn_id === id
            ? { ...r, [key]: value === "" ? null : Number(value) }
            : r,
        ),
      });
  };
  return (
    <Modal title="파견 근태 및 B.3" onCancel={onClose}>
      <section data-testid="billing-attendance-dialog">
        <div className={table.search}>
          <label>
            월
            <input
              data-testid="attendance-month"
              type="month"
              value={month}
              onChange={(e) => {
                setMonth(e.target.value);
                setData(null);
                setInspection(null);
              }}
            />
          </label>
          <label>
            프로젝트
            <select
              data-testid="attendance-project"
              value={project}
              onChange={(e) => {
                setProject(e.target.value);
                setData(null);
                setInspection(null);
              }}
            >
              <option value="">선택</option>
              {projects.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          </label>
          <Button
            data-testid="attendance-query"
            pending={pending}
            onClick={() => void load()}
          >
            조회
          </Button>
        </div>
        {data && (
          <>
            <div className={table.tableScroll}>
              <table className={table.table}>
                <thead>
                  <tr>
                    <th>인력</th>
                    <th>근무일수</th>
                    <th>정규 근무시간</th>
                    <th>초과 근무시간</th>
                    <th>해당 월 근무일수</th>
                    <th>산출 MM</th>
                    <th>⑦ 투입 MM</th>
                    <th>경고</th>
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((r) => (
                    <tr
                      key={r.asgn_id}
                      data-testid={`attendance-row-${r.asgn_id}`}
                    >
                      <td>#{r.dev_id}</td>
                      {(
                        [
                          "work_day_qty",
                          "rglr_tm_qty",
                          "ovtm_tm_qty",
                          "mth_work_day_qty",
                        ] as const
                      ).map((key) => (
                        <td key={key}>
                          <input
                            data-testid={`attendance-${r.asgn_id}-${key}`}
                            aria-label={`${r.dev_id} ${key}`}
                            type="number"
                            min={0}
                            step="0.1"
                            value={r[key] ?? ""}
                            onChange={(e) =>
                              change(r.asgn_id, key, e.target.value)
                            }
                          />
                        </td>
                      ))}
                      <td>{r.calculated_mm ?? "근무일수 미입력"}</td>
                      <td>{r.assignment_mm ?? "–"}</td>
                      <td>
                        {r.warnings.join(" · ")}
                        {!r.price_available &&
                          "단가 부재: 근태 저장 가능, 청구 생성 불가"}
                      </td>
                    </tr>
                  ))}
                  {data.items.length === 0 && (
                    <tr>
                      <td colSpan={8}>조회 결과가 없습니다.</td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
            {data.warnings.map((w, i) => (
              <p key={i} className={styles.message}>
                {w}
              </p>
            ))}
            <Button
              data-testid="attendance-save"
              pending={pending}
              onClick={() => void save()}
            >
              근태 저장
            </Button>
          </>
        )}
        <h3>B.3 파견 근태 업로드</h3>
        <p>
          검사는 저장하지 않습니다. 오류가 한 행이라도 있으면 전체 반영할 수
          없습니다.
        </p>
        <Button
          data-testid="attendance-template"
          onClick={() =>
            void downloadUploadFile(
              uploadTemplatePath("B.3"),
              "파견근태_양식.xlsx",
            ).catch((e) => setError(e.message))
          }
        >
          양식 내려받기
        </Button>
        <input
          data-testid="attendance-file"
          aria-label="B.3 파일"
          type="file"
          accept=".xlsx"
          onChange={(e) => {
            setFile(e.target.files?.[0] ?? null);
            setInspection(null);
          }}
        />
        <Button
          data-testid="attendance-validate"
          pending={pending}
          onClick={() => void validate()}
        >
          {inspection ? "다시 검사" : "검사"}
        </Button>
        {inspection && (
          <section data-testid="attendance-inspection">
            <p>
              전체 {inspection.row_count}행 · 경고 {inspection.warning_count}행
              · 오류 {inspection.error_count}행
            </p>
            <p>{inspection.overwrite_notice}</p>
            {issues.map((i, n) => (
              <p key={n}>
                {i.row}행 · {i.column} · {i.message}
              </p>
            ))}
            <Button
              data-testid="attendance-errors"
              onClick={() =>
                void downloadUploadFile(
                  uploadErrorPath(String(inspection.upload_id)),
                  "파견근태_오류.xlsx",
                ).catch((e) => setError(e.message))
              }
            >
              오류 파일
            </Button>
            <Button
              data-testid="attendance-apply"
              disabled={!inspection.can_apply || inspection.error_count > 0}
              pending={pending}
              onClick={() => void apply()}
            >
              반영
            </Button>
          </section>
        )}
        {error && <p role="alert">{error}</p>}
        {message && <p role="status">{message}</p>}
        <Button
          data-testid="attendance-close"
          disabled={pending}
          onClick={onClose}
        >
          닫기
        </Button>
      </section>
    </Modal>
  );
}
