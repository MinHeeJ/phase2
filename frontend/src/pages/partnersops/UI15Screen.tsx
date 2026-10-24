import { useCallback, useEffect, useState, type ReactNode } from "react";
import { readData } from "../../shared/api";
import {
  Button,
  Badge,
  Tabs,
  DataTable,
  Pagination,
  Skeleton,
} from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ScreenHeading } from "../shared/ScreenHeading";
import { useCodes } from "../shared/useCodes";
import { useAdminRead } from "../admin/useAdminResource";
import {
  PartnerDisableDialog,
  PartnerEnableButton,
  ListExportButton,
} from "./PartnerActions";
import { PartnerAssignments, PartnerHistory } from "./PartnerReadPanels";
import {
  getPartnerDetail,
  partnerColumns,
  partnerQuery,
  type PartnerPage,
  type PartnerRow,
  type PartnerFilter,
} from "./client";
import styles from "./PartnerLifecycle.module.css";

// SCR-UI-15: stateful /UI-15; dialogs do not create routes.
export default function UI15Screen({
  name = "거래처·영업 파트",
  groupName,
  onEdit,
  actions,
  salesPartPanel,
}: {
  name?: string;
  groupName?: string;
  onEdit?: (id: string) => void;
  actions?: ReactNode;
  salesPartPanel?: ReactNode;
}) {
  const [tab, setTab] = useState("partners"),
    [filter, setFilter] = useState<PartnerFilter>({ useYn: "Y" });
  const [draft, setDraft] = useState<PartnerFilter>({ useYn: "Y" });
  const [page, setPage] = useState(1),
    [size, setSize] = useState<20 | 50 | 100>(50);
  const [selected, setSelected] = useState<PartnerRow[]>([]),
    [detailId, setDetailId] = useState("");
  const [detailTab, setDetailTab] = useState("basic"),
    [disable, setDisable] = useState<PartnerRow[] | null>(null),
    [message, setMessage] = useState("");
  const [more, setMore] = useState(false);
  const kinds = useCodes("PARTNER_KIND");
  const list = useAdminRead(
    useCallback(
      () =>
        readData<PartnerPage>(
          `/api/partners?${partnerQuery(filter)}&page=${page}&size=${size}`,
        ),
      [filter, page, size],
    ),
  );
  const detail = useAdminRead(
    useCallback(
      () => (detailId ? getPartnerDetail(detailId) : Promise.resolve(null)),
      [detailId],
    ),
  );
  const refresh = async () => {
    await list.reload();
    if (detailId) await detail.reload();
    setSelected([]);
  };
  useEffect(() => {
    const close = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !disable) {
        setDetailId("");
        setMore(false);
      }
    };
    document.addEventListener("keydown", close);
    return () => document.removeEventListener("keydown", close);
  }, [disable]);
  const open = (row: PartnerRow, panel = "basic") => {
    setDetailId(row.ptnr_id);
    setDetailTab(panel);
    setMore(false);
  };
  const detailRow = list.data?.items.find((row) => row.ptnr_id === detailId);
  return (
    <section data-testid="partner-lifecycle-screen" data-screen-id="SCR-UI-15">
      <ScreenHeading
        screenId="UI-15"
        name={name}
        groupName={groupName}
        actions={<span className={styles.mutate}>{actions}</span>}
      />
      <Tabs
        selected={tab}
        onChange={setTab}
        items={[
          { key: "partners", label: "거래처" },
          { key: "sales", label: "영업 파트" },
        ]}
      />
      {message && <p role="status">{message}</p>}
      {tab === "sales" ? (
        salesPartPanel
      ) : (
        <>
          <form
            data-testid="partner-lifecycle-search-form"
            className={styles.filters}
            onSubmit={(event) => {
              event.preventDefault();
              setPage(1);
              setSelected([]);
              setFilter({ ...draft });
            }}
          >
            <label>
              구분
              <select
                data-testid="partner-kind-filter"
                value={draft.kind ?? ""}
                onChange={(event) =>
                  setDraft({ ...draft, kind: event.target.value })
                }
              >
                <option value="">전체</option>
                {kinds.codes.map((code) => (
                  <option
                    data-testid={`partner-lifecycle-kind-option-${code.code.toLowerCase().replace(/[^a-z0-9]+/g, "-")}`}
                    key={code.code}
                    value={code.code}
                  >
                    {code.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              사용 여부
              <select
                data-testid="partner-use-filter"
                value={draft.useYn ?? ""}
                onChange={(event) =>
                  setDraft({ ...draft, useYn: event.target.value })
                }
              >
                <option value="">전체</option>
                <option value="Y">사용</option>
                <option value="N">중지</option>
              </select>
            </label>
            <label>
              상호
              <input
                data-testid="partner-search"
                placeholder="상호 또는 사업자번호"
                value={draft.q ?? ""}
                onChange={(event) =>
                  setDraft({ ...draft, q: event.target.value })
                }
              />
            </label>
            <Button
              data-testid="partner-reset"
              type="button"
              onClick={() => {
                setDraft({ useYn: "Y" });
                setFilter({ useYn: "Y" });
                setSelected([]);
                setPage(1);
              }}
            >
              초기화
            </Button>
            <Button
              data-testid="partner-search-submit"
              type="submit"
              variant="primary"
            >
              조회
            </Button>
          </form>
          <ErrorBanner error={kinds.error} onRetry={kinds.retry} />
          <div className={styles.toolbar}>
            <span>총 {list.data?.total ?? 0}건</span>
            <span className={styles.mutate}>
              선택 {selected.length}건{" "}
              <Button
                data-testid="partner-bulk-disable"
                disabled={!selected.length}
                onClick={() => setDisable(selected)}
              >
                사용 중지
              </Button>
            </span>
            <ListExportButton
              filter={filter}
              selectedIds={selected.map((row) => row.ptnr_id)}
              columns={partnerColumns.map(([key]) => key)}
              total={list.data?.total ?? 0}
            />
          </div>
          <ErrorBanner
            error={list.error}
            onRetry={() => {
              void list.reload().catch(() => {});
            }}
          />
          {list.loading ? (
            <Skeleton />
          ) : (
            !list.error && (
              <DataTable
                rows={list.data?.items ?? []}
                total={list.data?.total ?? 0}
                rowKey={(row) => row.ptnr_id}
                columns={[
                  {
                    key: "selection",
                    label: "선택",
                    render: (row) => (
                      <input
                        className={styles.mutate}
                        data-testid={`partner-select-${row.ptnr_id}`}
                        aria-label={`${row.ptnr_nm} 선택`}
                        type="checkbox"
                        checked={selected.some(
                          (item) => item.ptnr_id === row.ptnr_id,
                        )}
                        onChange={(event) =>
                          setSelected(
                            event.target.checked
                              ? [...selected, row]
                              : selected.filter(
                                  (item) => item.ptnr_id !== row.ptnr_id,
                                ),
                          )
                        }
                      />
                    ),
                  },
                  ...partnerColumns.map(([key, label]) => ({
                    key,
                    label,
                    render: (row: PartnerRow) =>
                      key === "ptnr_nm" ? (
                        <Button
                          variant="text"
                          data-testid={`partner-open-${row.ptnr_id}`}
                          onClick={() => open(row)}
                        >
                          {row.ptnr_nm}
                        </Button>
                      ) : key === "use_yn" ? (
                        <Badge
                          tone={row.use_yn === "Y" ? "success" : "neutral"}
                        >
                          {row.use_yn === "Y" ? "사용" : "중지"}
                        </Badge>
                      ) : key === "kindName" ? (
                        <Badge
                          tone={row.ptnr_se_cd === "CUST" ? "info" : "neutral"}
                        >
                          {row.kindName}
                        </Badge>
                      ) : key === "assignmentPeopleCount" &&
                        row.assignmentPeopleCount > 0 ? (
                        <Button
                          variant="text"
                          data-testid={`partner-assignments-${row.ptnr_id}`}
                          onClick={() => open(row, "assignments")}
                        >
                          {row.assignmentPeopleCount}
                        </Button>
                      ) : (
                        String(row[key] ?? "–")
                      ),
                  })),
                ]}
              />
            )
          )}
          <Pagination
            page={page}
            size={size}
            total={list.data?.total ?? 0}
            onPage={(next) => {
              setPage(next);
            }}
            onSize={(next) => {
              setSize(next);
              setPage(1);
            }}
          />
        </>
      )}
      {detailId && (
        <div
          data-testid="partner-detail-backdrop"
          className={styles.scrim}
          onClick={() => setDetailId("")}
        >
          <aside
            className={styles.drawer}
            data-testid="partner-detail-drawer"
            role="dialog"
            aria-label="거래처 상세"
            onClick={(event) => event.stopPropagation()}
          >
            <header>
              <h2>{detail.data?.ptnr_nm}</h2>
              <Button
                data-testid="partner-detail-close"
                onClick={() => setDetailId("")}
              >
                닫기
              </Button>
            </header>
            <p>
              {detailRow?.kindName} · {detail.data?.biz_reg_no}
            </p>
            <div className={styles.mutate}>
              {onEdit && (
                <Button
                  data-testid="partner-edit"
                  onClick={() => onEdit(detailId)}
                >
                  수정
                </Button>
              )}
              <Button
                data-testid="partner-more"
                aria-label="더보기"
                onClick={() => setMore(!more)}
              >
                ⋯
              </Button>
              {more && (
                <div>
                  <Button
                    data-testid="partner-history-menu"
                    onClick={() => {
                      setDetailTab("history");
                      setMore(false);
                    }}
                  >
                    변경 이력 보기
                  </Button>
                  {detail.data?.use_yn === "N" ? (
                    <PartnerEnableButton
                      id={detailId}
                      reload={refresh}
                      onComplete={setMessage}
                    />
                  ) : (
                    detailRow && (
                      <Button
                        data-testid="partner-detail-disable"
                        onClick={() => setDisable([detailRow])}
                      >
                        사용 중지
                      </Button>
                    )
                  )}
                </div>
              )}
            </div>
            {detail.data?.use_yn === "N" && (
              <p className={styles.warning}>
                사용을 중지한 거래처입니다. 새 프로젝트·지급에 고를 수 없습니다.
              </p>
            )}
            <Tabs
              selected={detailTab}
              onChange={setDetailTab}
              items={[
                { key: "basic", label: "기본 정보" },
                { key: "assignments", label: "투입 내역" },
                { key: "history", label: "변경 이력" },
              ]}
            />
            <ErrorBanner
              error={detail.error}
              onRetry={() => {
                void detail.reload().catch(() => {});
              }}
            />
            {detail.loading ? (
              <Skeleton />
            ) : detailTab === "assignments" ? (
              <PartnerAssignments id={detailId} />
            ) : detailTab === "history" ? (
              <PartnerHistory id={detailId} />
            ) : (
              detail.data && (
                <dl data-testid="partner-basic-info">
                  {Object.entries(detail.data).map(([key, value]) => (
                    <div
                      key={key}
                      data-testid={`partner-basic-field-${key.replaceAll("_", "-")}`}
                    >
                      <dt>
                        {
                          (
                            {
                              ptnr_id: "거래처 ID",
                              ptnr_no: "거래처 코드",
                              ptnr_nm: "상호",
                              biz_reg_no: "사업자번호",
                              ceo_nm: "대표자",
                              indty_cd: "업종",
                              chrg_nm: "담당자",
                              chrg_tel_no: "연락처",
                              txinv_email_addr: "세금계산서 이메일",
                              bank_cd: "은행",
                              acnt_no: "계좌번호",
                              dpstr_nm: "예금주",
                              rmrk_desc: "비고",
                              use_yn: "사용 여부",
                              ptnr_se_cd: "구분",
                            } as Record<string, string>
                          )[key]
                        }
                      </dt>
                      <dd>{String(value ?? "–")}</dd>
                    </div>
                  ))}
                </dl>
              )
            )}
          </aside>
        </div>
      )}
      {disable && (
        <PartnerDisableDialog
          rows={disable}
          onClose={() => setDisable(null)}
          reload={refresh}
          onComplete={setMessage}
        />
      )}
    </section>
  );
}
