import { useCallback, useState } from "react";
import { Button } from "../../shared/Controls";
import { can } from "../../shared/types";
import { useAdminRead } from "../admin/useAdminResource";
import { screenIds } from "../shared/registry";
import { all, write, type SecurityMenu } from "./client";
import styles from "./Security.module.css";

export default function MenuManagementScreen() {
  const list = useAdminRead(
    useCallback(() => all<SecurityMenu>("/api/admin/menus"), []),
  );
  const [selected, setSelected] = useState<SecurityMenu | null>(null),
    [creating, setCreating] = useState(false),
    [preview, setPreview] = useState<SecurityMenu[] | null>(null),
    [confirmed, setConfirmed] = useState(false),
    [error, setError] = useState(""),
    [message, setMessage] = useState(""),
    [busy, setBusy] = useState(false);
  const run = async (action: () => Promise<unknown>) => {
    setBusy(true);
    setError("");
    try {
      await action();
      await list.reload();
      setMessage("저장했습니다.");
    } catch (e) {
      setError(e instanceof Error ? e.message : "요청 실패");
    } finally {
      setBusy(false);
    }
  };
  const rows = preview ?? list.data ?? [];
  const move = (id: number, direction: number) => {
    const row = rows.find((value) => value.id === id);
    if (!row) return;
    const siblings = rows
      .filter((value) => value.parentId === row.parentId)
      .sort((a, b) => a.sortOrder - b.sortOrder || a.id - b.id);
    const index = siblings.findIndex((value) => value.id === id),
      target = index + direction;
    if (target < 0 || target >= siblings.length) return;
    [siblings[index], siblings[target]] = [siblings[target], siblings[index]];
    setPreview(
      rows.map((value) =>
        value.parentId === row.parentId
          ? {
              ...value,
              sortOrder: siblings.findIndex(
                (sibling) => sibling.id === value.id,
              ),
            }
          : value,
      ),
    );
  };
  const tree = (
    parentId: number | null,
    ancestors: number[] = [],
  ): React.ReactNode =>
    rows
      .filter((row) => row.parentId === parentId && !ancestors.includes(row.id))
      .sort((a, b) => a.sortOrder - b.sortOrder || a.id - b.id)
      .map((row) => (
        <div
          key={row.id}
          data-testid={`menu-row-${row.id}`}
          style={{ paddingInlineStart: ancestors.length ? "1rem" : 0 }}
        >
          <div className={styles.toolbar}>
            <Button
              data-testid={`menu-select-${row.id}`}
              onClick={() => {
                setSelected({ ...row });
                setCreating(false);
                setConfirmed(false);
              }}
            >
              {row.name} {row.screenId ?? "그룹"} ·{" "}
              {row.useYn === "Y" ? "사용" : "중지"}
            </Button>
            {can("UI-25.update") && (
              <>
                <Button
                  data-testid={`menu-up-${row.id}`}
                  aria-label={`${row.name} 위로`}
                  onClick={() => move(row.id, -1)}
                >
                  ↑
                </Button>
                <Button
                  data-testid={`menu-down-${row.id}`}
                  aria-label={`${row.name} 아래로`}
                  onClick={() => move(row.id, 1)}
                >
                  ↓
                </Button>
              </>
            )}
          </div>
          {tree(row.id, [...ancestors, row.id])}
        </div>
      ));
  return (
    <section
      data-screen-id="SCR-UI-25"
      data-testid="menu-management-screen"
      className={styles.screen}
    >
      <h1>메뉴 관리</h1>
      {(error || list.error) && (
        <p role="alert">{error || list.error?.message}</p>
      )}
      {message && <p role="status">{message}</p>}
      <div className={styles.toolbar}>
        {can("UI-25.create") && (
          <Button
            data-testid="menu-create-button"
            onClick={() => {
              setCreating(true);
              setSelected({
                id: 0,
                name: "",
                screenId: null,
                parentId: null,
                sortOrder: 0,
                icon: null,
                useYn: "Y",
                mobileVisible: true,
              });
              setConfirmed(false);
            }}
          >
            메뉴·그룹 추가
          </Button>
        )}
        {preview && (
          <>
            <p role="status">정렬 미리보기 · 아직 저장하지 않았습니다.</p>
            <Button
              data-testid="menu-order-save-button"
              disabled={busy}
              onClick={() =>
                void run(async () => {
                  await write("/api/admin/menus/reorder", "PUT", {
                    items: preview.map((row) => ({
                      id: row.id,
                      parentId: row.parentId,
                      sortOrder: row.sortOrder,
                    })),
                  });
                  setPreview(null);
                })
              }
            >
              정렬 저장
            </Button>
            <Button
              data-testid="menu-order-cancel-button"
              onClick={() => setPreview(null)}
            >
              미리보기 취소
            </Button>
          </>
        )}
      </div>
      {list.loading ? (
        <p role="status">불러오는 중…</p>
      ) : (
        <div className={styles.split}>
          <aside className={styles.panel}>
            {tree(null)}
            {rows.length === 0 && <p>메뉴가 없습니다.</p>}
          </aside>
          {selected ? (
            <form
              data-testid="menu-detail-panel"
              className={`${styles.panel} ${styles.form}`}
              onSubmit={(e) => {
                e.preventDefault();
                void run(async () => {
                  const body = {
                    name: selected.name,
                    icon: selected.icon,
                    parentId: selected.parentId,
                    sortOrder: selected.sortOrder,
                    useYn: selected.useYn,
                    mobileVisible: selected.mobileVisible,
                    ...(creating
                      ? { screenId: selected.screenId }
                      : { impactConfirmed: confirmed }),
                  };
                  const saved = await write<SecurityMenu>(
                    creating
                      ? "/api/admin/menus"
                      : `/api/admin/menus/${selected.id}`,
                    creating ? "POST" : "PATCH",
                    body,
                  );
                  setSelected(saved);
                  setCreating(false);
                  setConfirmed(false);
                });
              }}
            >
              <label>
                표시명
                <input
                  data-testid="menu-name-input"
                  value={selected.name}
                  required
                  maxLength={50}
                  onChange={(e) =>
                    setSelected({ ...selected, name: e.target.value })
                  }
                />
              </label>
              <label>
                아이콘
                <input
                  data-testid="menu-icon-input"
                  value={selected.icon ?? ""}
                  maxLength={30}
                  onChange={(e) =>
                    setSelected({ ...selected, icon: e.target.value || null })
                  }
                />
              </label>
              <label>
                고정 화면 ID
                {creating ? (
                  <select
                    data-testid="menu-screen-select"
                    value={selected.screenId ?? ""}
                    onChange={(e) =>
                      setSelected({
                        ...selected,
                        screenId: e.target.value || null,
                      })
                    }
                  >
                    <option value="">그룹 (화면 없음)</option>
                    {screenIds
                      .filter((id) => !rows.some((row) => row.screenId === id))
                      .map((id) => (
                        <option key={id}>{id}</option>
                      ))}
                  </select>
                ) : (
                  <input
                    data-testid="menu-screen-readonly"
                    readOnly
                    value={selected.screenId ?? "그룹"}
                  />
                )}
              </label>
              <label>
                상위 메뉴
                <select
                  data-testid="menu-parent-select"
                  value={selected.parentId ?? ""}
                  onChange={(e) =>
                    setSelected({
                      ...selected,
                      parentId: e.target.value ? Number(e.target.value) : null,
                    })
                  }
                >
                  <option value="">최상위</option>
                  {rows
                    .filter(
                      (row) => row.id !== selected.id && row.screenId === null,
                    )
                    .map((row) => (
                      <option value={row.id} key={row.id}>
                        {row.name}
                      </option>
                    ))}
                </select>
              </label>
              <label>
                표시 순서
                <input
                  data-testid="menu-order-input"
                  type="number"
                  min={0}
                  value={selected.sortOrder}
                  onChange={(e) =>
                    setSelected({
                      ...selected,
                      sortOrder: Number(e.target.value),
                    })
                  }
                />
              </label>
              <label>
                사용 여부
                <select
                  data-testid="menu-use-select"
                  value={selected.useYn}
                  onChange={(e) => {
                    setSelected({ ...selected, useYn: e.target.value });
                    setConfirmed(false);
                  }}
                >
                  <option value="Y">사용</option>
                  <option value="N">중지</option>
                </select>
              </label>
              <label className={styles.check}>
                <input
                  data-testid="menu-mobile-checkbox"
                  type="checkbox"
                  checked={selected.mobileVisible}
                  onChange={(e) =>
                    setSelected({
                      ...selected,
                      mobileVisible: e.target.checked,
                    })
                  }
                />
                모바일 노출 (접근 권한과 별도)
              </label>
              {!creating && selected.useYn === "N" && (
                <div
                  className={styles.confirm}
                  data-testid="menu-disable-confirmation"
                >
                  <p>
                    이 메뉴와 하위 메뉴를 사용하는 모든 사용자의 접근에 영향을
                    줍니다. 복구 경로를 모두 끌 수 없습니다.
                  </p>
                  <label className={styles.check}>
                    <input
                      data-testid="menu-impact-checkbox"
                      type="checkbox"
                      checked={confirmed}
                      onChange={(e) => setConfirmed(e.target.checked)}
                    />
                    중지 영향을 확인했습니다.
                  </label>
                </div>
              )}
              <Button
                type="submit"
                data-testid="menu-save-button"
                disabled={
                  busy ||
                  !can(`UI-25.${creating ? "create" : "update"}`) ||
                  (!creating && selected.useYn === "N" && !confirmed)
                }
              >
                저장
              </Button>
              <Button
                data-testid="menu-edit-cancel-button"
                onClick={() => {
                  setSelected(null);
                  setCreating(false);
                }}
              >
                취소
              </Button>
              {creating && (
                <p>
                  새 메뉴는 관리자 포함 모든 역할의 7권한이 없음으로 생성됩니다.
                </p>
              )}
            </form>
          ) : (
            <p>설정할 메뉴를 선택하세요.</p>
          )}
        </div>
      )}
    </section>
  );
}
