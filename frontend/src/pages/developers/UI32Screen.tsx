import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Button, ConfirmDialog } from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ScreenHeading } from "../shared/ScreenHeading";
import { DeveloperForm } from "./DeveloperForm";
import { useDeveloperReadOnly } from "./useDeveloperReadOnly";
import type { DeveloperDetail } from "./client";

// SCR-UI-32: developer registration, a child of the UI-02 tab.
export default function UI32Screen({
  onSaved,
  onDirtyChange,
}: {
  onSaved?: (row: DeveloperDetail) => void;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const navigate = useNavigate(),
    readOnly = useDeveloperReadOnly();
  const [dirty, setDirty] = useState(false),
    [confirm, setConfirm] = useState(false);
  useEffect(() => {
    if (readOnly) navigate("/UI-02", { replace: true });
  }, [readOnly, navigate]);
  useEffect(() => {
    onDirtyChange?.(dirty);
    return () => onDirtyChange?.(false);
  }, [dirty, onDirtyChange]);
  const leave = () => {
    if (dirty) setConfirm(true);
    else navigate("/UI-02");
  };
  if (readOnly) return null;
  return (
    <section data-testid="developer-registration" data-screen-id="SCR-UI-32">
      <ScreenHeading
        screenId="UI-02"
        name="개발자 등록"
        groupName="기준정보 > 개발자 풀"
        actions={
          <Button data-testid="developer-back" onClick={leave}>
            목록
          </Button>
        }
      />
      <Can perm="UI-02.create">
        <DeveloperForm
          onDirtyChange={setDirty}
          onCancel={leave}
          reload={async () => {}}
          onSaved={(row) => {
            setDirty(false);
            onDirtyChange?.(false);
            if (row) {
              onSaved?.(row);
              navigate("/UI-02", { state: { developerCreated: row.dev_id } });
            }
          }}
        />
      </Can>
      {confirm && (
        <ConfirmDialog
          title="저장하지 않은 내용이 있습니다"
          cancelLabel="계속 작성"
          confirmLabel="나가기"
          onCancel={() => setConfirm(false)}
          onConfirm={() => navigate("/UI-02")}
        >
          나가면 입력한 내용이 사라집니다.
        </ConfirmDialog>
      )}
    </section>
  );
}
