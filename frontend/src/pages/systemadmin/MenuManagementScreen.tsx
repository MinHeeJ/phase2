import { useCallback, useEffect, useState, type ReactNode } from "react";
import { Button } from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure, readData } from "../../shared/api";
import { ScreenHeading } from "../shared/ScreenHeading";
import { screenIds } from "../shared/registry";
import { useAdminRead } from "../admin/useAdminResource";
import { adminMenus, command, type AdminMenu } from "./client";
import styles from "./SystemAdministration.module.css";

const empty = () => ({
  name: "",
  icon: "",
  screenId: "",
  parentId: null as number | null,
  sortOrder: 0,
  useYn: "Y",
  mobileVisible: true,
  riskConfirmed: false,
});
export default function MenuManagementScreen() {
  const menus = useAdminRead(adminMenus);
  const [rows, setRows] = useState<AdminMenu[]>([]),
    [selected, setSelected] = useState<number | null>(null),
    [creating, setCreating] = useState(false),
    [draft, setDraft] = useState(empty);
  const [error, setError] = useState<ApiFailure | null>(null),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState(""),
    [preview, setPreview] = useState(false),
    [confirm, setConfirm] = useState(false),
    [expanded, setExpanded] = useState(true);
  const channels = useAdminRead(
    useCallback(
      () =>
        readData<{ businessEmailEnabled: boolean }>(
          "/api/admin/notification-channels",
        ),
      [],
    ),
  );
  const [mail, setMail] = useState(false);
  useEffect(() => {
    if (menus.data) setRows(menus.data);
  }, [menus.data]);
  useEffect(() => {
    if (channels.data) setMail(channels.data.businessEmailEnabled);
  }, [channels.data]);
  const select = (row: AdminMenu) => {
    setSelected(row.menuId);
    setCreating(false);
    setDraft({
      ...empty(),
      name: row.name,
      screenId: row.screenId ?? "",
      icon: row.icon ?? "",
      parentId: row.parentId ?? null,
      sortOrder: row.sortOrder,
      useYn: row.useYn,
      mobileVisible: row.mobileVisible,
    });
    setConfirm(false);
  };
  const run = async (fn: () => Promise<unknown>) => {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await fn();
      const latest = await menus.reload();
      setRows(latest);
      const row = latest.find((r) => r.menuId === selected);
      if (row) select(row);
      setMessage("저장했습니다. 변경된 권한은 다음 요청부터 반영됩니다.");
      setPreview(false);
      setConfirm(false);
    } catch (e) {
      setError(e as ApiFailure);
    } finally {
      setBusy(false);
    }
  };
  const save = () => {
    if (!creating && draft.useYn === "N" && !confirm) {
      setConfirm(true);
      return;
    }
    const { screenId, riskConfirmed, ...attributes } = draft;
    void run(() =>
      command(
        creating ? "/api/admin/menus" : `/api/admin/menus/${selected}`,
        creating ? "POST" : "PATCH",
        creating
          ? { ...attributes, screenId: screenId || null }
          : { ...attributes, riskConfirmed: confirm || riskConfirmed },
      ),
    );
  };
  const move = (row: AdminMenu, offset: number) => {
    const siblings = rows
      .filter((r) => r.parentId === row.parentId)
      .sort((a, b) => a.sortOrder - b.sortOrder || a.menuId - b.menuId);
    const index = siblings.findIndex((r) => r.menuId === row.menuId),
      next = index + offset;
    if (next < 0 || next >= siblings.length) return;
    [siblings[index], siblings[next]] = [siblings[next], siblings[index]];
    setRows((all) =>
      all.map((r) => {
        const order = siblings.findIndex((s) => s.menuId === r.menuId);
        return order < 0 ? r : { ...r, sortOrder: order };
      }),
    );
  };
  const tree = (parent: number | null, depth = 0): ReactNode =>
    rows
      .filter((r) => r.parentId === parent)
      .sort((a, b) => a.sortOrder - b.sortOrder || a.menuId - b.menuId)
      .map((row) => (
        <div
          key={row.menuId}
          data-testid={`menu-row-${row.menuId}`}
          style={{ paddingLeft: depth ? 12 : 0 }}
        >
          <div className={styles.toolbar}>
            <Button
              data-testid={`menu-select-${row.menuId}`}
              aria-pressed={selected === row.menuId}
              onClick={() => select(row)}
            >
              {row.name} {row.screenId ?? "그룹"} · {row.useYn}
            </Button>
            <Button
              data-testid={`menu-up-${row.menuId}`}
              aria-label={`${row.name} 위로`}
              onClick={() => move(row, -1)}
            >
              ↑
            </Button>
            <Button
              data-testid={`menu-down-${row.menuId}`}
              aria-label={`${row.name} 아래로`}
              onClick={() => move(row, 1)}
            >
              ↓
            </Button>
          </div>
          {expanded && depth < rows.length && tree(row.menuId, depth + 1)}
        </div>
      ));
  return (
    <section
      className={styles.screen}
      data-screen-id="UI-25"
      data-testid="menu-management-screen"
    >
      <ScreenHeading screenId="UI-25" name="메뉴 관리" groupName="시스템" />
      <ErrorBanner
        error={error ?? menus.error ?? channels.error}
        onRetry={() => void menus.reload().catch(() => {})}
      />
      {error?.field && (
        <p role="alert">
          {error.field}: {error.message}
        </p>
      )}
      {message && <p role="status">{message}</p>}
      <div className={styles.layout}>
        <aside className={styles.panel}>
          <div className={styles.toolbar}>
            <Can perm="UI-25.create">
              <Button
                data-testid="menu-create"
                onClick={() => {
                  setSelected(null);
                  setCreating(true);
                  setDraft(empty());
                  setConfirm(false);
                }}
              >
                메뉴 추가
              </Button>
            </Can>
            <Button
              data-testid="menu-tree-toggle"
              onClick={() => setExpanded((v) => !v)}
            >
              {expanded ? "접기" : "펼치기"}
            </Button>
          </div>
          {menus.loading ? (
            <p role="status">불러오는 중…</p>
          ) : !rows.length ? (
            <p>메뉴가 없습니다.</p>
          ) : (
            tree(null)
          )}
          <Button
            data-testid="menu-order-preview"
            onClick={() => setPreview(true)}
          >
            순서 미리보기
          </Button>
          <Button
            data-testid="menu-order-cancel"
            onClick={() => {
              setRows(menus.data ?? []);
              setPreview(false);
            }}
          >
            순서 취소
          </Button>
          {preview && (
            <div
              className={styles.confirm}
              data-testid="menu-order-preview-panel"
            >
              <p>
                위 트리 순서로 저장합니다. 취소하면 서버의 순서는 바뀌지
                않습니다.
              </p>
              <Can perm="UI-25.update">
                <Button
                  data-testid="menu-order-save"
                  pending={busy}
                  onClick={() =>
                    void run(() =>
                      command("/api/admin/menus/reorder", "PUT", {
                        items: rows.map(({ menuId, parentId, sortOrder }) => ({
                          menuId,
                          parentId,
                          sortOrder,
                        })),
                      }),
                    )
                  }
                >
                  순서 저장
                </Button>
              </Can>
            </div>
          )}
        </aside>
        <div
          className={`${styles.panel} ${styles.fields}`}
          data-testid="menu-detail-panel"
        >
          {!selected && !creating ? (
            <p>설정할 메뉴를 선택하세요.</p>
          ) : (
            <>
              <label>
                표시명
                <input
                  data-testid="menu-name"
                  maxLength={50}
                  value={draft.name}
                  onChange={(e) =>
                    setDraft((d) => ({ ...d, name: e.target.value }))
                  }
                />
              </label>
              <label>
                아이콘
                <input
                  data-testid="menu-icon"
                  maxLength={30}
                  value={draft.icon}
                  onChange={(e) =>
                    setDraft((d) => ({ ...d, icon: e.target.value }))
                  }
                />
              </label>
              <label>
                고정 화면 ID
                {creating ? (
                  <select
                    data-testid="menu-screen-id"
                    value={draft.screenId}
                    onChange={(e) =>
                      setDraft((d) => ({ ...d, screenId: e.target.value }))
                    }
                  >
                    <option value="">그룹</option>
                    {screenIds
                      .filter((s) => !rows.some((r) => r.screenId === s))
                      .map((s) => (
                        <option key={s}>{s}</option>
                      ))}
                  </select>
                ) : (
                  <input
                    data-testid="menu-screen-id"
                    readOnly
                    value={draft.screenId}
                  />
                )}
              </label>
              <label>
                상위 그룹
                <select
                  data-testid="menu-parent"
                  value={draft.parentId ?? ""}
                  onChange={(e) =>
                    setDraft((d) => ({
                      ...d,
                      parentId: e.target.value ? Number(e.target.value) : null,
                    }))
                  }
                >
                  <option value="">최상위</option>
                  {rows
                    .filter((r) => r.screenId === null && r.menuId !== selected)
                    .map((r) => (
                      <option key={r.menuId} value={r.menuId}>
                        {r.name}
                      </option>
                    ))}
                </select>
              </label>
              <label>
                표시 순서
                <input
                  data-testid="menu-sort-order"
                  type="number"
                  min={0}
                  value={draft.sortOrder}
                  onChange={(e) =>
                    setDraft((d) => ({
                      ...d,
                      sortOrder: Number(e.target.value),
                    }))
                  }
                />
              </label>
              <label>
                사용 여부
                <select
                  data-testid="menu-use"
                  value={draft.useYn}
                  onChange={(e) =>
                    setDraft((d) => ({ ...d, useYn: e.target.value }))
                  }
                >
                  <option>Y</option>
                  <option>N</option>
                </select>
              </label>
              <label className={styles.check}>
                <input
                  data-testid="menu-mobile-visible"
                  type="checkbox"
                  checked={draft.mobileVisible}
                  onChange={(e) =>
                    setDraft((d) => ({ ...d, mobileVisible: e.target.checked }))
                  }
                />
                모바일 표시 (API 권한과 별개)
              </label>
              {confirm && (
                <div
                  data-testid="menu-disable-impact"
                  className={styles.confirm}
                >
                  <p>
                    메뉴 중지 시 기존 사용자 탭과 직접 API 접근이 차단됩니다.
                    마지막 관리자 복구 경로는 중지할 수 없습니다.
                  </p>
                  <Button
                    data-testid="menu-disable-cancel"
                    onClick={() => setConfirm(false)}
                  >
                    취소
                  </Button>
                </div>
              )}
              <Can perm={creating ? "UI-25.create" : "UI-25.update"}>
                <Button
                  data-testid="menu-save"
                  variant="primary"
                  pending={busy}
                  onClick={save}
                >
                  {confirm ? "영향 확인 후 저장" : "저장"}
                </Button>
              </Can>
            </>
          )}
          <h2>업무 알림 이메일</h2>
          <label className={styles.check}>
            <input
              data-testid="notification-mail-enabled"
              type="checkbox"
              checked={mail}
              onChange={(e) => setMail(e.target.checked)}
            />
            업무 이메일 채널 사용
          </label>
          <p>
            MFA·비밀번호 재설정 이메일과 시스템 내 알림은 이 설정으로 끄지
            않습니다.
          </p>
          <Can perm="UI-25.update">
            <Button
              data-testid="notification-mail-save"
              pending={busy}
              onClick={() =>
                void run(() =>
                  command("/api/admin/notification-channels", "PUT", {
                    businessEmailEnabled: mail,
                  }),
                )
              }
            >
              채널 저장
            </Button>
          </Can>
        </div>
      </div>
    </section>
  );
}
