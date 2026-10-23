import { useEffect, useState } from "react";
import {
  Badge,
  Button,
  ConfirmDialog,
  Skeleton,
  Tabs,
} from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import { useCodes } from "../shared/useCodes";
import {
  getPartner,
  listPartnerAssignments,
  listPartnerHistory,
  type PartnerDetail,
  type PartnerAssignment,
  type PartnerChange,
} from "./client";
import { PartnerForm } from "./PartnerForm";
import styles from "./Partners.module.css";

export function PartnerDrawer({
  id,
  initialTab = "basic",
  onClose,
  onSaved,
  onStatus,
  onDirtyChange,
  peopleCount,
}: {
  id: string;
  initialTab?: string;
  onClose: () => void;
  onSaved: (row: PartnerDetail, changed: boolean) => void;
  onStatus: (row: PartnerDetail) => void;
  onDirtyChange?: (dirty: boolean) => void;
  peopleCount?: number;
}) {
  const [row, setRow] = useState<PartnerDetail | null>(null),
    [error, setError] = useState<ApiFailure | null>(null),
    [loading, setLoading] = useState(true),
    [revision, setRevision] = useState(0);
  const [tab, setTab] = useState(initialTab),
    [edit, setEdit] = useState(false),
    [dirty, setDirty] = useState(false),
    [closeConfirm, setCloseConfirm] = useState(false),
    [more, setMore] = useState(false);
  const [assignments, setAssignments] = useState<PartnerAssignment[] | null>(
      null,
    ),
    [history, setHistory] = useState<PartnerChange[] | null>(null);
  const kinds = useCodes("PARTNER_KIND"),
    industry = useCodes("INDUSTRY"),
    banks = useCodes("BANK");
  useEffect(() => {
    onDirtyChange?.(dirty);
    return () => onDirtyChange?.(false);
  }, [dirty, onDirtyChange]);
  const close = () => {
    if (dirty) setCloseConfirm(true);
    else onClose();
  };
  useEffect(() => {
    const abort = new AbortController();
    setLoading(true);
    setError(null);
    getPartner(id, abort.signal)
      .then(setRow)
      .catch((e) => {
        if (!abort.signal.aborted) setError(e);
      })
      .finally(() => {
        if (!abort.signal.aborted) setLoading(false);
      });
    return () => abort.abort();
  }, [id, revision]);
  useEffect(() => {
    const abort = new AbortController();
    listPartnerHistory(id, abort.signal)
      .then(setHistory)
      .catch((e) => {
        if (!abort.signal.aborted && tab === "history") setError(e);
      });
    listPartnerAssignments(id, abort.signal)
      .then(setAssignments)
      .catch((e) => {
        if (!abort.signal.aborted && tab === "assignments") setError(e);
      });
    return () => abort.abort();
  }, [id, revision, tab]);
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        if (dirty) setCloseConfirm(true);
        else onClose();
      }
    };
    window.addEventListener("keydown", key);
    return () => window.removeEventListener("keydown", key);
  }, [dirty, onClose]);
  return (
    <div
      data-testid="partner-drawer-backdrop"
      className={styles.backdrop}
      onClick={(e) => {
        if (e.target === e.currentTarget) close();
      }}
    >
      <aside
        role="dialog"
        aria-modal="true"
        aria-label="거래처 상세"
        data-testid="partner-drawer"
        className={styles.drawer}
      >
        <header>
          <div>
            <h2>{row?.ptnr_nm ?? "거래처 상세"}</h2>
            <p>
              {edit
                ? "수정 중"
                : (kinds.codes.find((c) => c.code === row?.ptnr_se_cd)?.name ??
                  "–")}{" "}
              · {row?.biz_reg_no}
            </p>
          </div>
          <div className={styles.actions}>
            {!edit && row && (
              <Can perm="UI-15.update">
                <Button
                  data-testid="partner-edit"
                  className={styles.mutation}
                  onClick={() => setEdit(true)}
                >
                  수정
                </Button>
              </Can>
            )}
            {!edit && row && (
              <Button
                data-testid="partner-more"
                aria-label="더보기"
                onClick={() => setMore(!more)}
              >
                ⋯
              </Button>
            )}
            <Button data-testid="partner-close" onClick={close}>
              닫기
            </Button>
          </div>
        </header>
        {more && row && (
          <div className={styles.actions}>
            <Button
              data-testid="partner-history-shortcut"
              onClick={() => {
                setTab("history");
                setMore(false);
              }}
            >
              변경 이력 보기
            </Button>
            <Can perm="UI-15.delete">
              <Button
                data-testid="partner-status"
                className={styles.mutation}
                onClick={() => onStatus(row)}
              >
                {row.use_yn === "Y" ? "사용 중지" : "다시 사용"}
              </Button>
            </Can>
          </div>
        )}
        <ErrorBanner error={error} onRetry={() => setRevision((r) => r + 1)} />
        {loading && <Skeleton />}
        {row && (
          <>
            {row.use_yn === "N" && (
              <p className={styles.notice}>
                사용을 중지한 거래처입니다. 새 프로젝트·지급에 고를 수 없습니다.
              </p>
            )}
            {edit ? (
              <PartnerForm
                initial={row}
                onDirtyChange={setDirty}
                onCancel={() => {
                  setDirty(false);
                  setEdit(false);
                }}
                onSaved={(saved, changed) => {
                  setRow(saved);
                  setEdit(false);
                  setDirty(false);
                  setRevision((r) => r + 1);
                  onSaved(saved, changed);
                }}
              />
            ) : (
              <>
                <Tabs
                  selected={tab}
                  onChange={(t) => {
                    setError(null);
                    setTab(t);
                  }}
                  items={[
                    { key: "basic", label: "기본 정보" },
                    {
                      key: "assignments",
                      label: `투입 내역${peopleCount !== undefined ? ` (${peopleCount}명)` : ""}`,
                    },
                    {
                      key: "history",
                      label: `변경 이력${history ? ` (${history.length})` : ""}`,
                    },
                  ]}
                />
                <div role="tabpanel" data-testid="partner-detail-panel">
                  {tab === "basic" && (
                    <dl className={styles.details}>
                      {(
                        [
                          ["거래처 코드", row.ptnr_no],
                          ["사용 여부", row.use_yn === "Y" ? "사용" : "중지"],
                          [
                            "구분",
                            kinds.codes.find((c) => c.code === row.ptnr_se_cd)
                              ?.name,
                          ],
                          ["상호", row.ptnr_nm],
                          ["사업자번호", row.biz_reg_no],
                          ["대표자", row.ceo_nm],
                          [
                            "업종",
                            industry.codes.find((c) => c.code === row.indty_cd)
                              ?.name,
                          ],
                          ["담당자", row.chrg_nm],
                          ["연락처", row.chrg_tel_no],
                          ["비고", row.rmrk_desc],
                          ...(row.ptnr_se_cd !== "VEND"
                            ? ([
                                ["세금계산서 이메일", row.txinv_email_addr],
                              ] as const)
                            : []),
                          ...(row.ptnr_se_cd !== "CUST"
                            ? ([
                                [
                                  "지급 계좌",
                                  `${banks.codes.find((c) => c.code === row.bank_cd)?.name ?? "–"} ${row.acnt_no ?? "–"} / ${row.dpstr_nm ?? "–"}`,
                                ],
                              ] as const)
                            : []),
                        ] as const
                      ).map(([label, value]) => (
                        <div
                          data-testid={`partner-detail-field-${["거래처 코드", "사용 여부", "구분", "상호", "사업자번호", "대표자", "업종", "담당자", "연락처", "비고", "세금계산서 이메일", "지급 계좌"].indexOf(label)}`}
                          key={label}
                        >
                          <dt>{label}</dt>
                          <dd>{value || "–"}</dd>
                        </div>
                      ))}
                    </dl>
                  )}
                  {tab === "assignments" &&
                    (assignments === null ? (
                      !error && <Skeleton />
                    ) : assignments.length === 0 ? (
                      <p>지금 투입 중인 내역이 없습니다.</p>
                    ) : (
                      <table>
                        <thead>
                          <tr>
                            {[
                              "프로젝트",
                              "이름",
                              "소속",
                              "직무",
                              "투입률",
                              "철수일",
                            ].map((s) => (
                              <th
                                data-testid={`partner-drawer-assignment-heading-${["프로젝트", "이름", "소속", "직무", "투입률", "철수일"].indexOf(s)}`}
                                key={s}
                              >
                                {s}
                              </th>
                            ))}
                          </tr>
                        </thead>
                        <tbody>
                          {assignments.map((a, i) => (
                            <tr
                              data-testid={`partner-assignment-${i}`}
                              key={`${a.projectName}-${a.developerName}-${i}`}
                            >
                              <td>{a.projectName}</td>
                              <td>{a.developerName}</td>
                              <td>{a.affiliation}</td>
                              <td>{a.jobName}</td>
                              <td>{(a.assignmentRate * 100).toFixed(1)}%</td>
                              <td>{a.endDate}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    ))}
                  {tab === "history" &&
                    (history === null ? (
                      !error && <Skeleton />
                    ) : history.length === 0 ? (
                      <p>아직 바꾼 기록이 없습니다.</p>
                    ) : (
                      <table>
                        <thead>
                          <tr>
                            {[
                              "일시",
                              "항목",
                              "바꾸기 전",
                              "바꾼 뒤",
                              "처리자",
                            ].map((s) => (
                              <th
                                data-testid={`partner-drawer-history-heading-${["일시", "항목", "바꾸기 전", "바꾼 뒤", "처리자"].indexOf(s)}`}
                                key={s}
                              >
                                {s}
                              </th>
                            ))}
                          </tr>
                        </thead>
                        <tbody>
                          {history.map((h, i) => (
                            <tr
                              data-testid={`partner-history-${i}`}
                              key={`${h.changedAt}-${i}`}
                            >
                              <td>{h.changedAt}</td>
                              <td>{h.itemName}</td>
                              <td>{h.before ?? "–"}</td>
                              <td>{h.after ?? "–"}</td>
                              <td>{h.actorName}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    ))}
                </div>
              </>
            )}
          </>
        )}
      </aside>
      {closeConfirm && (
        <ConfirmDialog
          title="저장하지 않은 내용이 있습니다"
          confirmLabel="닫기"
          cancelLabel="계속 작성"
          onCancel={() => setCloseConfirm(false)}
          onConfirm={onClose}
        >
          창을 닫으면 고친 내용이 사라집니다.
        </ConfirmDialog>
      )}
    </div>
  );
}
