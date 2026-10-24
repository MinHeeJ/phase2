import { useCallback, useEffect, useState } from "react";
import {
  Button,
  Badge,
  Tabs,
  ConfirmDialog,
  Skeleton,
  LockedBanner,
  DataTable,
} from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { Can } from "../../shared/Can";
import { useAdminRead } from "../admin/useAdminResource";
import { useCodes } from "../shared/useCodes";
import {
  getPartner,
  listPartnerAssignments,
  listPartnerHistory,
  type PartnerDetail,
} from "./client";
import { PartnerForm } from "./PartnerForm";
import styles from "./Partners.module.css";

export function PartnerDrawer({
  id,
  initialTab = "basic",
  onClose,
  onSaved,
  onStateAction,
  onDirtyChange,
}: {
  id: string;
  initialTab?: string;
  onClose: () => void;
  onSaved: (message: string) => Promise<unknown>;
  onStateAction: (row: PartnerDetail) => void;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const read = useCallback(() => getPartner(id), [id]);
  const detail = useAdminRead(read);
  const [tab, setTab] = useState(initialTab),
    [editing, setEditing] = useState(false),
    [dirty, setDirty] = useState(false),
    [closeConfirm, setCloseConfirm] = useState(false),
    [more, setMore] = useState(false);
  const banks = useCodes("BANK"),
    kinds = useCodes("PARTNER_KIND"),
    industries = useCodes("INDUSTRY");
  const historyRead = useCallback(() => listPartnerHistory(id), [id]);
  const history = useAdminRead(historyRead);
  const close = useCallback(() => {
    if (dirty) setCloseConfirm(true);
    else onClose();
  }, [dirty, onClose]);
  useEffect(() => {
    onDirtyChange?.(dirty);
    return () => onDirtyChange?.(false);
  }, [dirty, onDirtyChange]);
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
  useEffect(() => {
    const unload = (e: BeforeUnloadEvent) => {
      if (dirty) {
        e.preventDefault();
        e.returnValue = "";
      }
    };
    window.addEventListener("beforeunload", unload);
    return () => window.removeEventListener("beforeunload", unload);
  }, [dirty]);
  const row = detail.data;
  const name = (
    group: ReturnType<typeof useCodes>,
    code: string | null | undefined,
  ) => group.codes.find((c) => c.code === code)?.name ?? code ?? "–";
  return (
    <div
      className={styles.scrim}
      data-testid="partner-drawer-backdrop"
      onClick={(e) => {
        if (e.target === e.currentTarget) close();
      }}
    >
      <aside
        role="dialog"
        aria-modal="true"
        aria-label="거래처 상세"
        className={styles.drawer}
        data-testid="partner-drawer"
      >
        <div className={styles.drawerHeader}>
          <h2>{row?.ptnr_nm ?? "거래처 상세"}</h2>
          <Button aria-label="상세 닫기" onClick={close}>
            ×
          </Button>
        </div>
        {row && (
          <>
            <p>
              {editing ? "수정 중" : name(kinds, row.ptnr_se_cd)} ·{" "}
              {row.biz_reg_no}
            </p>
            {!editing && (
              <div className={`${styles.actions} ${styles.writeActions}`}>
                <Can perm="UI-15.update">
                  <Button onClick={() => setEditing(true)}>수정</Button>
                </Can>
                <Button aria-label="더보기" onClick={() => setMore(!more)}>
                  ⋯
                </Button>
                {more && (
                  <div role="menu">
                    <Button
                      role="menuitem"
                      onClick={() => {
                        setTab("history");
                        setMore(false);
                      }}
                    >
                      변경 이력 보기
                    </Button>
                    <Can perm="UI-15.delete">
                      <Button
                        role="menuitem"
                        onClick={() => onStateAction(row)}
                      >
                        {row.use_yn === "Y" ? "사용 중지" : "다시 사용"}
                      </Button>
                    </Can>
                  </div>
                )}
              </div>
            )}
            {row.use_yn === "N" && (
              <LockedBanner>
                사용을 중지한 거래처입니다. 새 프로젝트·지급에 고를 수 없습니다.
              </LockedBanner>
            )}
          </>
        )}
        <ErrorBanner
          error={detail.error}
          onRetry={() => void detail.reload().catch(() => {})}
        />
        {detail.loading && !row && <Skeleton />}
        {row &&
          (editing ? (
            <PartnerForm
              initial={row}
              onDirtyChange={setDirty}
              onCancel={() => {
                setEditing(false);
                setDirty(false);
              }}
              reload={detail.reload}
              onSaved={(saved) => {
                setEditing(false);
                setDirty(false);
                void history.reload().catch(() => {});
                void onSaved(saved ? "저장했습니다." : "바뀐 내용이 없습니다.");
              }}
            />
          ) : (
            <>
              <Tabs
                selected={tab}
                onChange={setTab}
                items={[
                  { key: "basic", label: "기본 정보" },
                  { key: "assignments", label: "투입 내역" },
                  {
                    key: "history",
                    label: `변경 이력 (${history.data?.length ?? 0})`,
                  },
                ]}
              />
              <div role="tabpanel" data-testid={`partner-${tab}-panel`}>
                {tab === "basic" && (
                  <dl className={styles.details}>
                    <dt>거래처 코드</dt>
                    <dd>{row.ptnr_no}</dd>
                    <dt>구분</dt>
                    <dd>
                      <Badge
                        tone={row.ptnr_se_cd === "CUST" ? "info" : "neutral"}
                      >
                        {name(kinds, row.ptnr_se_cd)}
                      </Badge>
                    </dd>
                    <dt>상호</dt>
                    <dd>{row.ptnr_nm}</dd>
                    <dt>사업자번호</dt>
                    <dd>{row.biz_reg_no}</dd>
                    <dt>대표자</dt>
                    <dd>{row.ceo_nm ?? "–"}</dd>
                    <dt>업종</dt>
                    <dd>{name(industries, row.indty_cd)}</dd>
                    <dt>담당자</dt>
                    <dd>{row.chrg_nm ?? "–"}</dd>
                    <dt>연락처</dt>
                    <dd>{row.chrg_tel_no ?? "–"}</dd>
                    {row.ptnr_se_cd !== "VEND" && (
                      <>
                        <dt>세금계산서 이메일</dt>
                        <dd>{row.txinv_email_addr ?? "–"}</dd>
                      </>
                    )}
                    {row.ptnr_se_cd !== "CUST" && (
                      <>
                        <dt>지급 계좌</dt>
                        <dd data-sensitive="true">
                          {name(banks, row.bank_cd)} {row.acnt_no} /{" "}
                          {row.dpstr_nm}
                        </dd>
                      </>
                    )}
                    <dt>비고</dt>
                    <dd>{row.rmrk_desc ?? "–"}</dd>
                    <dt>사용 여부</dt>
                    <dd>
                      <Badge tone={row.use_yn === "Y" ? "success" : "neutral"}>
                        {row.use_yn === "Y" ? "사용" : "중지"}
                      </Badge>
                    </dd>
                  </dl>
                )}
                {tab === "assignments" && <PartnerAssignments id={id} />}
                {tab === "history" && (
                  <>
                    <ErrorBanner
                      error={history.error}
                      onRetry={() => void history.reload().catch(() => {})}
                    />
                    {history.loading ? (
                      <Skeleton />
                    ) : history.data?.length ? (
                      <DataTable
                        rows={history.data}
                        total={history.data.length}
                        rowKey={(r) => `${r.changedAt}-${r.column}`}
                        columns={[
                          {
                            key: "changedAt",
                            label: "일시",
                            render: (r) =>
                              r.changedAt.replace("T", " ").slice(0, 16),
                          },
                          {
                            key: "itemName",
                            label: "항목",
                            render: (r) => r.itemName,
                          },
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
                    ) : (
                      !history.error && (
                        <p className={styles.empty}>
                          아직 바꾼 기록이 없습니다.
                        </p>
                      )
                    )}
                  </>
                )}
              </div>
            </>
          ))}
      </aside>
      {closeConfirm && (
        <ConfirmDialog
          title="저장하지 않은 내용이 있습니다"
          confirmLabel="닫기"
          cancelLabel="계속 작성"
          onCancel={() => setCloseConfirm(false)}
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
function PartnerAssignments({ id }: { id: string }) {
  const read = useCallback(() => listPartnerAssignments(id), [id]);
  const state = useAdminRead(read);
  return (
    <>
      <ErrorBanner
        error={state.error}
        onRetry={() => void state.reload().catch(() => {})}
      />
      {state.loading ? (
        <Skeleton />
      ) : state.data?.length ? (
        <DataTable
          rows={state.data}
          total={state.data.length}
          rowKey={(r) =>
            `${r.projectName}-${r.developerName}-${r.jobCode}-${r.endDate}`
          }
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
            { key: "affiliation", label: "소속", render: (r) => r.affiliation },
            { key: "jobName", label: "직무", render: (r) => r.jobName },
            {
              key: "assignmentRate",
              label: "투입률",
              render: (r) => `${(r.assignmentRate * 100).toFixed(1)}%`,
            },
            { key: "endDate", label: "철수일", render: (r) => r.endDate },
          ]}
        />
      ) : (
        !state.error && (
          <p className={styles.empty}>지금 투입 중인 내역이 없습니다.</p>
        )
      )}
    </>
  );
}
