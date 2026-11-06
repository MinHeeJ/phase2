import { useCallback, useState } from "react";
import { Button } from "../../shared/Controls";
import { can } from "../../shared/types";
import { useAdminRead } from "../admin/useAdminResource";
import {
  actions,
  all,
  get,
  write,
  type Permission,
  type SecurityRole,
} from "./client";
import styles from "./Security.module.css";

const labels = {
  inq: "조회",
  reg: "등록",
  mdfcn: "수정",
  del: "삭제",
  upld: "업로드",
  dwnld: "다운로드",
  cnfrm: "확정",
};
export default function RolePermissionsScreen() {
  const list = useAdminRead(
    useCallback(() => all<SecurityRole>("/api/admin/roles"), []),
  );
  const [role, setRole] = useState<SecurityRole | null>(null),
    [creating, setCreating] = useState(false),
    [matrix, setMatrix] = useState<Permission[]>([]),
    [original, setOriginal] = useState<Permission[]>([]),
    [reason, setReason] = useState(""),
    [copyName, setCopyName] = useState(""),
    [risk, setRisk] = useState(false),
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
  const select = async (id: number) => {
    const [selected, permissions] = await Promise.all([
      get<SecurityRole>(`/api/admin/roles/${id}`),
      get<{ permissions: Permission[] }>(`/api/admin/roles/${id}/permissions`),
    ]);
    setRole(selected);
    setMatrix(permissions.permissions);
    setOriginal(permissions.permissions);
    setCreating(false);
    setRisk(false);
    setReason("");
  };
  const create = () => {
    setRole({
      id: 0,
      name: "",
      description: "",
      useYn: "Y",
      dataScope: "SELF",
      sensitiveInquiry: false,
      partStandardChange: false,
      admin: false,
      builtIn: false,
    });
    setCreating(true);
    setMatrix([]);
    setOriginal([]);
    setRisk(false);
  };
  const disabled = !can("UI-24.update") || busy;
  return (
    <section
      data-screen-id="SCR-UI-24"
      data-testid="role-permissions-screen"
      className={styles.screen}
    >
      <h1>권한 관리</h1>
      {(error || list.error) && (
        <p role="alert">{error || list.error?.message}</p>
      )}
      {message && <p role="status">{message}</p>}
      <div className={styles.toolbar}>
        {can("UI-24.create") && (
          <Button data-testid="role-create-button" onClick={create}>
            역할 추가
          </Button>
        )}
        <Button
          data-testid="roles-refresh-button"
          onClick={() => void list.reload().catch(() => {})}
        >
          새로고침
        </Button>
      </div>
      {list.loading ? (
        <p role="status">불러오는 중…</p>
      ) : (
        <div className={styles.split}>
          <aside className={`${styles.panel} ${styles.list}`}>
            {list.data?.map((item) => (
              <Button
                key={item.id}
                data-testid={`role-row-${item.id}`}
                onClick={() => void run(() => select(item.id))}
              >
                {item.name} · {item.dataScope} ·{" "}
                {item.useYn === "Y" ? "사용" : "중지"}
              </Button>
            ))}
            {list.data?.length === 0 && <p>역할이 없습니다.</p>}
          </aside>
          {role ? (
            <div className={styles.panel} data-testid="role-detail-panel">
              <form
                className={styles.form}
                onSubmit={(e) => {
                  e.preventDefault();
                  void run(async () => {
                    const saved = await write<SecurityRole>(
                      creating
                        ? "/api/admin/roles"
                        : `/api/admin/roles/${role.id}`,
                      creating ? "POST" : "PATCH",
                      {
                        name: role.name,
                        description: role.description,
                        useYn: role.useYn,
                        dataScope: role.dataScope,
                        sensitiveInquiry: role.sensitiveInquiry,
                        partStandardChange: role.partStandardChange,
                        admin: role.admin,
                        riskConfirmed: risk,
                      },
                    );
                    await select(saved.id);
                  });
                }}
              >
                <label>
                  역할명
                  <input
                    data-testid="role-name-input"
                    required
                    maxLength={50}
                    value={role.name}
                    onChange={(e) => setRole({ ...role, name: e.target.value })}
                  />
                </label>
                <label>
                  설명
                  <textarea
                    data-testid="role-description-input"
                    maxLength={500}
                    value={role.description ?? ""}
                    onChange={(e) =>
                      setRole({ ...role, description: e.target.value })
                    }
                  />
                </label>
                <label>
                  데이터 범위
                  <select
                    data-testid="role-scope-select"
                    value={role.dataScope}
                    onChange={(e) =>
                      setRole({ ...role, dataScope: e.target.value })
                    }
                  >
                    {["ALL", "PART", "PROJECT", "SELF"].map((value) => (
                      <option key={value}>{value}</option>
                    ))}
                  </select>
                </label>
                <label>
                  사용 여부
                  <select
                    data-testid="role-use-select"
                    value={role.useYn}
                    onChange={(e) =>
                      setRole({ ...role, useYn: e.target.value })
                    }
                  >
                    <option value="Y">사용</option>
                    <option value="N">중지</option>
                  </select>
                </label>
                {(
                  ["sensitiveInquiry", "partStandardChange", "admin"] as const
                ).map((key) => (
                  <label key={key} className={styles.check}>
                    <input
                      type="checkbox"
                      data-testid={`role-${key === "sensitiveInquiry" ? "sensitive" : key === "partStandardChange" ? "part-standard" : "admin"}-checkbox`}
                      disabled={key === "admin" && role.builtIn}
                      checked={role[key]}
                      onChange={(e) =>
                        setRole({ ...role, [key]: e.target.checked })
                      }
                    />
                    {
                      {
                        sensitiveInquiry: "민감 보기",
                        partStandardChange: "파트 기준 변경",
                        admin: "관리자",
                      }[key]
                    }
                  </label>
                ))}
                <label className={styles.check}>
                  <input
                    type="checkbox"
                    data-testid="role-risk-confirm-checkbox"
                    checked={risk}
                    onChange={(e) => setRisk(e.target.checked)}
                  />
                  관리자 권한 부여 위험을 확인했습니다.
                </label>
                <Button
                  type="submit"
                  data-testid="role-save-button"
                  disabled={
                    busy || !can(`UI-24.${creating ? "create" : "update"}`)
                  }
                >
                  속성 저장
                </Button>
              </form>
              {!creating && (
                <>
                  <h2>화면별 7권한</h2>
                  <div className={styles.toolbar}>
                    <Button
                      data-testid="permissions-select-all-button"
                      disabled={disabled}
                      onClick={() =>
                        setMatrix((rows) =>
                          rows.map((row) => ({
                            ...row,
                            ...Object.fromEntries(
                              actions.map((action) => [
                                action,
                                row.screenId !== null &&
                                  row.supportedActions.includes(action),
                              ]),
                            ),
                          })),
                        )
                      }
                    >
                      현재 역할 전체 선택
                    </Button>
                    <Button
                      data-testid="permissions-clear-button"
                      disabled={disabled}
                      onClick={() =>
                        setMatrix((rows) =>
                          rows.map((row) => ({
                            ...row,
                            ...Object.fromEntries(
                              actions.map((action) => [action, false]),
                            ),
                          })),
                        )
                      }
                    >
                      초기화
                    </Button>
                    <Button
                      data-testid="permissions-cancel-button"
                      onClick={() => setMatrix(original)}
                    >
                      변경 취소
                    </Button>
                  </div>
                  <div className={styles.scroll}>
                    <table className={styles.table}>
                      <thead>
                        <tr>
                          <th>화면</th>
                          {actions.map((action) => (
                            <th key={action}>{labels[action]}</th>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {matrix.map((row) => (
                          <tr
                            key={row.menuId}
                            data-testid={`permission-row-${row.menuId}`}
                          >
                            <th>{row.name}</th>
                            {actions.map((action) => (
                              <td key={action}>
                                <input
                                  type="checkbox"
                                  aria-label={`${row.name} ${labels[action]}`}
                                  data-testid={`permission-${row.menuId}-${action}`}
                                  disabled={
                                    disabled ||
                                    !row.screenId ||
                                    !row.supportedActions.includes(action) ||
                                    (!role.admin &&
                                      [
                                        "UI-23",
                                        "UI-24",
                                        "UI-25",
                                        "UI-27",
                                      ].includes(row.screenId))
                                  }
                                  checked={row[action]}
                                  onChange={(e) =>
                                    setMatrix((rows) =>
                                      rows.map((value) =>
                                        value.menuId === row.menuId
                                          ? {
                                              ...value,
                                              [action]: e.target.checked,
                                            }
                                          : value,
                                      ),
                                    )
                                  }
                                />
                                <small>
                                  {row[action] !==
                                  original.find(
                                    (value) => value.menuId === row.menuId,
                                  )?.[action]
                                    ? "변경"
                                    : ""}
                                </small>
                              </td>
                            ))}
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                  <form
                    className={styles.form}
                    onSubmit={(e) => {
                      e.preventDefault();
                      void run(async () => {
                        await write(
                          `/api/admin/roles/${role.id}/permissions`,
                          "PUT",
                          {
                            reason,
                            permissions: matrix.map((row) => ({
                              menuId: row.menuId,
                              ...Object.fromEntries(
                                actions.map((action) => [action, row[action]]),
                              ),
                            })),
                          },
                        );
                        await select(role.id);
                      });
                    }}
                  >
                    <label>
                      변경 사유
                      <textarea
                        data-testid="permissions-reason-input"
                        required
                        maxLength={500}
                        value={reason}
                        onChange={(e) => setReason(e.target.value)}
                      />
                    </label>
                    <Button
                      type="submit"
                      data-testid="permissions-save-button"
                      disabled={disabled || !reason.trim()}
                    >
                      권한 저장
                    </Button>
                  </form>
                  {can("UI-24.create") && (
                    <form
                      className={styles.form}
                      onSubmit={(e) => {
                        e.preventDefault();
                        void run(async () => {
                          const copied = await write<SecurityRole>(
                            `/api/admin/roles/${role.id}/copy`,
                            "POST",
                            { name: copyName, description: role.description },
                          );
                          await select(copied.id);
                          setCopyName("");
                        });
                      }}
                    >
                      <h2>역할 복사</h2>
                      <p>
                        범위·7권한은 복사하고 관리자·기본·민감·파트 기준 특권은
                        제거합니다.
                      </p>
                      <label>
                        새 역할명
                        <input
                          data-testid="role-copy-name-input"
                          required
                          maxLength={50}
                          value={copyName}
                          onChange={(e) => setCopyName(e.target.value)}
                        />
                      </label>
                      <Button
                        data-testid="role-copy-button"
                        type="submit"
                        disabled={busy}
                      >
                        복사
                      </Button>
                    </form>
                  )}
                </>
              )}
            </div>
          ) : (
            <p>역할을 선택하세요.</p>
          )}
        </div>
      )}
    </section>
  );
}
