import { useEffect, useRef, useState, type ReactNode } from "react";
import {
  Button,
  Badge,
  ConfirmDialog,
  Skeleton,
  LockedBanner,
} from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useCodes } from "../shared/useCodes";
import { PartnerForm } from "./PartnerForm";
import {
  getPartner,
  getPartnerAssignments,
  getPartnerHistory,
  type PartnerDetail,
} from "./client";
import { usePartnerRead } from "./usePartnerRead";
import styles from "./Partners.module.css";

export function PartnerDrawer({
  id,
  initialTab = "basic",
  onClose,
  onSaved,
  onDirtyChange,
  renderActions,
  renderActivity,
}: {
  id: string;
  initialTab?: string;
  onClose: () => void;
  onSaved: () => void;
  onDirtyChange?: (dirty: boolean) => void;
  renderActions?: (
    row: PartnerDetail,
    onChanged: (row: PartnerDetail) => void,
  ) => ReactNode;
  renderActivity?: (id: string, tab: string) => ReactNode;
}) {
  const detail = usePartnerRead(id, (signal) => getPartner(id, signal));
  const [saved, setSaved] = useState<PartnerDetail | null>(null),
    [editing, setEditing] = useState(false),
    [tab, setTab] = useState(initialTab),
    [dirty, setDirty] = useState(false),
    [confirmClose, setConfirmClose] = useState(false),
    [more, setMore] = useState(false),
    [message, setMessage] = useState("");
  const panel = useRef<HTMLElement>(null);
  const banks = useCodes("BANK"),
    kinds = useCodes("PARTNER_KIND"),
    industries = useCodes("INDUSTRY");
  const row = saved ?? detail.data;
  const close = () => {
    if (dirty) setConfirmClose(true);
    else onClose();
  };
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    panel.current?.focus();
    return () => previous?.focus();
  }, []);
  useEffect(() => {
    onDirtyChange?.(dirty);
    return () => onDirtyChange?.(false);
  }, [dirty, onDirtyChange]);
  const nameOf = (
    codes: { code: string; name: string }[],
    code: string | null | undefined,
  ) => codes.find((c) => c.code === code)?.name ?? code ?? "–";
  return (
    <div
      data-testid="partner-drawer-backdrop"
      className={styles.backdrop}
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) close();
      }}
    >
      <aside
        ref={panel}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label={row?.ptnr_nm ?? "거래처 상세"}
        data-testid="partner-detail-drawer"
        className={styles.drawer}
        onKeyDown={(e) => {
          if (
            (e.target as HTMLElement).closest('[role="dialog"]') !==
            panel.current
          )
            return;
          if (e.key === "Escape" && !confirmClose) {
            e.stopPropagation();
            e.preventDefault();
            close();
          }
          if (e.key === "Tab") {
            const elements = Array.from(
              panel.current?.querySelectorAll<HTMLElement>(
                'button:not(:disabled),input:not(:disabled),select:not(:disabled),textarea:not(:disabled),a[href],[tabindex="0"]',
              ) ?? [],
            );
            const first = elements[0],
              last = elements[elements.length - 1];
            if (
              e.shiftKey &&
              (document.activeElement === first ||
                document.activeElement === panel.current)
            ) {
              e.preventDefault();
              last?.focus();
            } else if (!e.shiftKey && document.activeElement === last) {
              e.preventDefault();
              first?.focus();
            }
          }
        }}
      >
        <header className={styles.drawerHeader}>
          <div>
            <h2>{row?.ptnr_nm ?? "거래처 상세"}</h2>
            <p>
              {editing ? "수정 중" : nameOf(kinds.codes, row?.ptnr_se_cd)} ·{" "}
              {row?.biz_reg_no}
            </p>
          </div>
          <div className={styles.actions}>
            {!editing && row && (
              <Button
                data-testid="partner-edit-button"
                className={styles.desktopAction}
                onClick={() => setEditing(true)}
              >
                수정
              </Button>
            )}
            <Button
              data-testid="partner-more-button"
              aria-expanded={more}
              aria-label="더보기"
              onClick={() => setMore(!more)}
            >
              ⋯
            </Button>
            <Button data-testid="partner-close-button" onClick={close}>
              닫기
            </Button>
          </div>
        </header>
        {more && (
          <div>
            <Button
              data-testid="partner-view-history-button"
              onClick={() => {
                setTab("history");
                setMore(false);
              }}
            >
              변경 이력 보기
            </Button>
            {row && !editing && (
              <div className={styles.desktopAction}>
                {renderActions?.(row, (next) => {
                  setSaved(next);
                  onSaved();
                })}
              </div>
            )}
          </div>
        )}
        <ErrorBanner error={detail.error} onRetry={detail.retry} />
        {detail.loading && <Skeleton />}
        {row && (
          <>
            {row.use_yn === "N" && (
              <LockedBanner>
                사용을 중지한 거래처입니다. 새 프로젝트·지급에 고를 수 없습니다.
              </LockedBanner>
            )}
            {message && <p role="status">{message}</p>}
            {editing ? (
              <div className={styles.desktopAction}>
                <PartnerForm
                  initial={row}
                  onDirtyChange={setDirty}
                  onCancel={() => {
                    setEditing(false);
                    setDirty(false);
                  }}
                  onSaved={(next, changed) => {
                    setSaved(next);
                    setEditing(false);
                    setDirty(false);
                    setMessage(
                      changed ? "저장했습니다." : "바뀐 내용이 없습니다.",
                    );
                    onSaved();
                  }}
                />
              </div>
            ) : (
              <>
                <div role="tablist" className={styles.tabs}>
                  {[
                    ["basic", "기본 정보"],
                    ["assignments", "투입 내역"],
                    ["history", "변경 이력"],
                  ].map(([key, label]) => (
                    <button
                      data-testid={`partner-${key}-tab`}
                      key={key}
                      role="tab"
                      aria-selected={tab === key}
                      onClick={() => setTab(key)}
                    >
                      {label}
                    </button>
                  ))}
                </div>
                <section role="tabpanel" data-testid={`partner-${tab}-panel`}>
                  {tab === "basic" && (
                    <dl className={styles.details}>
                      <dt>거래처 코드</dt>
                      <dd>{row.ptnr_no}</dd>
                      <dt>구분</dt>
                      <dd>
                        <Badge>{nameOf(kinds.codes, row.ptnr_se_cd)}</Badge>
                      </dd>
                      <dt>상호</dt>
                      <dd>{row.ptnr_nm}</dd>
                      <dt>사업자번호</dt>
                      <dd>{row.biz_reg_no}</dd>
                      <dt>대표자</dt>
                      <dd>{row.ceo_nm || "–"}</dd>
                      <dt>업종</dt>
                      <dd>{nameOf(industries.codes, row.indty_cd)}</dd>
                      <dt>담당자</dt>
                      <dd>{row.chrg_nm || "–"}</dd>
                      <dt>연락처</dt>
                      <dd>{row.chrg_tel_no || "–"}</dd>
                      {row.ptnr_se_cd !== "VEND" && (
                        <>
                          <dt>세금계산서 이메일</dt>
                          <dd>{row.txinv_email_addr || "–"}</dd>
                        </>
                      )}
                      {row.ptnr_se_cd !== "CUST" && (
                        <>
                          <dt>지급 계좌</dt>
                          <dd>
                            {nameOf(banks.codes, row.bank_cd)}{" "}
                            {row.acnt_no || "–"} / {row.dpstr_nm || "–"}
                          </dd>
                        </>
                      )}
                      <dt>비고</dt>
                      <dd>{row.rmrk_desc || "–"}</dd>
                      <dt>사용 여부</dt>
                      <dd>
                        <Badge
                          tone={row.use_yn === "Y" ? "success" : "neutral"}
                        >
                          {row.use_yn === "Y" ? "사용" : "중지"}
                        </Badge>
                      </dd>
                    </dl>
                  )}
                  {tab !== "basic" && renderActivity ? (
                    renderActivity(id, tab)
                  ) : (
                    <>
                      {tab === "assignments" && <PartnerAssignments id={id} />}
                      {tab === "history" && <PartnerHistory id={id} />}
                    </>
                  )}
                </section>
              </>
            )}
          </>
        )}
      </aside>
      {confirmClose && (
        <ConfirmDialog
          title="저장하지 않은 내용이 있습니다"
          confirmLabel="닫기"
          cancelLabel="계속 작성"
          onCancel={() => setConfirmClose(false)}
          onConfirm={onClose}
        >
          창을 닫으면 고친 내용이 사라집니다.
        </ConfirmDialog>
      )}
    </div>
  );
}
function PartnerAssignments({ id }: { id: string }) {
  const read = usePartnerRead(id, (signal) =>
    getPartnerAssignments(id, signal),
  );
  return (
    <>
      <ErrorBanner error={read.error} onRetry={read.retry} />
      {read.loading && <Skeleton />}
      {read.data &&
        (read.data.length ? (
          <div className={styles.tableScroll}>
            <table>
              <thead>
                <tr>
                  {["프로젝트", "이름", "소속", "직무", "투입률", "철수일"].map(
                    (t) => (
                      <th
                        data-testid={`partner-assignment-header-${["프로젝트", "이름", "소속", "직무", "투입률", "철수일"].indexOf(t)}`}
                        key={t}
                      >
                        {t}
                      </th>
                    ),
                  )}
                </tr>
              </thead>
              <tbody>
                {read.data.map((r, index) => (
                  <tr
                    data-testid={`partner-assignment-row-${index}`}
                    key={`${r.projectName}-${r.developerName}-${index}`}
                  >
                    <td>{r.projectName}</td>
                    <td>{r.developerName}</td>
                    <td>{r.affiliation}</td>
                    <td>{r.jobName}</td>
                    <td>{(r.assignmentRate * 100).toLocaleString("ko-KR")}%</td>
                    <td>{r.endDate}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p>현재 투입 중인 인력이 없습니다.</p>
        ))}
    </>
  );
}
function PartnerHistory({ id }: { id: string }) {
  const read = usePartnerRead(id, (signal) => getPartnerHistory(id, signal));
  return (
    <>
      <ErrorBanner error={read.error} onRetry={read.retry} />
      {read.loading && <Skeleton />}
      {read.data &&
        (read.data.length ? (
          <div className={styles.tableScroll}>
            <table>
              <thead>
                <tr>
                  {["일시", "항목", "바꾸기 전", "바꾼 뒤", "처리자"].map(
                    (t) => (
                      <th
                        data-testid={`partner-history-header-${["일시", "항목", "바꾸기 전", "바꾼 뒤", "처리자"].indexOf(t)}`}
                        key={t}
                      >
                        {t}
                      </th>
                    ),
                  )}
                </tr>
              </thead>
              <tbody>
                {read.data.map((r, index) => (
                  <tr
                    data-testid={`partner-history-row-${index}`}
                    key={`${r.changedAt}-${index}`}
                  >
                    <td>{r.changedAt}</td>
                    <td>{r.itemName}</td>
                    <td>{r.before ?? "–"}</td>
                    <td>{r.after ?? "–"}</td>
                    <td>{r.actorName}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p>변경 이력이 없습니다.</p>
        ))}
    </>
  );
}
