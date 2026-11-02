import { useCallback, useEffect, useState, type ReactNode } from "react";
import {
  Badge,
  Button,
  LockedBanner,
  Modal,
  Pagination,
  Skeleton,
} from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import { formatMoney } from "../../lib/format";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { ScreenHeading } from "../shared/ScreenHeading";
import { useCodes } from "../shared/useCodes";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import {
  createFinanceEntry,
  deleteFinanceEntry,
  listFinanceEntries,
  previewPreviousFinanceEntries,
  updateFinanceEntry,
  type FinanceEntry,
  type FinanceEntryInput,
} from "./client";
import styles from "./Entries.module.css";

function currentMonth() {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
  }).formatToParts(new Date());
  return `${parts.find((p) => p.type === "year")!.value}-${parts.find((p) => p.type === "month")!.value}`;
}
export interface EntriesScreenProps {
  initialMonth?: string;
  onMonthChange?: (month: string) => void;
  todayMonth?: string;
  name?: string;
  groupName?: string;
  onDirtyChange?: (dirty: boolean) => void;
  onUpload?: (month: string) => void;
  onTemplate?: () => void;
  taxPanel?: ReactNode;
  taxMonthlyAmount?: number | null;
  onTax?: () => void;
  refreshVersion?: number;
}
export default function UI10Screen(props: EntriesScreenProps) {
  const mobile = useDeveloperReadOnly();
  if (mobile)
    return (
      <section data-testid="entries-mobile">
        <h1>재무 항목</h1>
        <p>PC에서 이용해 주세요.</p>
        <a data-testid="entries-dashboard" href="/UI-16">
          대시보드로
        </a>
      </section>
    );
  return <EntriesDesktop {...props} />;
}
function EntriesDesktop({
  initialMonth,
  onMonthChange,
  todayMonth = currentMonth(),
  name = "재무 항목",
  groupName = "재무·자금",
  onDirtyChange,
  onUpload,
  onTemplate,
  taxPanel,
  taxMonthlyAmount,
  onTax,
  refreshVersion = 0,
}: EntriesScreenProps) {
  const [month, setMonth] = useState(initialMonth ?? todayMonth);
  useEffect(() => {
    onMonthChange?.(month);
  }, [month, onMonthChange]);
  const [page, setPage] = useState(1),
    [size, setSize] = useState<20 | 50 | 100>(20);
  const [localDrafts, setLocalDrafts] = useState<
    Record<string, FinanceEntry[]>
  >({});
  const [editing, setEditing] = useState<FinanceEntry | "new" | null>(null);
  const [copying, setCopying] = useState(false),
    [message, setMessage] = useState("");
  const codes = useCodes("FIN_CATEGORY");
  const read = useCallback(
    async () => ({ month, result: await listFinanceEntries(month) }),
    [month, refreshVersion],
  );
  const state = useAdminRead(read);
  const data = state.data?.month === month ? state.data.result : null;
  const readonly =
    !data ||
    state.loading ||
    !!state.error ||
    data.month_status === "CLOSED" ||
    month.slice(0, 4) < todayMonth.slice(0, 4);
  const write = useAdminWrite(state.reload);
  useEffect(() => {
    if (!state.data || state.loading || state.error) return;
    const { month: target, result } = state.data;
    setLocalDrafts((old) => {
      const saved = new Set(result.items.map((r) => r.copy_entry_id));
      const existing = (old[target] ?? []).filter(
        (r) => !saved.has(r.copy_entry_id),
      );
      const sources = new Set(existing.map((r) => r.copy_entry_id));
      return {
        ...old,
        [target]: [
          ...existing,
          ...result.drafts.filter(
            (r) => !sources.has(r.copy_entry_id) && !saved.has(r.copy_entry_id),
          ),
        ],
      };
    });
  }, [state.data, state.loading, state.error]);
  const drafts = localDrafts[month] ?? [];
  useEffect(() => {
    onDirtyChange?.(
      !!editing || Object.values(localDrafts).some((d) => d.length > 0),
    );
  }, [editing, localDrafts, onDirtyChange]);
  const choose = (target: string) => {
    setMonth(target);
    setPage(1);
    setEditing(null);
    setCopying(false);
    setMessage("");
    write.clear();
  };
  const keep = (row: FinanceEntry) => {
    setLocalDrafts((old) => ({
      ...old,
      [month]: [
        ...(old[month] ?? []).filter(
          (r) => r.copy_entry_id !== row.copy_entry_id,
        ),
        row,
      ],
    }));
    setEditing(null);
  };
  const save = (input: FinanceEntryInput, id: number | null) => {
    if (readonly) return;
    void write.save(
      () =>
        id == null ? createFinanceEntry(input) : updateFinanceEntry(id, input),
      () => {
        setEditing(null);
        setMessage("저장했습니다.");
        if (input.copy_entry_id != null)
          setLocalDrafts((old) => ({
            ...old,
            [month]: (old[month] ?? []).filter(
              (r) => r.copy_entry_id !== input.copy_entry_id,
            ),
          }));
      },
    );
  };
  const category = (code: string) =>
    codes.codes.find((c) => c.code === code)?.name ?? code;
  const rowCells = (row: FinanceEntry) => (
    <>
      <td>{category(row.fin_se_cd)}</td>
      <td>
        {row.item_nm}{" "}
        {row.auto_fill_yn === "Y" && <Badge tone="info">자동</Badge>}
      </td>
      <td className={styles.amount}>{formatMoney(BigInt(row.entry_amt))}</td>
      <td className={styles.amount}>{formatMoney(BigInt(row.vat_amt))}</td>
      <td>
        {row.inout_dt ?? "–"}{" "}
        {row.inout_dt && (
          <Badge tone={row.inout_cmpl_yn === "Y" ? "success" : "info"}>
            {row.inout_cmpl_yn === "Y" ? "완료" : "예정"}
          </Badge>
        )}
      </td>
      <td>{row.rpt_yn === "Y" ? "반복" : "–"}</td>
      <td className={styles.memo}>{row.remark ?? "–"}</td>
    </>
  );
  return (
    <section data-testid="entries-screen" data-screen-id="SCR-UI-10">
      <ScreenHeading
        screenId="UI-10"
        name={name}
        groupName={groupName}
        actions={
          <>
            {!readonly && (
              <Can perm="UI-10.read">
                <Button
                  data-testid="entries-copy"
                  onClick={() => setCopying(true)}
                  disabled={write.pending}
                >
                  지난달 항목 불러오기
                </Button>
              </Can>
            )}
            {onTemplate && (
              <Can perm="UI-10.download">
                <Button data-testid="entries-template" onClick={onTemplate}>
                  양식 내려받기
                </Button>
              </Can>
            )}
            {!readonly && onUpload && (
              <Can perm="UI-10.upload">
                <Button
                  data-testid="entries-upload"
                  onClick={() => onUpload(month)}
                >
                  엑셀 업로드
                </Button>
              </Can>
            )}
          </>
        }
      />
      <div className={styles.period}>
        <div className={styles.toolbar}>
          <Button
            data-testid="entries-previous-year"
            aria-label="이전 해"
            disabled={Number(month.slice(0, 4)) <= 1 || write.pending}
            onClick={() =>
              choose(
                `${String(Number(month.slice(0, 4)) - 1).padStart(4, "0")}-01`,
              )
            }
          >
            ‹
          </Button>
          <strong>{month.slice(0, 4)}년 입력 현황</strong>
          <Button
            data-testid="entries-next-year"
            aria-label="다음 해"
            disabled={
              month.slice(0, 4) >= todayMonth.slice(0, 4) || write.pending
            }
            onClick={() =>
              choose(
                `${String(Number(month.slice(0, 4)) + 1).padStart(4, "0")}-01`,
              )
            }
          >
            ›
          </Button>
        </div>
        <div className={styles.months} role="tablist" aria-label="입력 월">
          {Array.from({ length: 12 }, (_, i) => {
            const target = `${month.slice(0, 4)}-${String(i + 1).padStart(2, "0")}`;
            const info = data?.months.find((m) => m.month === target);
            return (
              <button
                type="button"
                data-testid={`entries-month-${i + 1}`}
                role="tab"
                key={target}
                aria-selected={target === month}
                disabled={target > todayMonth || write.pending}
                onClick={() => choose(target)}
              >
                {i + 1}월{" "}
                <small>
                  {target > todayMonth
                    ? "미래"
                    : info
                      ? `${info.status === "CLOSED" ? "마감" : "작업중"} · ${info.saved_count}건`
                      : "–"}
                </small>
              </button>
            );
          })}
        </div>
      </div>
      {message && <p role="status">{message}</p>}
      <ErrorBanner
        error={state.error}
        onRetry={() => {
          codes.retry();
          void state.reload().catch(() => {});
        }}
      />
      {!editing && (
        <ErrorBanner
          error={write.error}
          pending={write.pending}
          onRetry={write.uncertain ? () => void write.recover() : undefined}
        />
      )}
      {state.loading ? (
        <Skeleton />
      ) : (
        !state.error &&
        data && (
          <>
            {data.month_status === "CLOSED" ? (
              <LockedBanner>마감된 달입니다. 조회만 가능합니다.</LockedBanner>
            ) : (
              readonly && (
                <LockedBanner>과거 연도는 조회만 가능합니다.</LockedBanner>
              )
            )}
            <div className={styles.toolbar}>
              <h2>
                {Number(month.slice(5))}월 재무 항목{" "}
                <Badge tone={readonly ? "locked" : "info"}>
                  {data.month_status === "CLOSED" ? "마감" : "작업중"}
                </Badge>
              </h2>
              <span>저장된 항목 {data.saved_count}건</span>
            </div>
            <div className={styles.tableScroll}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    {[
                      "구분",
                      "세부 항목",
                      "금액",
                      "부가세",
                      "입출금일",
                      "매달 반복",
                      "비고",
                      "삭제",
                    ].map((h) => (
                      <th key={h}>{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {data.items.length === 0 && (
                    <tr>
                      <td colSpan={8} className={styles.empty}>
                        등록된 재무 항목이 없습니다.
                      </td>
                    </tr>
                  )}
                  {data.items
                    .slice((page - 1) * size, page * size)
                    .map((row) => (
                      <tr
                        data-testid={`entry-row-${row.entry_id}`}
                        key={row.entry_id}
                      >
                        {rowCells(row)}
                        <td>
                          {!readonly && (
                            <>
                              <Can perm="UI-10.update">
                                <Button
                                  small
                                  data-testid={`entry-edit-${row.entry_id}`}
                                  disabled={write.pending}
                                  onClick={() => {
                                    write.clear();
                                    setEditing(row);
                                  }}
                                >
                                  수정
                                </Button>
                              </Can>
                              <Can perm="UI-10.delete">
                                <Button
                                  small
                                  data-testid={`entry-delete-${row.entry_id}`}
                                  disabled={write.pending || write.uncertain}
                                  onClick={() =>
                                    void write.save(
                                      () => deleteFinanceEntry(row.entry_id!),
                                      () => {
                                        setPage(1);
                                        setMessage("삭제했습니다.");
                                      },
                                    )
                                  }
                                >
                                  삭제
                                </Button>
                              </Can>
                            </>
                          )}
                        </td>
                      </tr>
                    ))}
                  <tr data-testid="entries-tax-row">
                    <td>법인세</td>
                    <td>
                      법인세(월할) <Badge>계산</Badge>
                    </td>
                    <td className={styles.amount}>
                      {taxMonthlyAmount == null
                        ? "–"
                        : formatMoney(BigInt(taxMonthlyAmount))}
                    </td>
                    <td colSpan={4}>직접 등록·수정하지 않습니다.</td>
                    <td>
                      {onTax && (
                        <Button data-testid="entries-tax" onClick={onTax}>
                          법인세 상세
                        </Button>
                      )}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p data-testid="entries-totals">
              월 저장 합계 {formatMoney(BigInt(data.total_amount))}원 · 부가세{" "}
              {formatMoney(BigInt(data.total_vat))}원 (초안 제외)
            </p>
            <Pagination
              page={page}
              size={size}
              total={data.total}
              onPage={setPage}
              onSize={(value) => {
                setSize(value);
                setPage(1);
              }}
            />
            {!readonly && (
              <Can perm="UI-10.create">
                <Button
                  data-testid="entry-add"
                  disabled={
                    write.pending ||
                    write.uncertain ||
                    codes.loading ||
                    !!codes.error
                  }
                  onClick={() => {
                    write.clear();
                    setEditing("new");
                  }}
                >
                  + 항목 추가
                </Button>
              </Can>
            )}
            {drafts.length > 0 && (
              <section data-testid="entries-drafts" className={styles.drafts}>
                <h2>저장 전 초안</h2>
                <p>저장하기 전에는 입력 건수·손익·현금에 포함하지 않습니다.</p>
                <div className={styles.tableScroll}>
                  <table className={styles.table}>
                    <tbody>
                      {drafts.map((row) => (
                        <tr
                          data-testid={`entry-draft-${row.copy_entry_id}`}
                          key={row.copy_entry_id}
                        >
                          {rowCells(row)}
                          <td>
                            {!readonly && (
                              <>
                                <Button
                                  data-testid={`entry-draft-edit-${row.copy_entry_id}`}
                                  disabled={write.pending}
                                  onClick={() => {
                                    write.clear();
                                    setEditing(row);
                                  }}
                                >
                                  초안 수정
                                </Button>
                                <Can perm="UI-10.create">
                                  <Button
                                    data-testid={`entry-draft-save-${row.copy_entry_id}`}
                                    disabled={write.pending || write.uncertain}
                                    onClick={() => save(row, null)}
                                  >
                                    저장
                                  </Button>
                                </Can>
                              </>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </section>
            )}
            {taxPanel}
          </>
        )
      )}
      {!state.error && (
        <ErrorBanner error={codes.error} onRetry={codes.retry} />
      )}
      {editing && !readonly && (
        <EntryEditor
          key={
            editing === "new"
              ? "new"
              : `${editing.entry_id}-${editing.copy_entry_id}`
          }
          month={month}
          row={editing === "new" ? null : editing}
          options={codes.codes.map((c) => ({ value: c.code, label: c.name }))}
          pending={write.pending}
          blocked={write.uncertain || codes.loading || !!codes.error}
          error={write.error}
          onRecover={() => void write.recover()}
          onSave={save}
          onKeep={keep}
          onCancel={() => setEditing(null)}
        />
      )}
      {copying && !readonly && (
        <EntryCopy
          month={month}
          onCancel={() => setCopying(false)}
          onApply={(selected) => {
            setLocalDrafts((old) => {
              const current = old[month] ?? [];
              const ids = new Set(current.map((r) => r.copy_entry_id));
              return {
                ...old,
                [month]: [
                  ...current,
                  ...selected.filter((r) => !ids.has(r.copy_entry_id)),
                ],
              };
            });
            setCopying(false);
          }}
        />
      )}
    </section>
  );
}

function EntryEditor({
  month,
  row,
  options,
  pending,
  blocked,
  error,
  onRecover,
  onSave,
  onKeep,
  onCancel,
}: {
  month: string;
  row: FinanceEntry | null;
  options: { value: string; label: string }[];
  pending: boolean;
  blocked: boolean;
  error: ApiFailure | null;
  onRecover: () => void;
  onSave: (input: FinanceEntryInput, id: number | null) => void;
  onKeep: (row: FinanceEntry) => void;
  onCancel: () => void;
}) {
  const [input, setInput] = useState({
    fin_se_cd: row?.fin_se_cd ?? options[0]?.value ?? "",
    item_nm: row?.item_nm ?? "",
    entry_amt: String(row?.entry_amt ?? ""),
    vat_amt: String(row?.vat_amt ?? 0),
    inout_dt: row?.inout_dt ?? "",
    rpt_yn: row?.rpt_yn ?? "N",
    inout_cmpl_yn: row?.inout_cmpl_yn ?? "N",
    remark: row?.remark ?? "",
  });
  const [validation, setValidation] = useState("");
  const update = (key: keyof typeof input, value: string) =>
    setInput((p) => ({ ...p, [key]: value }));
  const payload = (): FinanceEntryInput | null => {
    if (!input.item_nm.trim()) {
      setValidation("세부 항목을 입력하세요.");
      return null;
    }
    if (!input.fin_se_cd) {
      setValidation("구분을 선택하세요.");
      return null;
    }
    if (
      ![input.entry_amt, input.vat_amt].every(
        (v) => /^\d+$/.test(v) && Number.isSafeInteger(Number(v)),
      )
    ) {
      setValidation("금액·부가세는 0 이상 원 단위 정수로 입력하세요.");
      return null;
    }
    if (input.inout_cmpl_yn === "Y" && !input.inout_dt) {
      setValidation("완료 표시에는 입출금일이 필요합니다.");
      return null;
    }
    return {
      entry_ym: month,
      fin_se_cd: input.fin_se_cd,
      item_nm: input.item_nm.trim(),
      entry_amt: Number(input.entry_amt),
      vat_amt: Number(input.vat_amt),
      inout_dt: input.inout_dt || null,
      rpt_yn: input.rpt_yn as "Y" | "N",
      inout_cmpl_yn: input.inout_cmpl_yn as "Y" | "N",
      remark: input.remark || null,
      copy_entry_id: row?.copy_entry_id ?? null,
      auto_fill_yn: row?.auto_fill_yn ?? "N",
    };
  };
  return (
    <Modal
      title={row?.entry_id ? "재무 항목 수정" : "재무 항목 등록"}
      onCancel={() => {
        if (!pending) onCancel();
      }}
    >
      <div data-testid="entry-editor" className={styles.form}>
        <ErrorBanner
          error={error}
          pending={pending}
          onRetry={error?.uncertainWrite ? onRecover : undefined}
        />
        {validation && <p role="alert">{validation}</p>}
        <p>{month} · 법인세는 별도 법인세 상세에서 관리합니다.</p>
        <label>
          구분
          <select
            data-testid="entry-category"
            aria-label="구분"
            disabled={pending}
            value={input.fin_se_cd}
            onChange={(e) => update("fin_se_cd", e.target.value)}
          >
            {options.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </label>
        <label>
          세부 항목
          <input
            data-testid="entry-name"
            aria-label="세부 항목"
            maxLength={100}
            disabled={pending}
            value={input.item_nm}
            onChange={(e) => update("item_nm", e.target.value)}
          />
        </label>
        <label>
          금액
          <input
            data-testid="entry-amount"
            aria-label="금액"
            inputMode="numeric"
            disabled={pending}
            value={input.entry_amt}
            onChange={(e) => update("entry_amt", e.target.value)}
          />
        </label>
        <label>
          부가세
          <input
            data-testid="entry-vat"
            aria-label="부가세"
            inputMode="numeric"
            disabled={pending}
            value={input.vat_amt}
            onChange={(e) => update("vat_amt", e.target.value)}
          />
        </label>
        <label>
          입출금일
          <input
            data-testid="entry-date"
            aria-label="입출금일"
            type="date"
            disabled={pending}
            value={input.inout_dt}
            onChange={(e) => update("inout_dt", e.target.value)}
          />
        </label>
        <label>
          <input
            data-testid="entry-recurring"
            type="checkbox"
            disabled={pending}
            checked={input.rpt_yn === "Y"}
            onChange={(e) => update("rpt_yn", e.target.checked ? "Y" : "N")}
          />
          매달 반복
        </label>
        <label>
          <input
            data-testid="entry-completed"
            type="checkbox"
            disabled={pending}
            checked={input.inout_cmpl_yn === "Y"}
            onChange={(e) =>
              update("inout_cmpl_yn", e.target.checked ? "Y" : "N")
            }
          />
          입출금 완료
        </label>
        <label>
          비고
          <textarea
            data-testid="entry-remark"
            aria-label="비고"
            maxLength={500}
            disabled={pending}
            value={input.remark}
            onChange={(e) => update("remark", e.target.value)}
          />
        </label>
        <div className={styles.actions}>
          <Button
            data-testid="entry-cancel"
            disabled={pending}
            onClick={onCancel}
          >
            취소
          </Button>
          {row && !row.entry_id && (
            <Button
              data-testid="entry-draft-keep"
              disabled={pending}
              onClick={() => {
                const p = payload();
                if (p) onKeep({ ...p, entry_id: null, already_copied: false });
              }}
            >
              초안 보관
            </Button>
          )}
          <Button
            data-testid="entry-save"
            variant="primary"
            pending={pending}
            disabled={blocked}
            onClick={() => {
              const p = payload();
              if (p) onSave(p, row?.entry_id ?? null);
            }}
          >
            저장
          </Button>
        </div>
      </div>
    </Modal>
  );
}
function EntryCopy({
  month,
  onApply,
  onCancel,
}: {
  month: string;
  onApply: (rows: FinanceEntry[]) => void;
  onCancel: () => void;
}) {
  const [include, setInclude] = useState(true),
    [selected, setSelected] = useState<number[]>([]);
  const read = useCallback(
    () => previewPreviousFinanceEntries(month, include),
    [month, include],
  );
  const state = useAdminRead(read);
  return (
    <Modal title="지난달 항목 불러오기" onCancel={onCancel}>
      <section data-testid="entry-copy-dialog">
        <p>선택한 항목을 초안으로 가져옵니다. 저장은 별도입니다.</p>
        <label>
          <input
            data-testid="entry-copy-include"
            type="checkbox"
            checked={include}
            onChange={(e) => {
              setInclude(e.target.checked);
              setSelected([]);
            }}
          />
          반복하지 않는 항목 포함
        </label>
        <ErrorBanner
          error={state.error}
          onRetry={() => void state.reload().catch(() => {})}
        />
        {state.loading ? (
          <Skeleton />
        ) : (
          !state.error &&
          state.data && (
            <>
              {!state.data.items.length && (
                <p className={styles.empty}>불러올 전월 항목이 없습니다.</p>
              )}
              <div className={styles.tableScroll}>
                <table className={styles.table}>
                  <thead>
                    <tr>
                      <th>선택</th>
                      <th>세부 항목</th>
                      <th>금액</th>
                      <th>상태</th>
                    </tr>
                  </thead>
                  <tbody>
                    {state.data.items.map((row) => (
                      <tr
                        data-testid={`entry-copy-row-${row.copy_entry_id}`}
                        key={row.copy_entry_id}
                      >
                        <td>
                          <input
                            data-testid={`entry-copy-select-${row.copy_entry_id}`}
                            aria-label={`${row.item_nm} 선택`}
                            type="checkbox"
                            disabled={row.already_copied}
                            checked={selected.includes(row.copy_entry_id!)}
                            onChange={(e) =>
                              setSelected((s) =>
                                e.target.checked
                                  ? [...s, row.copy_entry_id!]
                                  : s.filter((id) => id !== row.copy_entry_id),
                              )
                            }
                          />
                        </td>
                        <td>{row.item_nm}</td>
                        <td className={styles.amount}>
                          {formatMoney(BigInt(row.entry_amt))}
                        </td>
                        <td>
                          {row.already_copied ? (
                            <Badge tone="locked">이미 불러옴</Badge>
                          ) : (
                            <Badge tone="info">복제 가능</Badge>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )
        )}
        <div className={styles.actions}>
          <Button data-testid="entry-copy-cancel" onClick={onCancel}>
            취소
          </Button>
          <Button
            data-testid="entry-copy-apply"
            variant="primary"
            disabled={state.loading || !!state.error || !selected.length}
            onClick={() =>
              onApply(
                state.data!.items.filter(
                  (r) =>
                    selected.includes(r.copy_entry_id!) && !r.already_copied,
                ),
              )
            }
          >
            선택 항목 불러오기
          </Button>
        </div>
      </section>
    </Modal>
  );
}
