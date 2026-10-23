import { useState } from "react";
import { Button, Modal } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import type { ApiFailure } from "../../shared/api";
import { createExcelMap, failure } from "../admin/client";
import { useCodes } from "../shared/useCodes";
import styles from "./Partners.module.css";

// SCR-UI-15-M3. Upload result owners pass the selected issue's rawValue and fix.group.
export function PartnerMappingDialog({
  label,
  group,
  onCancel,
  onSaved,
}: {
  label: string;
  group: "PARTNER_KIND" | "INDUSTRY" | "BANK";
  onCancel: () => void;
  onSaved: () => void;
}) {
  const codes = useCodes(group),
    [code, setCode] = useState(""),
    [role, setRole] = useState<"" | "MAIN" | "SUB">(""),
    [pending, setPending] = useState(false),
    [error, setError] = useState<ApiFailure | null>(null),
    [saved, setSaved] = useState(false);
  async function save() {
    if (!code || !role) return;
    setPending(true);
    setError(null);
    try {
      await createExcelMap({
        excel_lbl_nm: label,
        trns_cd_grp_id: group,
        trns_cd: code,
        map_role_cd: role,
        rmrk_desc: "",
      });
      setSaved(true);
      onSaved();
    } catch (e) {
      setError(failure(e));
    } finally {
      setPending(false);
    }
  }
  return (
    <Modal
      title="엑셀 표기 대응 등록"
      onCancel={() => {
        if (!pending) onCancel();
      }}
    >
      <section
        data-testid="partner-mapping-dialog"
        data-screen-id="SCR-UI-15-M3"
        className={styles.region}
      >
        <label>
          엑셀 표기
          <input data-testid="partner-mapping-label" readOnly value={label} />
        </label>
        <label>
          그룹
          <input data-testid="partner-mapping-group" readOnly value={group} />
        </label>
        <ErrorBanner error={codes.error} onRetry={codes.retry} />
        <label>
          코드
          <select
            data-testid="partner-mapping-code"
            value={code}
            disabled={codes.loading || pending || saved}
            onChange={(e) => setCode(e.target.value)}
          >
            <option value="">선택</option>
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
            data-testid="partner-mapping-role"
            value={role}
            disabled={pending || saved}
            onChange={(e) => setRole(e.target.value as typeof role)}
          >
            <option value="">선택</option>
            <option value="MAIN">주</option>
            <option value="SUB">보조</option>
          </select>
        </label>
        <ErrorBanner error={error} onRetry={codes.retry} pending={pending} />
        {error?.field && (
          <p role="alert">
            {error.field}: {error.message}
          </p>
        )}
        {saved && <p role="status">대응을 저장했습니다. 다시 검사해 주세요.</p>}
        <Button
          data-testid="partner-mapping-cancel"
          disabled={pending}
          onClick={onCancel}
        >
          {saved ? "닫기" : "취소"}
        </Button>
        <Button
          data-testid="partner-mapping-save"
          pending={pending}
          disabled={!code || !role || saved}
          onClick={() => void save()}
        >
          저장
        </Button>
      </section>
    </Modal>
  );
}
