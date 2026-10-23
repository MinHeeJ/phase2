import { useRef, useState } from "react";
import { Button, Modal, SuccessToast } from "../../shared/Controls";
import { createExcelMap, failure } from "../admin/client";
import { useCodes } from "../shared/useCodes";
import { ApiFailure } from "../../shared/api";
import {
  createUploadValidation,
  saveUploadRecheck,
  saveValidatedUploadApply,
  getUploadTemplate,
  getUploadErrorFile,
  uploadFileError,
  type UploadIssue,
  type UploadValidation,
} from "./client";
import styles from "./Uploads.module.css";

// SCR-UI-15, SCR-UI-15-M1, SCR-UI-15-M2, SCR-UI-15-M3
export function useUpload(form: string) {
  const [result, setResult] = useState<UploadValidation | null>(null);
  const [error, setError] = useState<ApiFailure | null>(null);
  const [progress, setProgress] = useState("");
  const guard = useRef(false);
  async function run<T>(
    label: string,
    action: () => Promise<T>,
    success: (value: T) => void,
  ) {
    if (guard.current) return;
    guard.current = true;
    setError(null);
    setProgress(label);
    try {
      success(await action());
    } catch (e) {
      setError(failure(e));
    } finally {
      setProgress("");
      guard.current = false;
    }
  }
  return {
    result,
    error,
    progress,
    setResult,
    setError,
    validate: (file: File) =>
      run("검사 중", () => createUploadValidation(form, file), setResult),
    recheck: () =>
      result?.upldId
        ? run("검사 중", () => saveUploadRecheck(result.upldId!), setResult)
        : Promise.resolve(),
    apply: (onApplied: (count: number) => void) =>
      result?.token && result.errorRows === 0
        ? run(
            "저장 중",
            () => saveValidatedUploadApply(result.token!),
            (value) => {
              setResult(null);
              onApplied(value.appliedRows);
            },
          )
        : Promise.resolve(),
    downloadTemplate: () =>
      run(
        "양식 준비 중",
        () => getUploadTemplate(form),
        () => undefined,
      ),
    downloadErrors: () =>
      result?.upldId
        ? run(
            "오류 파일 준비 중",
            () => getUploadErrorFile(result.upldId!),
            () => undefined,
          )
        : Promise.resolve(),
  };
}
type UploadState = ReturnType<typeof useUpload>;
export function UploadButton({
  form = "B.7",
  onApplied,
}: {
  form?: string;
  onApplied: (count: number) => void | Promise<void>;
}) {
  const upload = useUpload(form);
  const [open, setOpen] = useState(false),
    [file, setFile] = useState<File | null>(null),
    [checking, setChecking] = useState(false);
  const [message, setMessage] = useState("");
  const busy = !!upload.progress || checking;
  const choose = (value?: File) => {
    if (!value || busy) return;
    upload.setError(null);
    setFile(value);
    const error = uploadFileError(value);
    if (error) upload.setError(new ApiFailure(error, null, null, "file"));
    if (error) setMessage(error);
    else setMessage("");
  };
  const close = () => {
    if (!busy) {
      setOpen(false);
      upload.setResult(null);
      upload.setError(null);
      setFile(null);
      setMessage("");
    }
  };
  const [success, setSuccess] = useState("");
  return (
    <section
      data-testid="partner-upload-controls"
      className={styles.controls}
      aria-label="거래처 엑셀 업로드"
    >
      <Button
        data-testid="upload-template-button"
        onClick={() => void upload.downloadTemplate()}
        disabled={busy}
      >
        양식 내려받기
      </Button>
      <Button
        data-testid="upload-open-button"
        onClick={() => {
          setOpen(true);
          upload.setError(null);
          setMessage("");
        }}
        disabled={busy}
      >
        엑셀 업로드
      </Button>
      {!open && upload.error && <p role="alert">{upload.error.message}</p>}
      {success && (
        <SuccessToast message={success} onDismiss={() => setSuccess("")} />
      )}
      {open && !upload.result && (
        <Modal title="엑셀 업로드 · 거래처" onCancel={close}>
          <div data-testid="upload-file-dialog" className={styles.dialog}>
            <div
              className={styles.drop}
              onDragOver={(event) => event.preventDefault()}
              onDrop={(event) => {
                event.preventDefault();
                choose(event.dataTransfer.files[0]);
              }}
            >
              <p>파일을 끌어다 놓거나 선택해 주세요.</p>
              <label>
                파일 선택
                <input
                  data-testid="upload-file-input"
                  type="file"
                  accept=".xlsx"
                  disabled={busy}
                  onChange={(event) => choose(event.target.files?.[0])}
                />
              </label>
              <p>선택 파일: {file?.name ?? "선택한 파일 없음"}</p>
            </div>
            <Button
              data-testid="upload-dialog-template"
              onClick={() => void upload.downloadTemplate()}
              disabled={busy}
            >
              양식 내려받기
            </Button>
            <p>xlsx · 10MB 이하 · 데이터 10,000행 이하</p>
            <p>오류가 하나라도 있으면 파일 전체를 반영하지 않습니다.</p>
            {(message || upload.error) && (
              <p role="alert">{message || upload.error?.message}</p>
            )}
            {busy && (
              <p role="status">{checking ? "파일 확인 중" : upload.progress}</p>
            )}
            <div className={styles.actions}>
              <Button
                data-testid="upload-cancel-button"
                onClick={close}
                disabled={busy}
              >
                취소
              </Button>
              <Button
                data-testid="upload-validate-button"
                variant="primary"
                disabled={!file || !!message || busy}
                onClick={() => {
                  if (!file) return;
                  setChecking(true);
                  void Promise.resolve().then(() => {
                    setChecking(false);
                    return upload.validate(file);
                  });
                }}
              >
                올리기
              </Button>
            </div>
          </div>
        </Modal>
      )}
      {open && upload.result && (
        <UploadResultModal
          upload={upload}
          onCancel={close}
          onApplied={(count) => {
            setOpen(false);
            setFile(null);
            setSuccess(`거래처 ${count}건을 반영했습니다.`);
            void onApplied(count);
          }}
        />
      )}
    </section>
  );
}
export function UploadResultModal({
  upload,
  onCancel,
  onApplied,
}: {
  upload: UploadState;
  onCancel: () => void;
  onApplied: (count: number) => void;
}) {
  const result = upload.result!;
  const [tab, setTab] = useState<"ERROR" | "WARN">(
    result.errorRows ? "ERROR" : "WARN",
  );
  const [mapping, setMapping] = useState<UploadIssue | null>(null),
    [mapped, setMapped] = useState(false);
  const [previous, setPrevious] = useState(result);
  if (previous !== result) {
    setPrevious(result);
    setTab(result.errorRows ? "ERROR" : "WARN");
    setMapped(false);
  }
  const busy = !!upload.progress;
  const items = result.items
    .filter((item) => item.severity === tab)
    .sort((a, b) => a.rowNo - b.rowNo);
  return (
    <>
      {!mapping && (
        <Modal title="엑셀 업로드 결과 · 거래처" onCancel={onCancel}>
          <div data-testid="upload-result-dialog" className={styles.dialog}>
            <p>
              {result.fileName} · 전체 {result.totalRows}행
            </p>
            <div className={styles.summary}>
              <span>성공 {result.okRows}</span>
              <span>경고 {result.warningRows}</span>
              <span>오류 {result.errorRows}</span>
            </div>
            <p>
              오류가 하나라도 있으면 파일 전체를 반영하지 않습니다. 경고는
              반영할 수 있습니다.
            </p>
            <div
              className={styles.actions}
              role="tablist"
              aria-label="검사 항목"
            >
              <Button
                role="tab"
                data-testid="upload-error-tab"
                aria-selected={tab === "ERROR"}
                onClick={() => setTab("ERROR")}
              >
                오류 ({result.errorRows})
              </Button>
              <Button
                role="tab"
                data-testid="upload-warning-tab"
                aria-selected={tab === "WARN"}
                onClick={() => setTab("WARN")}
              >
                경고 ({result.warningRows})
              </Button>
              <Button
                data-testid="upload-recheck-button"
                variant={mapped ? "primary" : "secondary"}
                disabled={!result.upldId || busy}
                onClick={() => void upload.recheck()}
              >
                다시 검사
              </Button>
            </div>
            {!result.upldId && (
              <p>
                정상 검사에는 업로드 원장이 없습니다. 다시 검사하려면 원본
                파일을 다시 올려 주세요. 오류 파일은 오류가 있는 검사에서만
                내려받을 수 있습니다.
              </p>
            )}
            {mapped && (
              <p role="status">대응을 등록했습니다. 다시 검사해 주세요.</p>
            )}
            <div
              className={styles.table}
              role="tabpanel"
              data-testid="upload-issues-panel"
            >
              <table>
                <thead>
                  <tr>
                    <th>구분</th>
                    <th>행</th>
                    <th>열</th>
                    <th>입력값</th>
                    <th>이유</th>
                    <th>대응</th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((item, index) => (
                    <tr
                      data-testid={`upload-issue-${index}`}
                      key={`${item.rowNo}-${item.column}-${index}`}
                    >
                      <td>{item.severity === "ERROR" ? "오류" : "경고"}</td>
                      <td>{item.rowNo}</td>
                      <td>{item.column}</td>
                      <td>{item.rawValue}</td>
                      <td>{item.message}</td>
                      <td>
                        {item.fix && (
                          <Button
                            data-testid={`upload-mapping-${index}`}
                            disabled={busy}
                            onClick={() => setMapping(item)}
                          >
                            대응 등록
                          </Button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {!items.length && <p>검사 항목이 없습니다.</p>}
            </div>
            {upload.error && (
              <p role="alert">
                {upload.error.message}
                {upload.error.field && ` (${upload.error.field})`}
              </p>
            )}
            {busy && <p role="status">{upload.progress}</p>}
            <div className={styles.actions}>
              <Button
                data-testid="upload-error-file-button"
                disabled={!result.upldId || busy}
                onClick={() => void upload.downloadErrors()}
              >
                오류 목록 내려받기
              </Button>
              <Button
                data-testid="upload-result-close"
                onClick={onCancel}
                disabled={busy}
              >
                닫기
              </Button>
              <Button
                data-testid="upload-apply-button"
                variant="primary"
                disabled={result.errorRows !== 0 || !result.token || busy}
                onClick={() => void upload.apply(onApplied)}
              >
                반영
              </Button>
            </div>
          </div>
        </Modal>
      )}
      {mapping && (
        <UploadMappingDialog
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
export function UploadMappingDialog({
  issue,
  onCancel,
  onSaved,
}: {
  issue: UploadIssue;
  onCancel: () => void;
  onSaved: () => void;
}) {
  const group = issue.fix!.group;
  const codes = useCodes(group);
  const [code, setCode] = useState(""),
    [role, setRole] = useState<"" | "MAIN" | "SUB">("");
  const [error, setError] = useState<ApiFailure | null>(null),
    [pending, setPending] = useState(false);
  return (
    <Modal
      title="엑셀 표기 대응 등록"
      onCancel={() => {
        if (!pending) onCancel();
      }}
    >
      <form
        data-testid="upload-mapping-dialog"
        className={styles.dialog}
        onSubmit={(event) => {
          event.preventDefault();
          if (!code || !role || pending) return;
          setPending(true);
          setError(null);
          void createExcelMap({
            excel_lbl_nm: issue.rawValue ?? "",
            trns_cd_grp_id: group,
            trns_cd: code,
            map_role_cd: role,
            rmrk_desc: "",
          })
            .then(onSaved)
            .catch((e) => setError(failure(e)))
            .finally(() => setPending(false));
        }}
      >
        <label>
          엑셀 표기
          <input
            data-testid="upload-mapping-label"
            readOnly
            value={issue.rawValue ?? ""}
          />
        </label>
        <label>
          그룹
          <input data-testid="upload-mapping-group" readOnly value={group} />
        </label>
        <label>
          코드 *
          <select
            data-testid="upload-mapping-code"
            required
            value={code}
            disabled={pending || codes.loading}
            onChange={(event) => setCode(event.target.value)}
          >
            <option value="">선택</option>
            {codes.codes.map((item) => (
              <option key={item.code} value={item.code}>
                {item.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          주/보조 *
          <select
            data-testid="upload-mapping-role"
            required
            value={role}
            disabled={pending}
            onChange={(event) => setRole(event.target.value as typeof role)}
          >
            <option value="">선택</option>
            <option value="MAIN">주</option>
            <option value="SUB">보조</option>
          </select>
        </label>
        {codes.loading && <p role="status">코드 불러오는 중</p>}
        {codes.error && (
          <p role="alert">
            {codes.error.message}
            <Button
              data-testid="upload-codes-retry"
              type="button"
              onClick={codes.retry}
            >
              다시 조회
            </Button>
          </p>
        )}
        {!codes.loading && !codes.error && !codes.codes.length && (
          <p>사용 중인 코드가 없습니다.</p>
        )}
        {error && (
          <p role="alert">
            {error.message}
            {error.field && ` (${error.field})`}
          </p>
        )}
        <div className={styles.actions}>
          <Button
            data-testid="upload-mapping-cancel"
            type="button"
            disabled={pending}
            onClick={onCancel}
          >
            취소
          </Button>
          <Button
            data-testid="upload-mapping-save"
            variant="primary"
            type="submit"
            disabled={!code || !role || pending || !!codes.error}
            pending={pending}
          >
            저장
          </Button>
        </div>
      </form>
    </Modal>
  );
}
