import { useEffect, useState } from "react";
import { Button, Modal } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { api, readData, ApiFailure } from "../../shared/api";
import {
  listProjectOptions,
  getProject,
  type ProjectOption,
} from "../projects/client";
import { seoulMonth } from "../shared/PeriodBar";
import { billingFailure, downloadBilling, query } from "./client";
import styles from "../developers/Developers.module.css";

type Attendance = {
  asgn_id: number;
  dev_id: number;
  work_day_qty: number | null;
  rglr_tm_qty: number | null;
  ovtm_tm_qty: number | null;
  mth_work_day_qty: number | null;
  calculated_mm: number | null;
  assignment_mm: number | null;
  warnings: string[];
};
type Inspection = {
  upload_id: number;
  can_apply: boolean;
  row_count: number;
  error_count: number;
  warning_count: number;
};
export function UI13M2({
  onClose,
  onSaved,
}: {
  onClose: () => void;
  onSaved: () => Promise<unknown>;
}) {
  const [month, setMonth] = useState(seoulMonth()),
    [project, setProject] = useState("");
  const [projects, setProjects] = useState<ProjectOption[]>([]),
    [rows, setRows] = useState<Attendance[]>([]);
  const [error, setError] = useState<ApiFailure | null>(null),
    [pending, setPending] = useState(false);
  const [file, setFile] = useState<File | null>(null),
    [inspection, setInspection] = useState<Inspection | null>(null);
  const [issues, setIssues] = useState<
    { row: number; column: string; message: string }[]
  >([]);
  const [unit, setUnit] = useState("");
  const [dirty, setDirty] = useState(false);
  useEffect(() => {
    listProjectOptions()
      .then(setProjects)
      .catch((e) => setError(billingFailure(e)));
  }, []);
  const close = () => {
    if (!pending && (!dirty || window.confirm("입력을 버리고 닫을까요?")))
      onClose();
  };
  const execute = async (action: () => Promise<unknown>) => {
    setPending(true);
    setError(null);
    try {
      await action();
    } catch (e) {
      setError(billingFailure(e));
    } finally {
      setPending(false);
    }
  };
  const load = async () => {
    const p = await getProject(Number(project));
    if (
      p.cntrct_ty_cd !== "DP" ||
      !["DAY", "HOUR"].includes(p.stlm_mthd_cd ?? "")
    )
      throw new ApiFailure(
        "일·시간 파견 프로젝트를 선택하세요.",
        400,
        null,
        "projectId",
      );
    setUnit(p.stlm_mthd_cd!);
    const result = await readData<{ items: Attendance[] }>(
      `/api/dispatch-attendance?${query({ projectId: project, month })}`,
    );
    setRows(result.items);
    setDirty(false);
  };
  const inspect = async () => {
    if (!file || !project) return;
    if (
      !file.name.toLowerCase().endsWith(".xlsx") ||
      file.size > 10 * 1024 * 1024
    )
      throw new ApiFailure(
        "10MB 이하 XLSX 파일을 선택하세요.",
        400,
        null,
        "file",
      );
    const body = new FormData();
    body.append("file", file);
    body.append("month", month);
    body.append("projectId", project);
    const response = await fetch("/api/uploads/dispatch-attendance/validate", {
      method: "POST",
      body,
    });
    const payload = await response.json();
    if (!response.ok || !payload.success)
      throw new ApiFailure(
        payload.message ?? "검사 실패",
        response.status,
        payload.requestId,
        payload.field,
      );
    const result = payload.data as Inspection;
    setInspection(result);
    const found = await readData<{ items: typeof issues }>(
      `/api/uploads/${result.upload_id}/issues`,
    );
    setIssues(found.items);
  };
  return (
    <Modal title="파견 근태" onCancel={close}>
      <section data-testid="billing-attendance-dialog" className={styles.form}>
        <div className={styles.search}>
          <label>
            월
            <input
              data-testid="billing-attendance-month"
              type="month"
              value={month}
              onChange={(e) => {
                setMonth(e.target.value);
                setRows([]);
                setInspection(null);
              }}
            />
          </label>
          <label>
            프로젝트
            <select
              data-testid="billing-attendance-project"
              value={project}
              onChange={(e) => {
                setProject(e.target.value);
                setRows([]);
                setInspection(null);
              }}
            >
              <option value="">선택</option>
              {projects
                .filter((p) => p.type === "DP")
                .map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
            </select>
          </label>
          <Button
            data-testid="billing-attendance-load"
            disabled={!project}
            pending={pending}
            onClick={() => void execute(load)}
          >
            조회
          </Button>
        </div>
        <div className={styles.tableScroll}>
          <table className={styles.table}>
            <thead>
              <tr>
                <th>인력</th>
                <th>근무일수</th>
                <th>정규 근무시간</th>
                <th>초과 근무시간</th>
                <th>해당 월 근무일수</th>
                <th>산출 MM / 투입 MM</th>
                <th>경고</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r, i) => (
                <tr
                  key={r.asgn_id}
                  data-testid={`billing-attendance-row-${r.asgn_id}`}
                >
                  <td>{r.dev_id}</td>
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
                        data-testid={`billing-attendance-${key.replaceAll("_", "-")}-${r.asgn_id}`}
                        type="number"
                        min={key === "mth_work_day_qty" ? 0.1 : 0}
                        step="0.1"
                        aria-label={`${key} ${r.dev_id}`}
                        required={
                          key === "mth_work_day_qty" ||
                          (unit === "DAY" && key === "work_day_qty") ||
                          (unit === "HOUR" && key === "rglr_tm_qty")
                        }
                        value={r[key] ?? ""}
                        onChange={(e) => {
                          setRows(
                            rows.map((v, n) =>
                              n === i
                                ? {
                                    ...v,
                                    [key]:
                                      e.target.value === ""
                                        ? null
                                        : Number(e.target.value),
                                  }
                                : v,
                            ),
                          );
                          setDirty(true);
                        }}
                      />
                    </td>
                  ))}
                  <td>
                    {r.calculated_mm ?? "–"} / {r.assignment_mm ?? "–"}
                  </td>
                  <td>{r.warnings.join(" · ")}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <Button
          data-testid="billing-attendance-save"
          disabled={!rows.length}
          pending={pending}
          onClick={() =>
            void execute(async () => {
              const items = rows.map(
                ({
                  asgn_id,
                  dev_id,
                  work_day_qty,
                  rglr_tm_qty,
                  ovtm_tm_qty,
                  mth_work_day_qty,
                }) => ({
                  asgn_id,
                  dev_id,
                  ...(work_day_qty == null ? {} : { work_day_qty }),
                  ...(rglr_tm_qty == null ? {} : { rglr_tm_qty }),
                  ovtm_tm_qty: ovtm_tm_qty ?? 0,
                  mth_work_day_qty,
                }),
              );
              await api(
                `/api/dispatch-attendance?${query({ projectId: project, month })}`,
                { method: "PUT", body: JSON.stringify({ items }) },
              );
              await load();
              await onSaved();
            })
          }
        >
          저장
        </Button>
        <h3>B.3 파견 근태 업로드</h3>
        <p>
          연월 · 프로젝트ID · 관리번호 · 이름 · 근무일수 · 정규 근무시간 · 초과
          근무시간 · 해당 월 근무일수
        </p>
        <Button
          data-testid="billing-attendance-template"
          onClick={() =>
            void execute(() => downloadBilling("/api/uploads/templates/B.3"))
          }
        >
          양식 내려받기
        </Button>
        <input
          data-testid="billing-attendance-file"
          aria-label="B.3 파일"
          type="file"
          accept=".xlsx"
          onChange={(e) => {
            setFile(e.target.files?.[0] ?? null);
            setInspection(null);
          }}
        />
        <Button
          data-testid="billing-attendance-inspect"
          disabled={!file || !project}
          pending={pending}
          onClick={() => void execute(inspect)}
        >
          검사
        </Button>
        {inspection && (
          <section data-testid="billing-attendance-inspection">
            <p>
              정상·경고·오류: {inspection.row_count}행 / 경고{" "}
              {inspection.warning_count} / 오류 {inspection.error_count}
            </p>
            {issues.map((issue, i) => (
              <p key={i} data-testid={`billing-attendance-issue-${i}`}>
                {issue.row}행 {issue.column}: {issue.message}
              </p>
            ))}
            <Button
              data-testid="billing-attendance-recheck"
              onClick={() => void execute(inspect)}
            >
              다시 검사
            </Button>
            <Button
              data-testid="billing-attendance-errors"
              onClick={() =>
                void execute(() =>
                  downloadBilling(
                    `/api/uploads/${inspection.upload_id}/errors`,
                  ),
                )
              }
            >
              오류 파일
            </Button>
            <Button
              data-testid="billing-attendance-apply"
              disabled={!inspection.can_apply}
              pending={pending}
              onClick={() =>
                void execute(async () => {
                  await api("/api/uploads/dispatch-attendance/apply", {
                    method: "POST",
                    body: JSON.stringify({
                      upload_id: inspection.upload_id,
                      confirm: true,
                    }),
                  });
                  setInspection(null);
                  await load();
                  await onSaved();
                })
              }
            >
              반영
            </Button>
          </section>
        )}
        {error && <ErrorBanner error={error} />}
        <Button data-testid="billing-attendance-close" onClick={close}>
          닫기
        </Button>
      </section>
    </Modal>
  );
}
