import { useRef, useState } from "react";
import { ActionBar, Badge, Button, Modal, Tabs } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { Can } from "../../shared/Can";
import { ApiFailure } from "../../shared/api";
import { useCodes } from "../shared/useCodes";
import { createExcelMap } from "../admin/client";
import {
  createUploadValidation,
  saveUploadRecheck,
  saveValidatedUploadApply,
  downloadUploadFile,
  uploadTemplatePath,
  uploadErrorPath,
  uploadFailure,
  type UploadIssue,
  type UploadValidationResult,
  type UploadApplyResult,
} from "./client";
import styles from "./Uploads.module.css";

function MappingDialog({
  issue,
  onCancel,
  onSaved,
}: {
  issue: UploadIssue;
  onCancel: () => void;
  onSaved: () => void;
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
        data-screen-id="SCR-UI-15-M3"
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
  const busy = useRef(false);
  const pending = !!phase;
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
        show(await createUploadValidation(form, file));
      } else if (kind === "recheck" && result?.upldId)
        show(await saveUploadRecheck(result.upldId));
      else if (kind === "apply" && result?.token && result.errorRows === 0) {
        const applied = await saveValidatedUploadApply(result.token);
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
        setResult((r) => (r ? { ...r, token: undefined } : r));
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
  if (!result)
    return (
      <Modal title="엑셀 업로드 · 거래처" onCancel={close}>
        <section data-testid="upload-selection" data-screen-id="SCR-UI-15-M1">
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
          <Can perm="UI-15.download">
            <Button
              data-testid="upload-template"
              variant="text"
              disabled={pending}
              onClick={() =>
                void download(
                  uploadTemplatePath(form),
                  "거래처_업로드양식.xlsx",
                )
              }
            >
              양식 내려받기
            </Button>
          </Can>
          <p className={styles.info}>
            오류가 하나라도 있으면 파일 전체를 반영하지 않습니다.
          </p>
          <ErrorBanner
            error={error}
            pending={pending}
            onRetry={() => {
              if (file) void run("validate");
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
                disabled={!file}
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
  return (
    <>
      <Modal title="엑셀 업로드 결과 · 거래처" onCancel={close}>
        <section data-testid="upload-result" data-screen-id="SCR-UI-15-M2">
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
            {!result.token && result.errorRows === 0 && (
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
            onChange={setTab}
            items={[
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
                {items.length === 0 && (
                  <tr>
                    <td colSpan={5} className={styles.empty}>
                      {tab === "ERROR"
                        ? "오류가 없습니다."
                        : "경고가 없습니다."}
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
          <footer className={styles.footer}>
            <Can perm="UI-15.download">
              <Button
                data-testid="upload-error-download"
                disabled={!result.upldId || pending}
                onClick={() => {
                  if (result.upldId)
                    void download(
                      uploadErrorPath(result.upldId),
                      result.fileName.replace(/\.xlsx$/i, "") + "_오류.xlsx",
                    );
                }}
              >
                오류 목록 내려받기
              </Button>
            </Can>
            <span />
            <Button
              data-testid="upload-close"
              disabled={pending}
              onClick={close}
            >
              닫기
            </Button>
            <Can perm="UI-15.upload">
              <Button
                data-testid="upload-apply"
                variant="primary"
                pending={pending}
                disabled={result.errorRows > 0 || !result.token}
                onClick={() => void run("apply")}
              >
                반영
              </Button>
            </Can>
          </footer>
        </section>
      </Modal>
      {mapping && (
        <MappingDialog
          issue={mapping}
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
