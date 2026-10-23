import { useState } from "react";
import { TextInput, SelectInput, TextareaInput } from "../../shared/Inputs";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { AdminWorkDialog } from "./AdminDialogs";
import {
  createCodeGroup,
  createExcelMap,
  type CodeGroup,
  type CodeGroupCreate,
  type ExcelMapCreate,
} from "./client";
import { useCodes } from "../shared/useCodes";
import { useAdminWrite } from "./useAdminResource";

export function GroupDialog({
  onCancel,
  onSaved,
  reload,
  onDirtyChange,
}: {
  onCancel: () => void;
  onSaved: () => void;
  reload: () => Promise<unknown>;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const [draft, setDraft] = useState<CodeGroupCreate>({
    cd_grp_id: "",
    cd_grp_nm: "",
    attr1_nm: "",
    attr2_nm: "",
    attr3_nm: "",
  });
  const write = useAdminWrite(reload);
  const input = (
    key: keyof CodeGroupCreate,
    label: string,
    maxLength: number,
    required = false,
  ) => (
    <TextInput
      key={key}
      name={key}
      label={label}
      maxLength={maxLength}
      required={required}
      value={draft[key]}
      disabled={write.pending}
      error={write.error?.field === key ? write.error.message : null}
      onValueChange={(value) => {
        write.clear();
        setDraft({ ...draft, [key]: value });
      }}
    />
  );
  return (
    <AdminWorkDialog
      title="그룹 추가"
      onDirtyChange={onDirtyChange}
      onCancel={onCancel}
      onSave={() => {
        void write.save(() => createCodeGroup(draft), onSaved);
      }}
      dirty={Object.values(draft).some(Boolean)}
      pending={write.pending}
      error={write.error}
      onRetry={() => {
        void write.recover();
      }}
      blocked={write.uncertain}
    >
      {input("cd_grp_id", "그룹 코드", 30, true)}
      <p>영문 대문자·_ 1~30자</p>
      {input("cd_grp_nm", "그룹명", 100, true)}
      {input("attr1_nm", "추가 값 이름1", 50)}
      {input("attr2_nm", "추가 값 이름2", 50)}
      {input("attr3_nm", "추가 값 이름3", 50)}
    </AdminWorkDialog>
  );
}
export function ExcelMapDialog({
  groups,
  onCancel,
  onSaved,
  reload,
  onDirtyChange,
}: {
  groups: CodeGroup[];
  onCancel: () => void;
  onSaved: () => void;
  reload: () => Promise<unknown>;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const [draft, setDraft] = useState({
    excel_lbl_nm: "",
    trns_cd_grp_id: "",
    trns_cd: "",
    map_role_cd: "",
    rmrk_desc: "",
  });
  const write = useAdminWrite(reload),
    codes = useCodes(draft.trns_cd_grp_id);
  const change = (field: string, value: string) => {
    write.clear();
    setDraft((d) => ({
      ...d,
      [field]: value,
      ...(field === "trns_cd_grp_id" ? { trns_cd: "" } : {}),
    }));
  };
  const error = (field: string) =>
    write.error?.field === field ? write.error.message : null;
  return (
    <AdminWorkDialog
      title="대응 추가"
      onDirtyChange={onDirtyChange}
      onCancel={onCancel}
      onSave={() => {
        void write.save(() => createExcelMap(draft as ExcelMapCreate), onSaved);
      }}
      dirty={Object.values(draft).some(Boolean)}
      pending={write.pending}
      error={write.error}
      onRetry={() => {
        void write.recover();
      }}
      blocked={write.uncertain || codes.loading || !!codes.error}
    >
      <TextInput
        name="excel_lbl_nm"
        label="엑셀 표기"
        required
        maxLength={100}
        value={draft.excel_lbl_nm}
        onValueChange={(v) => change("excel_lbl_nm", v)}
        error={error("excel_lbl_nm")}
        disabled={write.pending}
      />
      <SelectInput
        name="trns_cd_grp_id"
        label="그룹"
        required
        value={draft.trns_cd_grp_id}
        options={groups.map((g) => ({
          value: g.cd_grp_id,
          label: g.cd_grp_nm,
        }))}
        onValueChange={(v) => change("trns_cd_grp_id", v)}
        error={error("trns_cd_grp_id")}
        disabled={write.pending}
      />
      <ErrorBanner error={codes.error} onRetry={codes.retry} />
      <SelectInput
        name="trns_cd"
        label="코드"
        required
        value={draft.trns_cd}
        options={codes.codes.map((c) => ({ value: c.code, label: c.name }))}
        onValueChange={(v) => change("trns_cd", v)}
        error={error("trns_cd")}
        disabled={write.pending || codes.loading || !draft.trns_cd_grp_id}
      />
      <SelectInput
        name="map_role_cd"
        label="주/보조"
        required
        value={draft.map_role_cd}
        options={[
          { value: "MAIN", label: "주" },
          { value: "SUB", label: "보조" },
        ]}
        onValueChange={(v) => change("map_role_cd", v)}
        error={error("map_role_cd")}
        disabled={write.pending}
      />
      <TextareaInput
        name="rmrk_desc"
        label="메모"
        maxLength={200}
        value={draft.rmrk_desc}
        onValueChange={(v) => change("rmrk_desc", v)}
        error={error("rmrk_desc")}
        disabled={write.pending}
      />
    </AdminWorkDialog>
  );
}
