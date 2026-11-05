import { useEffect, useState } from "react";
import { Button, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import { command, useResource, type Menu } from "./client";
import styles from "../authentication/SecurityScreens.module.css";

export default function MenuManagementScreen() {
  const source = useResource<Menu[]>("/api/admin/menus");
  const [selected, setSelected] = useState<Menu | null>(null),
    [creating, setCreating] = useState(false);
  const [order, setOrder] = useState<Menu[]>([]),
    [preview, setPreview] = useState(false);
  const [pending, setPending] = useState(false),
    [error, setError] = useState<ApiFailure | null>(null),
    [message, setMessage] = useState("");
  useEffect(() => {
    setOrder(source.data ?? []);
    setPreview(false);
  }, [source.data]);
  const move = (delta: number) => {
    if (!selected) return;
    const index = order.findIndex((m) => m.id === selected.id),
      next = index + delta;
    if (
      next < 0 ||
      next >= order.length ||
      order[next].parentId !== selected.parentId
    )
      return;
    const updated = [...order];
    [updated[index], updated[next]] = [updated[next], updated[index]];
    setOrder(updated.map((m, i) => ({ ...m, sortOrder: i })));
    setPreview(false);
  };
  return (
    <section
      className={styles.workspace}
      data-testid="menu-management-screen"
      data-screen-id="UI-25"
    >
      <h1>메뉴 관리</h1>
      <ErrorBanner error={source.error ?? error} onRetry={source.refresh} />
      {source.loading && <Skeleton label="메뉴 불러오는 중" />}
      {message && <p role="status">{message}</p>}
      <div className={styles.layout}>
        <aside className={styles.panel}>
          <Button
            data-testid="menu-add"
            onClick={() => {
              setSelected(null);
              setCreating(true);
            }}
          >
            메뉴 추가
          </Button>
          <ul className={styles.list}>
            {order.map((m) => (
              <li key={m.id} data-testid={`menu-row-${m.id}`}>
                <Button
                  data-testid={`menu-select-${m.id}`}
                  onClick={() => {
                    setSelected(m);
                    setCreating(false);
                  }}
                >
                  {m.parentId !== null ? "↳ " : ""}
                  {m.name} · {m.useYn}
                </Button>
              </li>
            ))}
          </ul>
          {source.data?.length === 0 && <p>등록된 메뉴가 없습니다.</p>}
        </aside>
        <div className={styles.panel}>
          {selected || creating ? (
            <MenuEditor
              key={creating ? "new" : selected!.id}
              menu={creating ? null : selected}
              menus={source.data ?? []}
              onSaved={() => {
                source.refresh();
                setMessage(
                  "메뉴를 저장했습니다. 변경된 권한은 다음 요청부터 반영됩니다.",
                );
              }}
            />
          ) : (
            <p>메뉴를 선택하세요.</p>
          )}
          {selected && (
            <div className={styles.toolbar}>
              <Button data-testid="menu-move-up" onClick={() => move(-1)}>
                위로
              </Button>
              <Button data-testid="menu-move-down" onClick={() => move(1)}>
                아래로
              </Button>
              <Button
                data-testid="menu-preview"
                onClick={() => setPreview(true)}
              >
                저장 전 미리보기
              </Button>
            </div>
          )}
          {preview && (
            <section data-testid="menu-preview-panel">
              <h2>정렬 미리보기</h2>
              <ol>
                {order.map((m) => (
                  <li key={m.id}>{m.name}</li>
                ))}
              </ol>
              <div className={styles.toolbar}>
                <Button
                  data-testid="menu-order-save"
                  disabled={pending}
                  onClick={async () => {
                    setPending(true);
                    setError(null);
                    try {
                      await command("/api/admin/menus/reorder", "PUT", {
                        items: order.map(({ id, parentId, sortOrder }) => ({
                          menuId: id,
                          parentId,
                          sortOrder,
                        })),
                      });
                      source.refresh();
                      setMessage("메뉴 순서를 저장했습니다.");
                    } catch (e) {
                      setError(e as ApiFailure);
                    } finally {
                      setPending(false);
                    }
                  }}
                >
                  순서 저장
                </Button>
                <Button
                  data-testid="menu-order-cancel"
                  onClick={() => {
                    setOrder(source.data ?? []);
                    setPreview(false);
                  }}
                >
                  취소
                </Button>
              </div>
            </section>
          )}
        </div>
      </div>
    </section>
  );
}
function MenuEditor({
  menu,
  menus,
  onSaved,
}: {
  menu: Menu | null;
  menus: Menu[];
  onSaved: () => void;
}) {
  const [draft, setDraft] = useState<Omit<Menu, "id">>(
    menu ?? {
      name: "",
      screenId: null,
      parentId: null,
      icon: null,
      sortOrder: 0,
      useYn: "Y",
      mobileVisible: true,
    },
  );
  const [confirmed, setConfirmed] = useState(false),
    [error, setError] = useState<ApiFailure | null>(null),
    [pending, setPending] = useState(false);
  const set = <K extends keyof typeof draft>(
    key: K,
    value: (typeof draft)[K],
  ) => setDraft((old) => ({ ...old, [key]: value }));
  return (
    <form
      className={styles.form}
      data-testid="menu-editor"
      onSubmit={async (e) => {
        e.preventDefault();
        setPending(true);
        setError(null);
        try {
          const {
            name,
            screenId,
            parentId,
            icon,
            sortOrder,
            useYn,
            mobileVisible,
          } = draft;
          await command(
            menu ? `/api/admin/menus/${menu.id}` : "/api/admin/menus",
            menu ? "PATCH" : "POST",
            {
              name,
              parentId,
              icon,
              sortOrder,
              useYn,
              mobileVisible,
              ...(!menu ? { screenId } : {}),
            },
          );
          onSaved();
        } catch (e) {
          setError(e as ApiFailure);
        } finally {
          setPending(false);
        }
      }}
    >
      <label>
        표시명
        <input
          data-testid="menu-name"
          required
          maxLength={50}
          value={draft.name}
          onChange={(e) => set("name", e.target.value)}
        />
      </label>
      <label>
        고정 화면 ID
        {menu ? (
          <input
            data-testid="menu-screen-id"
            readOnly
            value={menu.screenId ?? "그룹"}
          />
        ) : (
          <select
            data-testid="menu-screen-id"
            value={draft.screenId ?? ""}
            onChange={(e) => set("screenId", e.target.value || null)}
          >
            <option value="">그룹</option>
            {menus
              .filter((m) => m.screenId !== null)
              .map((m) => (
                <option key={m.id} value={m.screenId!}>
                  {m.screenId} · {m.name}
                </option>
              ))}
          </select>
        )}
      </label>
      <label>
        상위 메뉴
        <select
          data-testid="menu-parent"
          value={draft.parentId ?? ""}
          onChange={(e) =>
            set("parentId", e.target.value ? Number(e.target.value) : null)
          }
        >
          <option value="">최상위</option>
          {menus
            .filter((m) => m.screenId === null && m.id !== menu?.id)
            .map((m) => (
              <option key={m.id} value={m.id}>
                {m.name}
              </option>
            ))}
        </select>
      </label>
      <label>
        아이콘
        <input
          data-testid="menu-icon"
          maxLength={30}
          value={draft.icon ?? ""}
          onChange={(e) => set("icon", e.target.value || null)}
        />
      </label>
      <label>
        정렬 순서
        <input
          data-testid="menu-order"
          type="number"
          min={0}
          step={1}
          required
          value={draft.sortOrder}
          onChange={(e) => set("sortOrder", Number(e.target.value))}
        />
      </label>
      <label>
        사용 여부
        <select
          data-testid="menu-use"
          value={draft.useYn}
          onChange={(e) => {
            set("useYn", e.target.value as "Y" | "N");
            setConfirmed(false);
          }}
        >
          <option value="Y">사용</option>
          <option value="N">사용 중지</option>
        </select>
      </label>
      <label className={styles.check}>
        <input
          data-testid="menu-mobile"
          type="checkbox"
          checked={draft.mobileVisible}
          onChange={(e) => set("mobileVisible", e.target.checked)}
        />
        모바일 표시 (권한과 별도)
      </label>
      {draft.useYn === "N" && (
        <label className={styles.check}>
          <input
            data-testid="menu-disable-confirm"
            type="checkbox"
            required
            checked={confirmed}
            onChange={(e) => setConfirmed(e.target.checked)}
          />
          {draft.name} 및 하위 화면 접근과 열린 탭이 차단됩니다. 영향을
          확인했습니다.
        </label>
      )}
      <ErrorBanner error={error} />
      <div className={styles.toolbar}>
        <Button data-testid="menu-save" type="submit" disabled={pending}>
          저장
        </Button>
        <Button
          data-testid="menu-cancel"
          type="button"
          onClick={() => {
            if (menu) setDraft(menu);
            setConfirmed(false);
          }}
        >
          취소
        </Button>
      </div>
    </form>
  );
}
