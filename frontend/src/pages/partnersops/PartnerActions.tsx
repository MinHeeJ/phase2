import { useEffect, useState } from "react";
import {
  Button,
  Modal,
  Skeleton,
  Tabs,
  DataTable,
} from "../../shared/Controls";
import { ApiFailure } from "../../shared/api";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useCodes } from "../shared/useCodes";
import { createExcelMap } from "../admin/client";
import {
  listPartnerAssignments,
  listPartnerHistory,
  savePartnerDisable,
  downloadPartnerFile,
  type PartnerAssignment,
  type PartnerChange,
  type PartnerRow,
  type PartnerBlocked,
  type PartnerFilters,
} from "./client";
import styles from "./Partners.module.css";

export function PartnerMappingResolution({
  issue,
  onRecheck,
  upldId,
}: {
  issue: { rawValue: string; fix?: { type: string; group: string } };
  onRecheck: () => void;
  upldId?: string;
}) {
  const [open, setOpen] = useState(false),
    [saved, setSaved] = useState(false);
  if (issue.fix?.type !== "MAPPING") return null;
  return (
    <section data-testid="partner-mapping-resolution">
      <Button data-testid="partner-mapping-open" onClick={() => setOpen(true)}>
        대응 등록
      </Button>
      {saved && (
        <>
          <p role="status">대응을 저장했습니다. 다시 검사해 주세요.</p>
          <Button
            data-testid="partner-mapping-recheck"
            variant="primary"
            disabled={!upldId}
            onClick={onRecheck}
          >
            다시 검사
          </Button>
          {!upldId && <p>원본 파일을 다시 올려 검사해 주세요.</p>}
        </>
      )}
      {open && issue.fix && (
        <PartnerMappingDialog
          rawValue={issue.rawValue}
          group={issue.fix.group}
          onCancel={() => setOpen(false)}
          onSaved={() => {
            setOpen(false);
            setSaved(true);
          }}
        />
      )}
    </section>
  );
}

export function partnerFailure(error: unknown): ApiFailure {
  return error instanceof ApiFailure
    ? error
    : new ApiFailure("처리하지 못했습니다. 다시 시도해 주세요.", null, null);
}
export function PartnerActivityTabs({
  id,
  initial = "assignments",
  embedded = false,
}: {
  id: string;
  initial?: string;
  embedded?: boolean;
}) {
  const [tab, setTab] = useState(initial),
    [assignments, setAssignments] = useState<PartnerAssignment[] | null>(null),
    [history, setHistory] = useState<PartnerChange[] | null>(null),
    [error, setError] = useState<ApiFailure | null>(null),
    [version, setVersion] = useState(0);
  useEffect(() => {
    let current = true;
    setError(null);
    setAssignments(null);
    setHistory(null);
    (tab === "assignments"
      ? listPartnerAssignments(id).then((data) => {
          if (current) setAssignments(data);
        })
      : listPartnerHistory(id).then((data) => {
          if (current) setHistory(data);
        })
    ).catch((e) => {
      if (current) setError(partnerFailure(e));
    });
    return () => {
      current = false;
    };
  }, [id, tab, version]);
  return (
    <section data-testid="partner-activity-panel">
      {!embedded && (
        <Tabs
          selected={tab}
          onChange={setTab}
          items={[
            { key: "assignments", label: "투입 내역" },
            { key: "history", label: "변경 이력" },
          ]}
        />
      )}
      <ErrorBanner error={error} onRetry={() => setVersion((v) => v + 1)} />
      {!error && assignments === null && history === null && <Skeleton />}
      {assignments && (
        <div
          role={embedded ? undefined : "tabpanel"}
          data-testid={
            embedded
              ? "partner-activity-assignments"
              : "partner-assignments-panel"
          }
        >
          {assignments.length === 0 ? (
            <p>지금 투입 중인 내역이 없습니다.</p>
          ) : (
            <DataTable
              rows={assignments}
              rowKey={(r) => String(assignments.indexOf(r))}
              total={assignments.length}
              columns={[
                {
                  key: "projectName",
                  label: "프로젝트",
                  render: (r) => r.projectName,
                },
                {
                  key: "developerName",
                  label: "이름",
                  render: (r) => r.developerName,
                },
                {
                  key: "affiliation",
                  label: "소속",
                  render: (r) => r.affiliation,
                },
                { key: "jobName", label: "직무", render: (r) => r.jobName },
                {
                  key: "assignmentRate",
                  label: "투입률",
                  render: (r) =>
                    `${(r.assignmentRate * 100).toLocaleString("ko-KR")}%`,
                },
                { key: "endDate", label: "철수일", render: (r) => r.endDate },
              ]}
            />
          )}
        </div>
      )}
      {history && (
        <div
          role={embedded ? undefined : "tabpanel"}
          data-testid={
            embedded ? "partner-activity-history" : "partner-history-panel"
          }
        >
          {history.length === 0 ? (
            <p>아직 바꾼 기록이 없습니다.</p>
          ) : (
            <DataTable
              rows={history}
              rowKey={(r) => String(history.indexOf(r))}
              total={history.length}
              columns={[
                {
                  key: "changedAt",
                  label: "일시",
                  render: (r) => r.changedAt.replace("T", " ").slice(0, 16),
                },
                { key: "itemName", label: "항목", render: (r) => r.itemName },
                {
                  key: "before",
                  label: "바꾸기 전",
                  render: (r) => r.before ?? "–",
                },
                {
                  key: "after",
                  label: "바꾼 뒤",
                  render: (r) => r.after ?? "–",
                },
                {
                  key: "actorName",
                  label: "처리자",
                  render: (r) => r.actorName,
                },
              ]}
            />
          )}
        </div>
      )}
    </section>
  );
}

// SCR-UI-15-M4 / SCR-UI-15-M11: server counts are displayed only after confirmation.
export function PartnerDisableDialog({
  rows,
  onCancel,
  onChanged,
}: {
  rows: Pick<PartnerRow, "ptnr_id" | "ptnr_nm" | "use_yn">[];
  onCancel: () => void;
  onChanged: (message: string) => void;
}) {
  const [pending, setPending] = useState(false),
    [error, setError] = useState<ApiFailure | null>(null),
    [blocked, setBlocked] = useState<PartnerBlocked[] | null>(null),
    [message, setMessage] = useState("");
  const confirm = async () => {
    if (pending) return;
    setPending(true);
    setError(null);
    try {
      const result = await savePartnerDisable(rows.map((r) => r.ptnr_id));
      const text = result.disabledIds.length
        ? `${result.disabledIds.length}건 완료${result.blocked.length ? `, ${result.blocked.length}건 제외` : ""}`
        : result.blocked.length
          ? "사용을 중지할 수 없습니다"
          : "이미 중지된 거래처입니다.";
      if (result.disabledIds.length) onChanged(text);
      if (result.blocked.length || !result.disabledIds.length) {
        setBlocked(result.blocked);
        setMessage(text);
      } else onCancel();
    } catch (e) {
      const failure = partnerFailure(e);
      setError(failure);
      if (failure.code === "PARTNER_IN_USE") {
        setBlocked([]);
        setMessage("사용을 중지할 수 없습니다");
      }
    } finally {
      setPending(false);
    }
  };
  return (
    <Modal
      title={
        blocked === null ? `${rows.length}건의 사용을 중지할까요?` : message
      }
      onCancel={() => {
        if (!pending) onCancel();
      }}
      confirmation
    >
      <section
        data-testid="partner-disable-dialog"
        data-screen-id={blocked === null ? "SCR-UI-15-M4" : "SCR-UI-15-M11"}
      >
        {blocked === null ? (
          <>
            <ul>
              {rows.map((r) => (
                <li
                  data-testid={`partner-disable-item-${r.ptnr_id}`}
                  key={r.ptnr_id}
                >
                  {r.ptnr_nm}
                </li>
              ))}
            </ul>
            <p>
              중지하면 새 프로젝트·지급에서 고를 수 없습니다. 기록은 그대로
              남습니다.
            </p>
          </>
        ) : (
          <ul>
            {blocked.map((r) => (
              <li
                data-testid={`partner-disable-blocked-item-${r.id}`}
                key={r.id}
              >
                {r.name} — 진행 프로젝트 {r.projectCount}건 / 소속 인력{" "}
                {r.peopleCount}명 · {r.reason}
              </li>
            ))}
          </ul>
        )}
        <ErrorBanner error={error} onRetry={() => setError(null)} />
        <div className={styles.actions}>
          <Button
            data-testid="partner-disable-cancel"
            disabled={pending}
            onClick={onCancel}
          >
            {blocked === null ? "취소" : "확인"}
          </Button>
          {blocked === null && (
            <button
              data-testid="partner-disable-confirm"
              className={styles.danger}
              disabled={pending}
              onClick={() => void confirm()}
            >
              사용 중지
            </button>
          )}
        </div>
      </section>
    </Modal>
  );
}

// SCR-UI-15-M13 / SCR-UI-15-M14
export function PartnerExportButton({
  resource = "partners",
  filters = {},
  columns,
  ids,
  total,
}: {
  resource?: "partners" | "sales-parts";
  filters?: PartnerFilters;
  columns: readonly string[];
  ids: string[];
  total: number;
}) {
  const [open, setOpen] = useState(false),
    [range, setRange] = useState("selected"),
    [pending, setPending] = useState(false),
    [error, setError] = useState<ApiFailure | null>(null);
  const download = async (selected: boolean) => {
    if (pending) return;
    setPending(true);
    setError(null);
    try {
      await downloadPartnerFile(
        resource,
        filters,
        columns,
        selected ? ids : undefined,
      );
      setOpen(false);
    } catch (e) {
      setError(partnerFailure(e));
    } finally {
      setPending(false);
    }
  };
  return (
    <div
      className={styles.writeOnly}
      data-testid={`${resource}-export-control`}
    >
      <Button
        data-testid={`${resource}-export-open`}
        pending={pending}
        disabled={!total}
        onClick={() => {
          if (ids.length) {
            setRange("selected");
            setOpen(true);
          } else void download(false);
        }}
      >
        엑셀 다운로드
      </Button>
      {!open && (
        <ErrorBanner error={error} onRetry={() => void download(false)} />
      )}
      {open && (
        <Modal
          title="다운로드 범위"
          onCancel={() => {
            if (!pending) setOpen(false);
          }}
        >
          <section
            data-testid={`${resource}-export-dialog`}
            data-screen-id={
              resource === "partners" ? "SCR-UI-15-M13" : "SCR-UI-15-M14"
            }
          >
            <label>
              <input
                data-testid={`${resource}-export-selected`}
                type="radio"
                name="partner-export-range"
                checked={range === "selected"}
                onChange={() => setRange("selected")}
              />
              선택한 {ids.length}건만
            </label>
            <label>
              <input
                data-testid={`${resource}-export-all`}
                type="radio"
                name="partner-export-range"
                checked={range === "all"}
                onChange={() => setRange("all")}
              />
              조회된 전체 {total}건
            </label>
            <ErrorBanner
              error={error}
              onRetry={() => void download(range === "selected")}
            />
            <div className={styles.actions}>
              <Button
                data-testid={`${resource}-export-cancel`}
                disabled={pending}
                onClick={() => setOpen(false)}
              >
                취소
              </Button>
              <Button
                data-testid={`${resource}-export-download`}
                pending={pending}
                variant="primary"
                onClick={() => void download(range === "selected")}
              >
                내려받기
              </Button>
            </div>
          </section>
        </Modal>
      )}
    </div>
  );
}

// SCR-UI-15-M3
export function PartnerMappingDialog({
  rawValue,
  group,
  onCancel,
  onSaved,
}: {
  rawValue: string;
  group: string;
  onCancel: () => void;
  onSaved: () => void;
}) {
  const codes = useCodes(group),
    [code, setCode] = useState(""),
    [role, setRole] = useState(""),
    [pending, setPending] = useState(false),
    [error, setError] = useState<ApiFailure | null>(null);
  const save = async () => {
    if (!code || !role || pending) return;
    setPending(true);
    setError(null);
    try {
      await createExcelMap({
        excel_lbl_nm: rawValue,
        trns_cd_grp_id: group,
        trns_cd: code,
        map_role_cd: role as "MAIN" | "SUB",
        rmrk_desc: "",
      });
      onSaved();
    } catch (e) {
      setError(partnerFailure(e));
    } finally {
      setPending(false);
    }
  };
  return (
    <Modal
      title="엑셀 표기 대응 등록"
      onCancel={() => {
        if (!pending) onCancel();
      }}
    >
      <section
        data-testid="partner-mapping-dialog"
        data-screen-id="SCR-UI-15-M3"
      >
        <label>
          엑셀 표기
          <input
            data-testid="partner-mapping-label"
            value={rawValue}
            readOnly
          />
        </label>
        <label>
          그룹
          <input data-testid="partner-mapping-group" value={group} readOnly />
        </label>
        <label>
          코드
          <select
            data-testid="partner-mapping-code"
            value={code}
            disabled={pending || codes.loading}
            onChange={(e) => setCode(e.target.value)}
          >
            <option value="">선택</option>
            {codes.codes.map((c) => (
              <option
                data-testid={`partner-mapping-code-option-${c.code.toLowerCase().replace(/[^a-z0-9]+/g, "-")}`}
                key={c.code}
                value={c.code}
              >
                {c.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          주/보조
          <select
            data-testid="partner-mapping-role"
            value={role}
            disabled={pending}
            onChange={(e) => setRole(e.target.value)}
          >
            <option value="">선택</option>
            <option value="MAIN">주</option>
            <option value="SUB">보조</option>
          </select>
        </label>
        <ErrorBanner error={codes.error} onRetry={codes.retry} />
        <ErrorBanner error={error} onRetry={() => setError(null)} />
        <div className={styles.actions}>
          <Button
            data-testid="partner-mapping-cancel"
            disabled={pending}
            onClick={onCancel}
          >
            취소
          </Button>
          <Button
            data-testid="partner-mapping-save"
            pending={pending}
            disabled={!code || !role || !!codes.error}
            onClick={() => void save()}
            variant="primary"
          >
            저장
          </Button>
        </div>
      </section>
    </Modal>
  );
}
