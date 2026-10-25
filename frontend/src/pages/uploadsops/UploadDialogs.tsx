import { useEffect, useRef, useState } from "react";
import { ActionBar, Badge, Button, Modal, Tabs } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { Can } from "../../shared/Can";
import { ApiFailure } from "../../shared/api";
import { useCodes } from "../shared/useCodes";
import { getMonthClose, monthStatus, type MonthClose } from "../shared/api";
import { createExcelMap } from "../admin/client";
import { UploadResultPanel } from "../apiops/UploadResultPanel";
import {
  createUploadValidation,
  saveUploadRecheck,
  saveValidatedUploadApply,
  downloadUploadFile,
  uploadTemplatePath,
  uploadErrorPath,
  uploadFailure,
  revalidatePersonnelUpload,
  applyPersonnelUpload,
  listPersonnelUploadRows,
  type UploadIssue,
  type UploadValidationResult,
  type UploadApplyResult,
  type PersonnelPage,
  type PersonnelPreview,
} from "./client";
import styles from "./Uploads.module.css";

function MappingDialog({
  issue,
  onCancel,
  onSaved,
  screenId,
}: {
  issue: UploadIssue;
  onCancel: () => void;
  onSaved: () => void;
  screenId: string;
}) {
  const codes = useCodes(issue.fix!.group);
  const [code, setCode] = useState(""),
    [role, setRole] = useState(""),
    [pending, setPending] = useState(false),
    [error, setError] = useState<ApiFailure | null>(null);
  const busy = useRef(false);
  const save = async () => {
    if (busy.current) return;
    if (!code || !role) {
      setError(
        new ApiFailure(
          !code ? "대응할 코드를 고르세요." : "주/보조를 고르세요.",
          400,
          null,
          !code ? "trns_cd" : "map_role_cd",
        ),
      );
      return;
    }
    busy.current = true;
    setPending(true);
    setError(null);
    try {
      await createExcelMap({
        excel_lbl_nm: issue.rawValue ?? "",
        trns_cd_grp_id: issue.fix!.group,
        trns_cd: code,
        map_role_cd: role as "MAIN" | "SUB",
        rmrk_desc: "",
      });
      onSaved();
    } catch (e) {
      setError(uploadFailure(e));
    } finally {
      busy.current = false;
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
        data-screen-id={screenId}
        data-testid="upload-mapping-dialog"
        className={styles.mapping}
      >
        <label>
          엑셀 표기
          <input
            data-testid="upload-map-label"
            readOnly
            value={issue.rawValue ?? ""}
          />
        </label>
        <label>
          그룹
          <input
            data-testid="upload-map-group"
            readOnly
            value={issue.fix!.group}
          />
        </label>
        <ErrorBanner error={codes.error} onRetry={codes.retry} />
        <ErrorBanner error={error} onRetry={() => setError(null)} />
        <label>
          코드
          <select
            data-testid="upload-map-code"
            aria-label="코드"
            value={code}
            disabled={pending || codes.loading}
            onChange={(e) => setCode(e.target.value)}
          >
            <option value="">선택해 주세요</option>
            {codes.codes.map((c) => (
              <option key={c.code} value={c.code}>
                {c.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          주/보조
          <select
            data-testid="upload-map-role"
            aria-label="주/보조"
            value={role}
            disabled={pending}
            onChange={(e) => setRole(e.target.value)}
          >
            <option value="">선택해 주세요</option>
            <option value="MAIN">주</option>
            <option value="SUB">보조</option>
          </select>
        </label>
        <ActionBar
          primary={
            <Button
              data-testid="upload-map-save"
              variant="primary"
              pending={pending}
              disabled={!!codes.error || codes.loading}
              onClick={() => void save()}
            >
              저장
            </Button>
          }
        >
          <Button
            data-testid="upload-map-cancel"
            disabled={pending}
            onClick={onCancel}
          >
            취소
          </Button>
        </ActionBar>
      </section>
    </Modal>
  );
}

export function UploadDialogs({
  form = "B.7",
  onClose,
  onApplied,
}: {
  form?: string;
  onClose: () => void;
  onApplied: (result: UploadApplyResult) => void | Promise<unknown>;
}) {
  const [file, setFile] = useState<File | null>(null),
    [result, setResult] = useState<UploadValidationResult | null>(null),
    [error, setError] = useState<ApiFailure | null>(null),
    [phase, setPhase] = useState(""),
    [tab, setTab] = useState("ERROR"),
    [mapping, setMapping] = useState<UploadIssue | null>(null),
    [mapped, setMapped] = useState(false);
  const busy = useRef(false),
    pending = !!phase;
  const personnel = form === "B.8" || form === "B.1",
    salary = form === "B.1";
  const subject = salary ? "급여" : personnel ? "개발자" : "거래처",
    permission = personnel ? "UI-02" : "UI-15";
  const [month, setMonth] = useState(""),
    [months, setMonths] = useState<MonthClose[]>([]),
    [monthReady, setMonthReady] = useState(!salary);
  const [preview, setPreview] =
      useState<PersonnelPage<PersonnelPreview> | null>(null),
    [previewPage, setPreviewPage] = useState(0);
  const [mobile, setMobile] = useState(
    () =>
      typeof window.matchMedia === "function" &&
      window.matchMedia("(max-width: 767px)").matches,
  );
  useEffect(() => {
    if (!personnel || typeof window.matchMedia !== "function") return;
    const media = window.matchMedia("(max-width: 767px)"),
      changed = () => setMobile(media.matches);
    media.addEventListener("change", changed);
    return () => media.removeEventListener("change", changed);
  }, [personnel]);
  const loadMonths = () => {
    setMonthReady(false);
    return getMonthClose("0001-01", "9999-12")
      .then((rows) => {
        setMonths(rows);
        setMonth(
          (m) =>
            m ||
            rows
              .filter((r) => r.status === "OPEN")
              .map((r) => r.month)
              .sort()[0] ||
            "",
        );
        setMonthReady(true);
      })
      .catch((e) => setError(uploadFailure(e)));
  };
  useEffect(() => {
    if (salary) void loadMonths();
  }, [salary]);
  useEffect(() => {
    if (!result?.reviewId) return;
    let active = true;
    setPreview(null);
    listPersonnelUploadRows(
      result.reviewId,
      previewPage,
      tab === "WARN" ? "WARNING" : tab,
    )
      .then((rows) => {
        if (active) setPreview(rows);
      })
      .catch((e) => {
        if (active) setError(uploadFailure(e));
      });
    return () => {
      active = false;
    };
  }, [result, previewPage, tab]);
  const closedMonth =
    salary && !!month && monthStatus(months, month) === "CLOSED";
  const choose = (chosen?: File) => {
    if (!chosen || pending) return;
    setError(null);
    setFile(null);
    if (!/\.xlsx$/i.test(chosen.name)) {
      setError(
        new ApiFailure("xlsx 파일만 올릴 수 있습니다.", 400, null, "file"),
      );
      return;
    }
    if (chosen.size > 10 * 1024 * 1024) {
      setError(
        new ApiFailure(
          "파일은 10MB 이하만 올릴 수 있습니다.",
          413,
          null,
          "file",
        ),
      );
      return;
    }
    setFile(chosen);
  };
  const show = (next: UploadValidationResult) => {
    setResult(next);
    setTab(next.errorRows ? "ERROR" : "WARN");
    setMapped(false);
    setPreviewPage(0);
  };
  const run = async (kind: "validate" | "recheck" | "apply") => {
    if (busy.current) return;
    busy.current = true;
    setError(null);
    setPhase(
      kind === "apply"
        ? "저장 중"
        : kind === "validate"
          ? "파일 확인 중"
          : "검사 중",
    );
    try {
      if (kind === "validate" && file) {
        await Promise.resolve();
        setPhase("검사 중");
        show(
          await createUploadValidation(form, file, salary ? month : undefined),
        );
      } else if (kind === "recheck" && result?.reviewId)
        show(await revalidatePersonnelUpload(result.reviewId, result.fileName));
      else if (kind === "recheck" && result?.upldId)
        show(await saveUploadRecheck(result.upldId));
      else if (
        kind === "apply" &&
        result &&
        result.errorRows === 0 &&
        (result.reviewId ? result.canApply : result.token)
      ) {
        const applied = result.reviewId
          ? await applyPersonnelUpload(result.reviewId)
          : await saveValidatedUploadApply(result.token!);
        await onApplied(applied);
        onClose();
      }
    } catch (e) {
      const failure = uploadFailure(e);
      setError(failure);
      if (
        failure.code === "VALIDATION_EXPIRED" ||
        failure.code === "STALE_VALIDATION"
      )
        setResult((r) => (r ? { ...r, token: undefined, canApply: false } : r));
    } finally {
      busy.current = false;
      setPhase("");
    }
  };
  const download = async (path: string, name: string) => {
    if (busy.current) return;
    busy.current = true;
    setPhase("파일 준비 중");
    setError(null);
    try {
      await downloadUploadFile(path, name);
    } catch (e) {
      setError(uploadFailure(e));
    } finally {
      busy.current = false;
      setPhase("");
    }
  };
  const close = () => {
    if (!pending) onClose();
  };
  if (personnel && mobile) return null;
  if (!result)
    return (
      <Modal
        title={salary ? "급여 업로드" : `엑셀 업로드 · ${subject}`}
        onCancel={close}
      >
        <section
          data-testid="upload-selection"
          data-screen-id={
            salary
              ? "SCR-UI-02-M5"
              : personnel
                ? "SCR-UI-02-M4"
                : "SCR-UI-15-M1"
          }
        >
          {salary && (
            <label>
              연월 *
              <input
                data-testid="salary-month"
                aria-label="연월"
                type="month"
                value={month}
                disabled={pending || !monthReady}
                onChange={(e) => setMonth(e.target.value)}
              />
            </label>
          )}
          {closedMonth && (
            <p role="alert" className={styles.dangerBanner}>
              {month}은 마감된 달이라 올릴 수 없습니다.
            </p>
          )}
          <div
            className={styles.dropzone}
            data-testid="upload-dropzone"
            onDragOver={(e) => e.preventDefault()}
            onDrop={(e) => {
              e.preventDefault();
              choose(e.dataTransfer.files[0]);
            }}
          >
            <p>파일을 끌어다 놓으세요</p>
            <label className={styles.fileLabel}>
              파일 선택
              <input
                data-testid="upload-file"
                aria-label="파일 선택"
                type="file"
                accept=".xlsx"
                disabled={pending}
                onChange={(e) => choose(e.target.files?.[0])}
              />
            </label>
            <p>xlsx · 10MB 이하 · 데이터 10,000행 이하</p>
            {file && <p>{file.name}</p>}
          </div>
          <Can perm={`${permission}.download`}>
            <Button
              data-testid="upload-template"
              variant="text"
              disabled={pending}
              onClick={() =>
                void download(
                  uploadTemplatePath(form),
                  `${subject}_업로드양식.xlsx`,
                )
              }
            >
              양식 내려받기
            </Button>
          </Can>
          <p className={styles.info}>
            {salary
              ? "같은 연월을 다시 올리면 그 달 급여를 모두 새 파일로 바꿉니다. 마감한 달은 올릴 수 없습니다."
              : "오류가 하나라도 있으면 파일 전체를 반영하지 않습니다."}
          </p>
          <ErrorBanner
            error={error}
            pending={pending}
            onRetry={() => {
              if (salary && !monthReady) void loadMonths();
              else if (file) void run("validate");
              else setError(null);
            }}
          />
          {phase && <p role="status">{phase}</p>}
          <ActionBar
            primary={
              <Button
                data-testid="upload-submit"
                variant="primary"
                pending={pending}
                disabled={
                  !file || (salary && (!month || !monthReady || closedMonth))
                }
                onClick={() => void run("validate")}
              >
                올리기
              </Button>
            }
          >
            <Button
              data-testid="upload-cancel"
              disabled={pending}
              onClick={close}
            >
              취소
            </Button>
          </ActionBar>
        </section>
      </Modal>
    );
  const items = result.items
    .filter((i) => i.severity === tab)
    .sort((a, b) => a.rowNo - b.rowNo);
  const content = (canApply: boolean) => (
    <section
      data-testid="upload-result"
      data-screen-id={
        salary ? "SCR-UI-02-M7" : personnel ? "SCR-UI-02-M6" : "SCR-UI-15-M2"
      }
    >
      {!personnel && (
        <>
          {result.overwriteNotice && (
            <p className={styles.warning}>{result.overwriteNotice}</p>
          )}
          <p>
            {result.fileName} · 총 {result.totalRows}행
          </p>
          <div className={styles.summary}>
            <div className={styles.success}>
              성공 <strong>{result.okRows}</strong>
            </div>
            <div className={styles.warning}>
              경고 <strong>{result.warningRows}</strong>
            </div>
            <div className={styles.danger}>
              오류 <strong>{result.errorRows}</strong>
            </div>
          </div>
          <p className={result.errorRows ? styles.dangerBanner : styles.info}>
            {result.errorRows
              ? "오류가 하나라도 있으면 파일 전체를 반영하지 않습니다."
              : "검사가 끝났습니다. 경고는 반영을 막지 않습니다."}
          </p>
        </>
      )}
      <ErrorBanner
        error={error}
        pending={pending}
        onRetry={() => {
          if (result.upldId) void run("recheck");
          else setError(null);
        }}
      />
      {phase && <p role="status">{phase}</p>}
      <div className={styles.tools}>
        {result.upldId && (
          <Button
            data-testid="upload-recheck"
            variant={mapped ? "primary" : "secondary"}
            pending={pending}
            onClick={() => void run("recheck")}
          >
            다시 검사
          </Button>
        )}
        {!result.token && !result.canApply && result.errorRows === 0 && (
          <Button
            data-testid="upload-again"
            disabled={pending}
            onClick={() => {
              setResult(null);
              setFile(null);
              setError(null);
            }}
          >
            다시 올리기
          </Button>
        )}
      </div>
      <Tabs
        selected={tab}
        onChange={(value) => {
          setTab(value);
          setPreviewPage(0);
        }}
        items={[
          ...(personnel ? [{ key: "NORMAL", label: "정상" }] : []),
          {
            key: "ERROR",
            label: `오류 (${result.items.filter((i) => i.severity === "ERROR").length})`,
          },
          {
            key: "WARN",
            label: `경고 (${result.items.filter((i) => i.severity === "WARN").length})`,
          },
        ]}
      />
      {personnel && (
        <div
          data-testid="upload-normalized-rows"
          role="tabpanel"
          className={styles.tableScroll}
        >
          {!preview ? (
            <p role="status">조회 중</p>
          ) : (
            <>
              <table>
                <thead>
                  <tr>
                    <th>행</th>
                    <th>관리번호</th>
                    <th>이름</th>
                    <th>직원구분</th>
                  </tr>
                </thead>
                <tbody>
                  {preview.items.map((row) => {
                    const data = row.developer ?? row.salary;
                    return (
                      <tr
                        key={row.row}
                        data-testid={`upload-preview-${row.row}`}
                      >
                        <td>{row.row}</td>
                        <td>{String(data?.mng_no ?? "–")}</td>
                        <td>{String(data?.dev_nm ?? "–")}</td>
                        <td>{String(data?.emp_se_cd ?? "–")}</td>
                      </tr>
                    );
                  })}
                  {!preview.items.length && (
                    <tr>
                      <td className={styles.empty} colSpan={4}>
                        해당 행이 없습니다.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
              <Button
                data-testid="upload-preview-prev"
                disabled={previewPage === 0}
                onClick={() => setPreviewPage((p) => p - 1)}
              >
                이전
              </Button>
              <span>{preview.total}행</span>
              <Button
                data-testid="upload-preview-next"
                disabled={(previewPage + 1) * 50 >= preview.total}
                onClick={() => setPreviewPage((p) => p + 1)}
              >
                다음
              </Button>
            </>
          )}
        </div>
      )}
      <div
        className={styles.tableScroll}
        role="tabpanel"
        data-testid="upload-issues"
      >
        <table>
          <thead>
            <tr>
              {["구분", "행", "열", "입력값", "이유"].map((c) => (
                <th key={c}>{c}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {items.map((item, index) => (
              <tr
                key={`${item.rowNo}-${item.column}-${index}`}
                data-testid={`upload-issue-${index}`}
              >
                <td>
                  <Badge
                    tone={item.severity === "ERROR" ? "danger" : "warning"}
                  >
                    {item.severity === "ERROR" ? "오류" : "경고"}
                  </Badge>
                </td>
                <td>{item.rowNo}</td>
                <td>{item.column}</td>
                <td>{item.rawValue ?? "–"}</td>
                <td>
                  {item.message}
                  {item.fix && (
                    <Button
                      data-testid={`upload-mapping-${index}`}
                      small
                      disabled={pending}
                      onClick={() => setMapping(item)}
                    >
                      대응 등록
                    </Button>
                  )}
                </td>
              </tr>
            ))}
            {!items.length && (
              <tr>
                <td colSpan={5} className={styles.empty}>
                  {tab === "NORMAL"
                    ? "정규화된 행을 확인해 주세요."
                    : tab === "ERROR"
                      ? "오류가 없습니다."
                      : "경고가 없습니다."}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      <footer className={styles.footer}>
        <Can perm={`${permission}.download`}>
          <Button
            data-testid="upload-error-download"
            disabled={!result.upldId || pending}
            onClick={() => {
              if (result.upldId)
                void download(
                  result.reviewId
                    ? `/api/uploads/${result.reviewId}/errors`
                    : uploadErrorPath(result.upldId),
                  result.fileName.replace(/\.xlsx$/i, "") + "_오류.xlsx",
                );
            }}
          >
            오류 목록 내려받기
          </Button>
        </Can>
        <span />
        <Button data-testid="upload-close" disabled={pending} onClick={close}>
          닫기
        </Button>
        <Can perm={`${permission}.upload`}>
          <Button
            data-testid="upload-apply"
            variant="primary"
            pending={pending}
            disabled={
              !canApply ||
              result.errorRows > 0 ||
              (result.reviewId ? !result.canApply : !result.token)
            }
            onClick={() => void run("apply")}
          >
            반영
          </Button>
        </Can>
      </footer>
    </section>
  );
  return (
    <>
      <Modal
        title={`엑셀 업로드 결과 · ${subject}${salary ? ` ${result.month}` : ""}`}
        onCancel={close}
      >
        {personnel && result.reviewId ? (
          <UploadResultPanel key={result.reviewId} uploadId={result.reviewId}>
            {(review) => content(review.can_apply && review.error_count === 0)}
          </UploadResultPanel>
        ) : (
          content(true)
        )}
      </Modal>
      {mapping && (
        <MappingDialog
          issue={mapping}
          screenId={personnel ? "SCR-UI-02-M7" : "SCR-UI-15-M3"}
          onCancel={() => setMapping(null)}
          onSaved={() => {
            setMapping(null);
            setMapped(true);
          }}
        />
      )}
    </>
  );
}
