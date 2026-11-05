export type ApiResponse<T = void> = {
  success: true;
  meta: Record<string, unknown>;
} & ([T] extends [void] ? { data?: T } : { data: T });
export interface ApiError {
  success: false;
  meta: Record<string, unknown>;
  code: string;
  message: string;
  field?: string | null;
  requestId: string;
}
export interface PageResult<T> {
  items: T[];
  total: number;
  page: number;
  size: 20 | 50 | 100;
}
export type DataScope = "ALL" | "PART" | "PROJECT" | "SELF";
export interface CurrentUser {
  userId: number | null;
  name: string;
  roleIds: number[];
  roles: string[];
}
export const currentUser: CurrentUser = {
  userId: null,
  name: "",
  roleIds: [],
  roles: [],
};
export const currentScope: DataScope = "SELF";
let effectivePermissions: Record<string, Record<string, boolean>> = {};
export function setIdentity(
  identity: CurrentUser | null,
  permissions: Record<string, Record<string, boolean>> = {},
) {
  Object.assign(
    currentUser,
    identity ?? { userId: null, name: "", roleIds: [], roles: [] },
  );
  effectivePermissions = permissions;
}
export function can(permission: string): boolean {
  const [screen, action] = permission.split(".");
  const column: Record<string, string> = {
    read: "inq",
    view: "inq",
    ask: "inq",
    recommend: "inq",
    create: "reg",
    update: "mdfcn",
    decision: "mdfcn",
    receipt: "mdfcn",
    remind: "mdfcn",
    delete: "del",
    upload: "upld",
    download: "dwnld",
    export: "dwnld",
    confirm: "cnfrm",
    cancel: "cnfrm",
    reopen: "cnfrm",
    approve: "cnfrm",
  };
  return effectivePermissions[screen]?.[column[action] ?? action] === true;
}
