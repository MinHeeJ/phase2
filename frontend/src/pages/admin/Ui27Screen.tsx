import { useCallback, useEffect, useState } from "react";
import { Actions, Button, Can, Tabs } from "../../shared";
import { adminApi } from "./adminApi";
import { AdminLoadState, useAdminDiscard, useAdminLoad } from "./adminState";
import { CodesTab } from "./CodesTab";
import { ExcelMapsTab } from "./ExcelMapsTab";
import { GroupDialog } from "./GroupDialog";
import { CodeHistoryTab, ParamsTab } from "./ParamsTab";
import styles from "./Admin.module.css";

export function Ui27Screen({
  name = "공통코드",
  onDirtyChange,
  embedded = false,
}: {
  name?: string;
  onDirtyChange?: (dirty: boolean) => void;
  embedded?: boolean;
}) {
  const [mobile, setMobile] = useState(
    () => window.matchMedia?.("(max-width: 767px)").matches ?? false,
  );
  useEffect(() => {
    const media = window.matchMedia?.("(max-width: 767px)");
    if (!media) return;
    const change = () => setMobile(media.matches);
    media.addEventListener("change", change);
    return () => media.removeEventListener("change", change);
  }, []);
  return mobile ? (
    <section>
      <p>PC에서 이용해 주세요</p>
      <a href="/UI-16">대시보드로</a>
    </section>
  ) : (
    <Ui27Content
      name={name}
      onDirtyChange={onDirtyChange}
      embedded={embedded}
    />
  );
}
function Ui27Content({
  name,
  onDirtyChange,
  embedded,
}: {
  name: string;
  onDirtyChange?: (dirty: boolean) => void;
  embedded: boolean;
}) {
  const groups = useAdminLoad(adminApi.listCodeGroups, []);
  const [selected, setSelected] = useState("");
  const [tab, setTab] = useState("codes");
  const [dirty, setDirty] = useState(false);
  const [groupDialog, setGroupDialog] = useState(false);
  const reportDirty = useCallback(
    (value: boolean) => {
      setDirty(value);
      onDirtyChange?.(value);
    },
    [onDirtyChange],
  );
  useEffect(() => {
    if (!selected && groups.data?.length) setSelected(groups.data[0].cd_grp_id);
  }, [groups.data, selected]);
  useEffect(() => {
    const before = (event: BeforeUnloadEvent) => {
      if (dirty) {
        event.preventDefault();
        event.returnValue = "";
      }
    };
    window.addEventListener("beforeunload", before);
    return () => window.removeEventListener("beforeunload", before);
  }, [dirty]);
  const discard = useAdminDiscard(dirty);
  return (
    <section className={styles.page}>
      <header className={styles.heading}>
        {embedded ? (
          <p className={styles.description}>
            코드와 기준정보를 관리하고 변경 내역을 확인합니다.
          </p>
        ) : (
          <h1>
            {dirty ? "● " : ""}
            {name}
          </h1>
        )}
        {!embedded && <span>UI-27</span>}
        <Actions>
          <Can perm="UI-27.create">
            <Button onClick={() => setGroupDialog(true)}>그룹 추가</Button>
          </Can>
        </Actions>
      </header>
      <Tabs
        items={[
          { key: "codes", label: "코드" },
          { key: "excel", label: "엑셀 표기 대응" },
          { key: "params", label: "기준값" },
          { key: "history", label: "변경 이력" },
        ]}
        value={tab}
        onChange={(next) => {
          if (next !== tab)
            discard.request(() => {
              reportDirty(false);
              setTab(next);
            });
        }}
      />
      <AdminLoadState
        loading={groups.loading}
        error={groups.error}
        retry={groups.reload}
      />
      {groups.data && (
        <Can perm="UI-27.read">
          {tab === "codes" && (
            <CodesTab
              groups={groups.data}
              selected={selected}
              onSelect={(id) => {
                if (id !== selected)
                  discard.request(() => {
                    reportDirty(false);
                    setSelected(id);
                  });
              }}
              onDirty={reportDirty}
              onSaved={groups.reload}
            />
          )}
          {tab === "excel" && (
            <ExcelMapsTab groups={groups.data} onDirty={reportDirty} />
          )}
          {tab === "params" && <ParamsTab />}
          {tab === "history" && <CodeHistoryTab groups={groups.data} />}
        </Can>
      )}
      {groupDialog && (
        <GroupDialog
          onClose={() => setGroupDialog(false)}
          onSaved={groups.reload}
        />
      )}
      {discard.dialog}
    </section>
  );
}
export default Ui27Screen;
