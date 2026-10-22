import { useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { Plus } from "lucide-react";
import { allPages, api, ApiError, type Group } from "../../services/api";
import {
  Busy,
  Can,
  ErrorBanner,
  Loading,
  Modal,
  TextField,
} from "../../components/Common";
import { CodeEditor } from "./CodeEditor";
import { Mappings } from "./Mappings";
import { Parameters } from "./Parameters";
import { History } from "./History";
import { usePreferences } from "../../stores/preferences";
import "./codes.css";
export function CommonCodes({
  active = true,
  actionsHost,
  guard,
  setDirty,
  toast,
}: {
  guard: (a: () => void) => void;
  active?: boolean;
  actionsHost?: HTMLElement | null;
  setDirty: (d: boolean) => void;
  toast: (s: string) => void;
}) {
  const [groups, setGroups] = useState<Group[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>();
  const [revision, setRevision] = useState(0);
  const [tab, setTab] = useState(0);
  const [groupModal, setGroupModal] = useState(false);
  const pref = usePreferences();
  const groupAction = (
    <Can perm="UI-27.create">
      <button onClick={() => guard(() => setGroupModal(true))}>
        <Plus size={16} />
        그룹 추가
      </button>
    </Can>
  );
  useEffect(() => {
    const c = new AbortController();
    setLoading(true);
    allPages<Group>("/api/admin/code-groups", c.signal)
      .then(setGroups)
      .catch((e) => {
        if (!c.signal.aborted) setError(e);
      })
      .finally(() => {
        if (!c.signal.aborted) setLoading(false);
      });
    return () => c.abort();
  }, [revision]);
  return (
    <div className={pref.dense ? "dense" : ""}>
      <div className="toolbar">
        <div role="tablist" aria-label="공통코드 탭" className="subtabs">
          {["코드", "엑셀 표기 대응", "기준값", "변경 이력"].map((name, i) => (
            <button
              role="tab"
              aria-selected={tab === i}
              key={name}
              onClick={() => guard(() => setTab(i))}
            >
              {name}
            </button>
          ))}
        </div>
        {active &&
          (actionsHost ? createPortal(groupAction, actionsHost) : groupAction)}
        <label>
          <input
            type="checkbox"
            checked={pref.dense}
            onChange={(e) => pref.set({ dense: e.target.checked })}
          />{" "}
          좁게 보기 32px
        </label>
      </div>
      <ErrorBanner
        error={error}
        retry={() => {
          setError(undefined);
          setRevision((v) => v + 1);
        }}
      />
      {loading && !groups.length ? (
        <Loading />
      ) : (
        <>
          <div hidden={tab !== 0}>
            <CodeEditor
              groups={groups}
              guard={guard}
              setDirty={setDirty}
              toast={toast}
            />
          </div>
          {tab === 1 && <Mappings groups={groups} toast={toast} />}{" "}
          {tab === 2 && <Parameters toast={toast} />}{" "}
          {tab === 3 && <History groups={groups} />}
        </>
      )}
      {groupModal && (
        <GroupForm
          onClose={() => setGroupModal(false)}
          onSaved={() => {
            setGroupModal(false);
            setRevision((v) => v + 1);
            toast("그룹을 추가했습니다.");
          }}
        />
      )}
    </div>
  );
}
function GroupForm({
  onClose,
  onSaved,
}: {
  onClose: () => void;
  onSaved: () => void;
}) {
  const [form, setForm] = useState({
    cd_grp_id: "",
    cd_grp_nm: "",
    attr1_nm: "",
    attr2_nm: "",
    attr3_nm: "",
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  useEffect(() => {
    if (!busy && error instanceof ApiError && error.field)
      document.getElementsByName(error.field)[0]?.focus();
  }, [busy, error]);
  const labels: Record<keyof typeof form, string> = {
    cd_grp_id: "그룹 코드",
    cd_grp_nm: "그룹명",
    attr1_nm: "추가 값 이름1",
    attr2_nm: "추가 값 이름2",
    attr3_nm: "추가 값 이름3",
  };
  return (
    <Modal
      title="그룹 추가"
      onClose={() => {
        if (!busy) onClose();
      }}
    >
      <ErrorBanner error={error} />
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          if (busy) return;
          setBusy(true);
          setError(undefined);
          try {
            await api("/api/admin/code-groups", "POST", form);
            onSaved();
          } catch (e) {
            setError(e);
          } finally {
            setBusy(false);
          }
        }}
      >
        <div className="formGrid">
          {(Object.keys(form) as (keyof typeof form)[]).map((field) => (
            <TextField
              key={field}
              name={field}
              label={labels[field]}
              value={form[field]}
              required={field === "cd_grp_id" || field === "cd_grp_nm"}
              maxLength={
                field === "cd_grp_id" ? 30 : field === "cd_grp_nm" ? 100 : 50
              }
              pattern={field === "cd_grp_id" ? "[A-Z_]{1,30}" : undefined}
              disabled={busy}
              onChange={(e) =>
                setForm((s) => ({ ...s, [field]: e.target.value }))
              }
              error={
                error instanceof ApiError && error.field === field
                  ? error.message
                  : undefined
              }
            />
          ))}
        </div>
        <div className="actions">
          <button type="button" disabled={busy} onClick={onClose}>
            취소
          </button>
          <Busy busy={busy} className="primary">
            저장
          </Busy>
        </div>
      </form>
    </Modal>
  );
}
