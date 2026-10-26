import { useCallback, useEffect, useState } from "react";
import { LockKeyhole, Plus } from "lucide-react";
import {
  ScreenTitleAction,
  useSharedScreenState,
} from "../shared/SharedWorkspace";
import { useSharedRead } from "../shared/sharedApi";
import { ErrorBanner } from "../../shared/ErrorBanner";
import {
  Button,
  Dialog,
  Skeleton,
  Tabs,
  SuccessToast,
  Notice,
} from "../../shared/ui";
import { Can } from "../../shared/permissions";
import { collectAdminPages, listCodeGroups, type CodeGroup } from "./adminApi";
import { AdminCodeRegion } from "./AdminCodeRegion";
import { AdminMapRegion, AdminGroupDialog } from "./AdminMapRegion";
import { AdminParamRegion } from "./AdminParamRegion";
import { AdminHistoryRegion } from "./AdminHistoryRegion";
import styles from "./admin.module.css";

const loadGroups = () => collectAdminPages((page) => listCodeGroups(page, 100));
export interface AdminRegionProps {
  onDirty: (value: boolean) => void;
  onSuccess: (message: string) => void;
  guard: (action: () => void) => void;
}
export function UI27() {
  const groups = useSharedRead(loadGroups);
  const [tab, setTab] = useState("codes");
  const [group, setGroup] = useState<CodeGroup>();
  const [dirty, setDirty] = useState(false);
  const [discard, setDiscard] = useState<(() => void) | null>(null);
  const [generation, setGeneration] = useState(0);
  const [groupDialog, setGroupDialog] = useState(false);
  const [success, setSuccess] = useState("");
  const shell = useSharedScreenState();
  const onDirty = useCallback((value: boolean) => setDirty(value), []);
  useEffect(() => {
    shell.setDirty(dirty);
  }, [dirty, shell.setDirty]);
  useEffect(() => {
    if (!group && groups.data?.length) setGroup(groups.data[0]);
    else if (group && groups.data) {
      const fresh = groups.data.find(
        (row) => row.cd_grp_id === group.cd_grp_id,
      );
      if (fresh) setGroup(fresh);
    }
  }, [groups.data]);
  useEffect(() => {
    if (!dirty) return;
    const handler = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [dirty]);
  function guard(action: () => void) {
    if (dirty) setDiscard(() => action);
    else action();
  }
  const common: AdminRegionProps = { onDirty, onSuccess: setSuccess, guard };
  return (
    <section
      data-testid="scr-ui-27"
      aria-label="공통코드 관리"
      className={styles.workspace}
    >
      <Can perm="UI-27.read">
        <Tabs
          items={[
            { key: "codes", label: "코드" },
            { key: "maps", label: "엑셀 표기 대응" },
            { key: "params", label: "기준값" },
            { key: "history", label: "변경 이력" },
          ]}
          value={tab}
          onChange={(value) => {
            if (value !== tab)
              guard(() => {
                setDirty(false);
                setTab(value);
              });
          }}
        />
        <ErrorBanner error={groups.error} onRetry={groups.retry} />
        {groups.loading && !groups.data && (
          <Skeleton label="그룹 불러오는 중" />
        )}
        <div
          data-testid={`admin-tab-panel-${tab}`}
          key={`${tab}-${generation}`}
        >
          {tab === "codes" && (
            <>
              <div className={styles.regionHeading}>
                <div>
                  <h2>코드 그룹 및 상세 코드</h2>
                  <p>그룹을 선택하고 코드 정보를 편집하세요.</p>
                </div>
                <ScreenTitleAction>
                  <Can perm="UI-27.create">
                    <Button
                      onClick={() =>
                        guard(() => {
                          setGeneration((n) => n + 1);
                          setGroupDialog(true);
                        })
                      }
                    >
                      <Plus size={16} aria-hidden="true" /> 그룹 추가
                    </Button>
                  </Can>
                </ScreenTitleAction>
              </div>
              <div className={styles.codeLayout}>
                <nav aria-label="코드 그룹" className={styles.groups}>
                  <h2>코드 그룹</h2>
                  {groups.data?.map((item, index) => (
                    <button
                      data-testid={`a-27-select-${index}`}
                      key={item.cd_grp_id}
                      type="button"
                      aria-current={
                        group?.cd_grp_id === item.cd_grp_id ? "true" : undefined
                      }
                      onClick={() => {
                        if (group?.cd_grp_id !== item.cd_grp_id)
                          guard(() => {
                            setDirty(false);
                            setGroup(item);
                          });
                      }}
                    >
                      <span>{item.cd_grp_nm}</span>
                      <span className={styles.groupMeta}>
                        {item.cd_grp_se_cd === "SYSTEM" && (
                          <LockKeyhole size={12} aria-hidden="true" />
                        )}
                        {item.cd_grp_se_cd === "SYSTEM"
                          ? "SYSTEM 잠금"
                          : `${item.codeCount}개`}
                      </span>
                    </button>
                  ))}
                </nav>
                {group ? (
                  <AdminCodeRegion
                    key={group.cd_grp_id}
                    group={group}
                    {...common}
                    onSaved={groups.retry}
                  />
                ) : (
                  !groups.loading &&
                  !groups.error && <Notice>조건에 맞는 결과가 없습니다</Notice>
                )}
              </div>
            </>
          )}
          {tab === "maps" && (
            <AdminMapRegion groups={groups.data ?? []} {...common} />
          )}
          {tab === "params" && <AdminParamRegion {...common} />}
          {tab === "history" && (
            <AdminHistoryRegion groups={groups.data ?? []} />
          )}
        </div>
      </Can>
      {groupDialog && (
        <AdminGroupDialog
          onDirty={onDirty}
          onClose={() =>
            guard(() => {
              setGroupDialog(false);
              setDirty(false);
            })
          }
          onSaved={() => {
            setGroupDialog(false);
            setDirty(false);
            groups.retry();
            setSuccess("그룹을 추가했습니다.");
          }}
        />
      )}
      {discard && (
        <Dialog
          title="저장하지 않은 내용"
          confirm
          onClose={() => setDiscard(null)}
        >
          <p>저장하지 않은 내용이 있습니다. 나가면 사라집니다.</p>
          <div className={styles.toolbar}>
            <Button onClick={() => setDiscard(null)}>계속 작성</Button>
            <Button
              primary
              onClick={() => {
                const action = discard;
                setDiscard(null);
                setDirty(false);
                action();
              }}
            >
              나가기
            </Button>
          </div>
        </Dialog>
      )}
      {success && (
        <SuccessToast message={success} onClose={() => setSuccess("")} />
      )}
    </section>
  );
}
export default UI27;
export function renderAdminScreen(screenId: string) {
  return screenId === "UI-27" ? <UI27 /> : undefined;
}
