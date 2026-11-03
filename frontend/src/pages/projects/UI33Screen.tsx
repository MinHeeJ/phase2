import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Button, ConfirmDialog } from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ScreenHeading } from "../shared/ScreenHeading";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import { ProjectForm } from "./ProjectForm";
import type { ProjectDetail } from "./client";

// SCR-UI-33: child of the UI-01 workspace, never a separate menu tab.
export default function UI33Screen({
  onSaved,
  onDirtyChange,
}: {
  onSaved?: (row: ProjectDetail) => void;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const navigate = useNavigate(),
    readOnly = useDeveloperReadOnly();
  const [dirty, setDirty] = useState(false),
    [confirm, setConfirm] = useState(false);
  useEffect(() => {
    onDirtyChange?.(dirty);
    return () => onDirtyChange?.(false);
  }, [dirty, onDirtyChange]);
  const leave = () => (dirty ? setConfirm(true) : navigate("/UI-01"));
  if (readOnly)
    return (
      <section data-testid="project-registration-mobile">
        <p>프로젝트 등록은 PC에서 이용해 주세요.</p>
        <Button
          data-testid="project-return-list"
          onClick={() => navigate("/UI-01")}
        >
          목록
        </Button>
      </section>
    );
  return (
    <section data-testid="project-registration" data-screen-id="SCR-UI-33">
      <ScreenHeading
        screenId="UI-01"
        name="프로젝트 등록"
        groupName="프로젝트·투입 > 프로젝트"
        actions={
          <Button data-testid="project-back" onClick={leave}>
            목록
          </Button>
        }
      />
      <Can perm="UI-01.create">
        <ProjectForm
          onDirtyChange={setDirty}
          onCancel={leave}
          onSaved={(row) => {
            setDirty(false);
            onDirtyChange?.(false);
            onSaved?.(row);
            navigate("/UI-01", { state: { projectCreated: row.prj_id } });
          }}
        />
      </Can>
      {confirm && (
        <ConfirmDialog
          title="저장하지 않은 내용이 있습니다"
          cancelLabel="계속 작성"
          confirmLabel="나가기"
          onCancel={() => setConfirm(false)}
          onConfirm={() => {
            setDirty(false);
            onDirtyChange?.(false);
            navigate("/UI-01");
          }}
        >
          나가면 입력한 내용이 사라집니다.
        </ConfirmDialog>
      )}
    </section>
  );
}
