import {
  Fragment,
  useCallback,
  useEffect,
  useState,
  type ReactNode,
} from "react";
import {
  Badge,
  Button,
  ConfirmDialog,
  LockedBanner,
  Skeleton,
} from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "../admin/useAdminResource";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import { useCodes } from "../shared/useCodes";
import { seoulMonth } from "../shared/PeriodBar";
import { ProjectForm } from "./ProjectForm";
import {
  getProject,
  listProjectAssignments,
  listProjectHistory,
  listProjectPartChanges,
  listProjectOptions,
  type ProjectDetail,
} from "./client";
import styles from "./Projects.module.css";

export interface ProjectDetailTab {
  key: string;
  label: string | ((row: ProjectDetail) => ReactNode);
  types?: string[];
  render: (
    row: ProjectDetail,
    refresh: () => Promise<unknown>,
    readOnly: boolean,
    controls: {
      onDirtyChange: (dirty: boolean) => void;
      onBasicInfo: () => void;
    },
  ) => ReactNode;
}
export function ProjectStatusBadge({
  code,
  label,
}: {
  code: string;
  label: string;
}) {
  return (
    <Badge
      tone={
        code === "RUN"
          ? "info"
          : code === "PLAN" || code === "HOLD"
            ? "warning"
            : code === "LOST"
              ? "danger"
              : "locked"
      }
    >
      {label}
    </Badge>
  );
}
export function ProjectDrawer({
  id,
  onClose,
  onSaved,
  onDirtyChange,
  tabs = [],
  onPartChange,
  actions,
  initialTab = "basic",
}: {
  id: number | string;
  onClose: () => void;
  onSaved: (message: string) => Promise<unknown>;
  onDirtyChange?: (dirty: boolean) => void;
  tabs?: ProjectDetailTab[];
  onPartChange?: (row: ProjectDetail, refresh: () => Promise<unknown>) => void;
  actions?: (row: ProjectDetail, refresh: () => Promise<unknown>) => ReactNode;
  initialTab?: string;
}) {
  const detail = useAdminRead(useCallback(() => getProject(id), [id]));
  const [editing, setEditing] = useState(false),
    [dirty, setDirty] = useState(false),
    [pendingLeave, setPendingLeave] = useState<(() => void) | null>(null),
    [tab, setTab] = useState(initialTab),
    [more, setMore] = useState(false);
  const readOnly = useDeveloperReadOnly();
  const row = detail.data;
  const assignments = useAdminRead(
    useCallback(
      () => (row ? listProjectAssignments(row.prj_id) : Promise.resolve([])),
      [row?.prj_id],
    ),
  );
  const parts = useAdminRead(
    useCallback(
      () => (row ? listProjectPartChanges(row.prj_id) : Promise.resolve([])),
      [row],
    ),
  );
  const history = useAdminRead(
    useCallback(
      () =>
        row && tab === "history"
          ? listProjectHistory(row.prj_id)
          : Promise.resolve([]),
      [row, tab],
    ),
  );
  const previous = useAdminRead(listProjectOptions);
  const types = useCodes("CONTRACT_TYPE"),
    statuses = useCodes("PROJECT_STATUS"),
    settlements = useCodes("SETTLE_METHOD"),
    industries = useCodes("INDUSTRY"),
    skills = useCodes("SKILL");
  const requestLeave = useCallback(
    (action: () => void) => {
      if (dirty) setPendingLeave(() => action);
      else action();
    },
    [dirty],
  );
  useEffect(() => {
    onDirtyChange?.(dirty);
    return () => onDirtyChange?.(false);
  }, [dirty, onDirtyChange]);
  useEffect(() => {
    if (readOnly) setEditing(false);
  }, [readOnly]);
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !pendingLeave) requestLeave(onClose);
    };
    window.addEventListener("keydown", key);
    return () => window.removeEventListener("keydown", key);
  }, [requestLeave, onClose, pendingLeave]);
  const refresh = useCallback(async () => {
    await detail.reload();
    await onSaved("저장했습니다.");
  }, [detail.reload, onSaved]);
  const label = (source: ReturnType<typeof useCodes>, code: string | null) =>
    source.codes.find((c) => c.code === code)?.name ?? code ?? "–";
  const external = row?.cntrct_ty_cd !== "IN";
  const money = (v: number | null) =>
    external ? (v?.toLocaleString("ko-KR") ?? "–") : "–";
  const extra = tabs.filter(
    (t) => !t.types || (row && t.types.includes(row.cntrct_ty_cd)),
  );
  const values: [string, ReactNode][] = row
    ? [
        ["계약유형", <Badge>{label(types, row.cntrct_ty_cd)}</Badge>],
        [
          "상태",
          <ProjectStatusBadge
            code={row.prj_stts_cd}
            label={label(statuses, row.prj_stts_cd)}
          />,
        ],
        ["계약 기간", `${row.cntrct_bgn_dt} ~ ${row.cntrct_end_dt}`],
        ["수행 시작일", row.prfm_bgn_dt ?? "–"],
        ["현재 종료일", row.cur_end_dt],
        ["최초 계약금액", money(row.init_cntrct_amt)],
        ["현재 계약금액", money(row.cur_cntrct_amt)],
        [
          "목표 마진율",
          external && row.trgt_mrgn_rt != null ? `${row.trgt_mrgn_rt}%` : "–",
        ],
        [
          "목표 원가",
          <span>
            {money(row.trgt_cst_amt)}{" "}
            <span title="현재 계약금액 × (100 − 목표 마진율) ÷ 100, 원 단위 버림">
              ⓘ 계산식
            </span>
          </span>,
        ],
        ["정산 방식", label(settlements, row.stlm_mthd_cd)],
        [
          "영업 파트",
          <>
            {external
              ? (row.partName ?? <Badge tone="warning">미지정</Badge>)
              : "없음"}
            {external && !readOnly && row.use_yn === "Y" && (
              <Can perm="UI-01.update">
                <Button
                  data-testid="project-part-change"
                  small
                  disabled={!onPartChange}
                  onClick={() => onPartChange?.(row, refresh)}
                >
                  변경
                </Button>
              </Can>
            )}
          </>,
        ],
        [
          "파트 변경 이력",
          parts.data
            ?.map(
              (p) =>
                `${p.aply_bgn_ym}~ ${p.partName ?? (p.bsn_part_id == null ? "없음" : p.bsn_part_id)}${p.aply_bgn_ym > seoulMonth() ? " (미래 적용)" : ""}`,
            )
            .join(" → ") || "–",
        ],
        ["PM", row.pmName ?? "–"],
        ["업종", label(industries, row.indty_cd)],
        ["업무 분야", row.duty_fld_nm ?? "–"],
        [
          "기술 스택",
          row.prj_skl.map((code) => (
            <Badge key={code}>{label(skills, code)}</Badge>
          )),
        ],
        [
          "선행 프로젝트",
          previous.data?.find((p) => p.id === row.prev_prj_id)?.name ??
            row.prev_prj_id ??
            "–",
        ],
        ["무상지원", row.free_sprt_yn === "Y" ? "예" : "아니요"],
        ["비고", row.rmrk_desc ?? "–"],
      ]
    : [];
  return (
    <div
      className={styles.scrim}
      data-testid="project-drawer-backdrop"
      onClick={(e) => {
        if (e.target === e.currentTarget) requestLeave(onClose);
      }}
    >
      <aside
        role="dialog"
        aria-modal="true"
        aria-label="프로젝트 상세"
        data-testid="project-drawer"
        className={styles.drawer}
      >
        <div className={styles.toolbar}>
          <h2>{row?.prj_nm ?? "프로젝트 상세"}</h2>
          <div className={styles.actions}>
            {!editing && row && row.use_yn === "Y" && !readOnly && (
              <Can perm="UI-01.update">
                <Button
                  data-testid="project-edit"
                  onClick={() =>
                    requestLeave(() => {
                      setDirty(false);
                      setEditing(true);
                    })
                  }
                >
                  수정
                </Button>
              </Can>
            )}
            {!editing && (
              <Button
                data-testid="project-more"
                aria-label="더보기"
                onClick={() => setMore((v) => !v)}
              >
                ⋯
              </Button>
            )}
            <Button
              data-testid="project-drawer-close"
              aria-label="상세 닫기"
              onClick={() => requestLeave(onClose)}
            >
              ×
            </Button>
          </div>
        </div>
        {row && (
          <p>
            {editing ? "수정 중 · " : ""}
            {row.prj_no} · {external ? (row.custName ?? "–") : "–"} ·{" "}
            {label(types, row.cntrct_ty_cd)}
          </p>
        )}
        {more && !editing && (
          <div className={styles.actions}>
            <Button
              data-testid="project-history-open"
              onClick={() =>
                requestLeave(() => {
                  setDirty(false);
                  setTab("history");
                  setMore(false);
                })
              }
            >
              변경 이력 보기
            </Button>
            {row && !dirty && actions?.(row, refresh)}
          </div>
        )}
        {detail.loading && <Skeleton />}
        <ErrorBanner
          error={detail.error}
          onRetry={() => void detail.reload().catch(() => {})}
        />
        {row?.prj_stts_cd === "PLAN" && row.assignmentCount > 0 && (
          <div className={styles.warning}>
            계약 전에 {row.assignmentCount}명이 먼저 투입되었습니다(미계약
            선투입).
          </div>
        )}
        {row?.use_yn === "N" && (
          <LockedBanner>사용 안 함으로 바꾼 프로젝트입니다.</LockedBanner>
        )}
        {row &&
          (editing ? (
            <ProjectForm
              initial={row}
              onDirtyChange={setDirty}
              onCancel={() =>
                requestLeave(() => {
                  setDirty(false);
                  setEditing(false);
                })
              }
              onSaved={async (_, changed) => {
                setDirty(false);
                onDirtyChange?.(false);
                setEditing(false);
                await detail.reload();
                await onSaved(
                  changed ? "저장했습니다." : "바뀐 내용이 없습니다.",
                );
              }}
            />
          ) : (
            <>
              <div role="tablist" className={styles.tabs}>
                {[
                  { key: "basic", label: "기본 정보" },
                  ...extra,
                  {
                    key: "assignments",
                    label: `투입 인력 (${assignments.data?.length ?? row.assignmentCount})`,
                  },
                  ...(tab === "history"
                    ? [{ key: "history", label: "변경 이력" }]
                    : []),
                ].map((t) => (
                  <Button
                    key={t.key}
                    role="tab"
                    data-testid={`project-tab-${t.key}`}
                    aria-selected={tab === t.key}
                    onClick={() => {
                      if (tab !== t.key)
                        requestLeave(() => {
                          setDirty(false);
                          setTab(t.key);
                        });
                    }}
                  >
                    {typeof t.label === "function" ? t.label(row) : t.label}
                  </Button>
                ))}
              </div>
              <section role="tabpanel" data-testid={`project-panel-${tab}`}>
                {tab === "basic" && (
                  <>
                    <ErrorBanner
                      error={parts.error}
                      onRetry={() => void parts.reload().catch(() => {})}
                    />
                    <dl className={styles.details}>
                      {values.map(([key, value]) => (
                        <Fragment key={key}>
                          <dt>{key}</dt>
                          <dd>{value}</dd>
                        </Fragment>
                      ))}
                    </dl>
                    {extra.some((t) => t.key === "progress") && (
                      <Button
                        data-testid="project-progress-open"
                        onClick={() => requestLeave(() => setTab("progress"))}
                      >
                        진행 현황 보기
                      </Button>
                    )}
                  </>
                )}
                {extra
                  .find((t) => t.key === tab)
                  ?.render(row, refresh, readOnly || row.use_yn === "N", {
                    onDirtyChange: setDirty,
                    onBasicInfo: () =>
                      requestLeave(() => {
                        setDirty(false);
                        setTab("basic");
                      }),
                  })}
                {tab === "assignments" && (
                  <>
                    <ErrorBanner
                      error={assignments.error}
                      onRetry={() => void assignments.reload().catch(() => {})}
                    />
                    {assignments.loading ? (
                      <Skeleton />
                    ) : (
                      !assignments.error &&
                      (assignments.data?.length ? (
                        <table className={styles.table}>
                          <thead>
                            <tr>
                              {[
                                "이름",
                                "직무",
                                "투입 ~ 철수",
                                "투입률",
                                "상태",
                              ].map((c) => (
                                <th
                                  data-testid={`project-assignment-column-${["이름", "직무", "투입 ~ 철수", "투입률", "상태"].indexOf(c)}`}
                                  key={c}
                                >
                                  {c}
                                </th>
                              ))}
                            </tr>
                          </thead>
                          <tbody>
                            {assignments.data.map((a, i) => (
                              <tr
                                data-testid={`project-assignment-${i}`}
                                key={i}
                              >
                                <td>{a.name}</td>
                                <td>{a.job}</td>
                                <td>
                                  {a.beginDate} ~ {a.endDate ?? "–"}
                                </td>
                                <td>{a.assignmentRate * 100}%</td>
                                <td>{a.status}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      ) : (
                        <p className={styles.empty}>투입된 인력이 없습니다.</p>
                      ))
                    )}
                  </>
                )}
                {tab === "history" && (
                  <>
                    <ErrorBanner
                      error={history.error}
                      onRetry={() => void history.reload().catch(() => {})}
                    />
                    {history.loading ? (
                      <Skeleton />
                    ) : (
                      !history.error && (
                        <table className={styles.table}>
                          <thead>
                            <tr>
                              {[
                                "일시",
                                "항목",
                                "바꾸기 전",
                                "바꾼 뒤",
                                "처리자",
                              ].map((c) => (
                                <th
                                  data-testid={`project-history-column-${["일시", "항목", "바꾸기 전", "바꾼 뒤", "처리자"].indexOf(c)}`}
                                  key={c}
                                >
                                  {c}
                                </th>
                              ))}
                            </tr>
                          </thead>
                          <tbody>
                            {history.data?.map((h, i) => (
                              <tr key={i} data-testid={`project-history-${i}`}>
                                <td>{h.changedAt}</td>
                                <td>{h.field}</td>
                                <td>{h.before ?? "–"}</td>
                                <td>{h.after ?? "–"}</td>
                                <td>{h.actor ?? "기본 사용자"}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      )
                    )}
                    {!history.loading &&
                      !history.error &&
                      history.data?.length === 0 && (
                        <p>변경 이력이 없습니다.</p>
                      )}
                  </>
                )}
              </section>
            </>
          ))}
      </aside>
      {pendingLeave && (
        <ConfirmDialog
          title="저장하지 않은 내용이 있습니다"
          cancelLabel="계속 작성"
          confirmLabel="나가기"
          onCancel={() => setPendingLeave(null)}
          onConfirm={() => {
            setDirty(false);
            onDirtyChange?.(false);
            pendingLeave();
            setPendingLeave(null);
          }}
        >
          나가면 입력한 내용이 사라집니다.
        </ConfirmDialog>
      )}
    </div>
  );
}
