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
  userId: null;
  name: string;
  roleIds: number[];
}
export const currentUser: CurrentUser = {
  userId: null,
  name: "",
  roleIds: [],
};
export const currentScope: DataScope = "ALL";
let effectivePermissions = new Map<string, boolean>();
export function setSecurity(
  name: string,
  roles: number[],
  rows: ({ screenId: string } & Record<string, boolean | string>)[],
) {
  currentUser.name = name;
  currentUser.roleIds = roles;
  effectivePermissions = new Map(
    rows.flatMap((row) =>
      Object.entries(row)
        .filter(([, value]) => typeof value === "boolean")
        .map(
          ([action, value]) =>
            [`${row.screenId}.${action}`, value === true] as const,
        ),
    ),
  );
}
export function clearSecurity() {
  currentUser.name = "";
  currentUser.roleIds = [];
  effectivePermissions.clear();
}
export function can(permission: string): boolean {
  return effectivePermissions.get(permission) === true;
}
