import { useEffect, useState } from "react";
import { Lock, Plus, ListTree } from "lucide-react";
import { ScreenHeading } from "../shared/ScreenHeading";
import { Button, Tabs, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { Can } from "../../shared/Can";
import { listCodeGroups } from "./client";
import { CodeTab } from "./CodeTab";
import { ExcelMapsTab } from "./ExcelMapsTab";
import { ParamsTab } from "./ParamsTab";
import { HistoryTab } from "./HistoryTab";
import { GroupDialog } from "./CreateDialogs";
import { useAdminRead } from "./useAdminResource";
import { useAdminGuard } from "./AdminDialogs";
import styles from "./CommonCodes.module.css";

export default function UI27Screen({
  onDirtyChange,
  name = "공통코드 관리",
  groupName,
}: {
  onDirtyChange?: (dirty: boolean) => void;
  name?: string;
  groupName?: string;
}) {
  const groups = useAdminRead(listCodeGroups);
  const [selected, setSelected] = useState(""),
    [tab, setTab] = useState("codes"),
    [dirty, setDirty] = useState(false),
    [groupDialog, setGroupDialog] = useState(false),
    [generation, setGeneration] = useState(0);
  const [modalDirty, setModalDirty] = useState(false);
  const guard = useAdminGuard(dirty || modalDirty);
  useEffect(() => {
    if (!selected && groups.data?.length) setSelected(groups.data[0].cd_grp_id);
  }, [groups.data, selected]);
  useEffect(() => {
    onDirtyChange?.(dirty || modalDirty);
    return () => onDirtyChange?.(false);
  }, [dirty, modalDirty, onDirtyChange]);
  const group = groups.data?.find((g) => g.cd_grp_id === selected);
  const leave = (action: () => void) =>
    guard.request(() => {
      setDirty(false);
      setGeneration((g) => g + 1);
      action();
    });
  return (
    <>
      <div className={styles.mobileOnly}>
        <p>PC에서 이용해 주세요</p>
        <a href="/UI-16">대시보드로</a>
      </div>
      <section className={styles.desktopOnly} aria-label="공통코드 관리">
        <ScreenHeading
          screenId="UI-27"
          name={name}
          groupName={groupName}
          actions={
            <Can perm="UI-27.create">
              <Button onClick={() => leave(() => setGroupDialog(true))}>
                <Plus size={16} aria-hidden="true" />
                그룹 추가
              </Button>
            </Can>
          }
        />
        <Tabs
          selected={tab}
          onChange={(t) => {
            if (t !== tab) leave(() => setTab(t));
          }}
          items={[
            { key: "codes", label: "코드" },
            { key: "maps", label: "엑셀 표기 대응" },
            { key: "params", label: "기준값" },
            { key: "history", label: "변경 이력" },
          ]}
        />
        <ErrorBanner
          error={groups.error}
          onRetry={() => {
            void groups.reload().catch(() => {});
          }}
        />
        {groups.loading && !groups.data && <Skeleton />}
        {tab === "codes" && (
          <div className={styles.codeLayout}>
            <aside aria-label="코드 그룹">
              <h2 className={styles.groupHeading}>
                <ListTree size={16} aria-hidden="true" />
                코드 그룹
              </h2>
              {groups.data?.map((g) => (
                <Button
                  key={g.cd_grp_id}
                  aria-pressed={selected === g.cd_grp_id}
                  onClick={() => {
                    if (selected !== g.cd_grp_id)
                      leave(() => setSelected(g.cd_grp_id));
                  }}
                >
                  <span className={styles.groupLabel}>
                    {g.cd_grp_nm}
                    <small>{g.cd_grp_id}</small>
                  </span>
                  {g.cd_grp_se_cd === "SYSTEM" ? (
                    <Lock aria-label="SYSTEM 잠금" size={16} />
                  ) : (
                    <span>{g.codeCount}건</span>
                  )}
                </Button>
              ))}
            </aside>
            {group && (
              <CodeTab
                key={`${selected}-${generation}`}
                group={group}
                onDirtyChange={setDirty}
              />
            )}{" "}
            {!groups.loading && !groups.error && !groups.data?.length && (
              <p>조건에 맞는 결과가 없습니다</p>
            )}
          </div>
        )}
        {tab === "maps" && (
          <ExcelMapsTab
            key={generation}
            groups={groups.data ?? []}
            onDirtyChange={setDirty}
          />
        )}
        {tab === "params" && <ParamsTab onDirtyChange={setModalDirty} />}
        {tab === "history" && <HistoryTab groups={groups.data ?? []} />}
        {groupDialog && (
          <GroupDialog
            reload={groups.reload}
            onDirtyChange={setModalDirty}
            onCancel={() => setGroupDialog(false)}
            onSaved={() => setGroupDialog(false)}
          />
        )}
        {guard.confirmation}
      </section>
    </>
  );
}
