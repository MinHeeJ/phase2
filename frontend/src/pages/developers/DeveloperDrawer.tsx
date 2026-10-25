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
  Tabs,
} from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { DeveloperForm } from "./DeveloperForm";
import { enableDeveloper, getDeveloper, type DeveloperDetail } from "./client";
import { useDeveloperReadOnly } from "./useDeveloperReadOnly";
import styles from "./Developers.module.css";

export function DeveloperStatusBadge({ status }: { status: string }) {
  const tone =
    status === "투입중"
      ? "info"
      : status === "철수예정"
        ? "warning"
        : status === "대기" || status === "퇴사"
          ? "danger"
          : "locked";
  return <Badge tone={tone}>{status}</Badge>;
}
export interface DeveloperDetailTab {
  key: string;
  label: string;
  render: (
    row: DeveloperDetail,
    refresh: () => Promise<unknown>,
    readOnly: boolean,
  ) => ReactNode;
}
export function DeveloperDrawer({
  id,
  onClose,
  onSaved,
  onDirtyChange,
  tabs = [],
  onDisable,
  refreshVersion = 0,
}: {
  id: number;
  onClose: () => void;
  onSaved: (message: string) => Promise<unknown>;
  onDirtyChange?: (dirty: boolean) => void;
  tabs?: DeveloperDetailTab[];
  onDisable?: (row: DeveloperDetail) => void;
  refreshVersion?: number;
}) {
  const read = useCallback(() => getDeveloper(id), [id, refreshVersion]),
    detail = useAdminRead(read);
  const [editing, setEditing] = useState(false),
    [dirty, setDirty] = useState(false),
    [confirm, setConfirm] = useState(false),
    [more, setMore] = useState(false),
    [tab, setTab] = useState("basic");
  const readOnly = useDeveloperReadOnly();
  const close = useCallback(() => {
    if (dirty) setConfirm(true);
    else onClose();
  }, [dirty, onClose]);
  const refresh = useCallback(async () => {
    await detail.reload();
    await onSaved("저장했습니다.");
  }, [detail.reload, onSaved]);
  const write = useAdminWrite(refresh);
  useEffect(() => {
    onDirtyChange?.(dirty);
    return () => onDirtyChange?.(false);
  }, [dirty, onDirtyChange]);
  useEffect(() => {
    if (readOnly) setEditing(false);
  }, [readOnly]);
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (
        e.key === "Escape" &&
        !document.querySelector('[aria-modal="true"] [aria-modal="true"]')
      )
        close();
    };
    window.addEventListener("keydown", key);
    return () => window.removeEventListener("keydown", key);
  }, [close]);
  const row = detail.data;
  const name = (group: string, code: string | null) =>
    code == null ? "–" : (row?.code_names?.[`${group}:${code}`] ?? code);
  const values: [string, ReactNode][] = row
    ? [
        ["이름", row.dev_nm],
        ["관리번호", row.mng_no],
        ["소속", row.affiliation_name],
        ["직원구분", <Badge>{row.emp_type_name}</Badge>],
        ["현재 상태", <DeveloperStatusBadge status={row.status} />],
        ["직위", name("POSITION", row.pstn_cd)],
        ["업무 구분", name("WORK_TYPE", row.duty_se_cd)],
        ["등급", row.grade_name ?? "–"],
        ["주 직무", row.main_job_name],
        ["보조 직무", row.sub_job.map((c) => name("JOB", c)).join(", ") || "–"],
        [
          "보유 스킬",
          row.skill.length
            ? row.skill.map((c) => <Badge key={c}>{name("SKILL", c)}</Badge>)
            : "–",
        ],
        ["영업 파트", row.sales_part_name ?? "–"],
        ["입사일", row.hire_dt ?? "–"],
        ["연락처", row.tel_no ?? "–"],
        ["이메일", row.email_addr ?? "–"],
        ["비고", row.rmrk_desc ?? "–"],
        ["월 단가", row.dflt_untprc_amt?.toLocaleString("ko-KR") ?? "–"],
      ]
    : [];
  return (
    <div
      className={styles.scrim}
      data-testid="developer-drawer-backdrop"
      onClick={(e) => {
        if (e.currentTarget === e.target) close();
      }}
    >
      <aside
        role="dialog"
        aria-modal="true"
        aria-label="개발자 상세"
        className={styles.drawer}
        data-testid="developer-drawer"
      >
        <div className={styles.drawerHeader}>
          <h2>{row?.dev_nm ?? "개발자 상세"}</h2>
          <Button
            aria-label="상세 닫기"
            data-testid="developer-drawer-close"
            onClick={close}
          >
            ×
          </Button>
        </div>
        {row && (
          <>
            <p>
              {editing ? "수정 중" : row.affiliation_name} · {row.mng_no}
            </p>
            {!editing && !readOnly && (
              <div className={styles.actions}>
                <Can perm="UI-02.update">
                  <Button
                    data-testid="developer-edit"
                    onClick={() => setEditing(true)}
                  >
                    수정
                  </Button>
                </Can>
                <Button
                  aria-label="더보기"
                  data-testid="developer-more"
                  onClick={() => setMore(!more)}
                >
                  ⋯
                </Button>
                {more && (
                  <div role="menu">
                    {tabs.some((t) => t.key === "history") && (
                      <Button
                        role="menuitem"
                        onClick={() => {
                          setTab("history");
                          setMore(false);
                        }}
                      >
                        변경 이력 보기
                      </Button>
                    )}
                    <Can perm="UI-02.delete">
                      {row.use_yn === "N" ? (
                        <Button
                          role="menuitem"
                          data-testid="developer-enable"
                          pending={write.pending}
                          onClick={() =>
                            void write.save(
                              () => enableDeveloper(id),
                              () => setMore(false),
                            )
                          }
                        >
                          다시 사용
                        </Button>
                      ) : (
                        onDisable && (
                          <Button
                            role="menuitem"
                            onClick={() => onDisable(row)}
                          >
                            사용 중지
                          </Button>
                        )
                      )}
                    </Can>
                  </div>
                )}
              </div>
            )}
            {row.status === "퇴사" && (
              <LockedBanner>
                퇴사한 사람입니다(퇴사일 {row.rsgn_dt}). 새 투입에 고를 수
                없습니다.
              </LockedBanner>
            )}
            {row.use_yn === "N" && (
              <LockedBanner>사용을 중지한 사람입니다.</LockedBanner>
            )}
          </>
        )}
        <ErrorBanner
          error={detail.error}
          onRetry={() => void detail.reload().catch(() => {})}
        />
        <ErrorBanner error={write.error} onRetry={() => void write.recover()} />
        {detail.loading && !row && <Skeleton />}
        {row &&
          (editing && !readOnly ? (
            <DeveloperForm
              key={row.dev_id}
              initial={row}
              reload={detail.reload}
              onDirtyChange={setDirty}
              onCancel={() => {
                setEditing(false);
                setDirty(false);
              }}
              onSaved={(_, changed) => {
                setEditing(false);
                setDirty(false);
                void onSaved(
                  changed ? "저장했습니다." : "바뀐 내용이 없습니다.",
                );
              }}
            />
          ) : (
            <>
              <Tabs
                items={[
                  { key: "basic", label: "기본 정보" },
                  ...tabs.map((t) => ({ key: t.key, label: t.label })),
                ]}
                selected={tab}
                onChange={setTab}
              />
              <div role="tabpanel" data-testid={`developer-${tab}-panel`}>
                {tab === "basic" ? (
                  <>
                    <dl className={styles.details}>
                      {values.map(([label, value]) => (
                        <Fragment key={label}>
                          <dt>{label}</dt>
                          <dd>{value}</dd>
                        </Fragment>
                      ))}
                    </dl>
                    <p data-sensitive="true">
                      최근 월 급여{" "}
                      {row.latest_salary
                        ? `${row.latest_salary.month} ${row.latest_salary.slry_amt.toLocaleString("ko-KR")} 원`
                        : "–"}
                    </p>
                  </>
                ) : (
                  tabs
                    .find((t) => t.key === tab)
                    ?.render(row, refresh, readOnly)
                )}
              </div>
            </>
          ))}
      </aside>
      {confirm && (
        <ConfirmDialog
          title="저장하지 않은 내용이 있습니다"
          cancelLabel="계속 작성"
          confirmLabel="닫기"
          onCancel={() => setConfirm(false)}
          onConfirm={() => {
            setDirty(false);
            onClose();
          }}
        >
          창을 닫으면 고친 내용이 사라집니다.
        </ConfirmDialog>
      )}
    </div>
  );
}
