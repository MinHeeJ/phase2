import { useState } from "react";
import { Button, Modal } from "../../shared/Controls";
import { TextInput, SelectInput } from "../../shared/Inputs";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useCodes } from "../shared/useCodes";
import { createExcelMap } from "../admin/client";
import { useAdminWrite } from "../admin/useAdminResource";
import styles from "./PartnerLifecycle.module.css";

// SCR-UI-15-M3: parent upload result keeps its upldId/token and owns recheck.
export function PartnerMappingDialog({
  label,
  group,
  onClose,
  onSaved,
}: {
  label: string;
  group: "PARTNER_KIND" | "INDUSTRY" | "BANK";
  onClose: () => void;
  onSaved: () => void;
}) {
  const codes = useCodes(group);
  const [code, setCode] = useState(""),
    [role, setRole] = useState("");
  const write = useAdminWrite(async () => {});
  return (
    <Modal
      title="표기 대응 등록"
      onCancel={() => {
        if (!write.pending) onClose();
      }}
    >
      <form
        data-testid="partner-mapping-dialog"
        onSubmit={(event) => {
          event.preventDefault();
          void write.save(
            () =>
              createExcelMap({
                excel_lbl_nm: label,
                trns_cd_grp_id: group,
                trns_cd: code,
                map_role_cd: role as "MAIN" | "SUB",
                rmrk_desc: "",
              }),
            onSaved,
          );
        }}
      >
        <TextInput
          name="excel_lbl_nm"
          label="엑셀 표기"
          readOnly
          value={label}
          onValueChange={() => {}}
        />
        <TextInput
          name="trns_cd_grp_id"
          label="그룹"
          readOnly
          value={group}
          onValueChange={() => {}}
        />
        <SelectInput
          name="trns_cd"
          label="대응 코드"
          required
          value={code}
          onValueChange={setCode}
          options={codes.codes.map((item) => ({
            value: item.code,
            label: item.name,
          }))}
          error={write.error?.field === "trns_cd" ? write.error.message : null}
        />
        <SelectInput
          name="map_role_cd"
          label="주/보조"
          required
          value={role}
          onValueChange={setRole}
          options={[
            { value: "MAIN", label: "주" },
            { value: "SUB", label: "보조" },
          ]}
          error={
            write.error?.field === "map_role_cd" ? write.error.message : null
          }
        />
        <ErrorBanner error={codes.error} onRetry={codes.retry} />
        <ErrorBanner
          error={write.error}
          onRetry={() => {
            void write.recover();
          }}
          pending={write.pending}
        />
        <p>저장한 뒤 다시 검사를 실행해 주세요.</p>
        <div className={styles.actions}>
          <Button
            type="button"
            data-testid="mapping-cancel"
            disabled={write.pending}
            onClick={onClose}
          >
            취소
          </Button>
          <Button
            data-testid="mapping-save"
            type="submit"
            variant="primary"
            pending={write.pending}
            disabled={
              !code ||
              !role ||
              codes.loading ||
              !!codes.error ||
              write.uncertain
            }
          >
            저장
          </Button>
        </div>
      </form>
    </Modal>
  );
}
export function PartnerMappingRepair({
  label,
  group,
  recheck,
}: {
  label: string;
  group: "PARTNER_KIND" | "INDUSTRY" | "BANK";
  recheck: () => void;
}) {
  const [open, setOpen] = useState(false),
    [saved, setSaved] = useState(false);
  return (
    <span className={styles.mutate}>
      <Button data-testid="mapping-open" onClick={() => setOpen(true)}>
        대응 등록
      </Button>
      {saved && (
        <Button
          data-testid="mapping-recheck"
          variant="primary"
          onClick={recheck}
        >
          다시 검사
        </Button>
      )}
      {open && (
        <PartnerMappingDialog
          label={label}
          group={group}
          onClose={() => setOpen(false)}
          onSaved={() => {
            setOpen(false);
            setSaved(true);
          }}
        />
      )}
    </span>
  );
}
