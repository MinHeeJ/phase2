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
}
export const currentUser: CurrentUser = {
  userId: null,
  name: "",
  roleIds: [],
};
export const currentScope: DataScope = "SELF";
let effectivePermissions: Record<string, boolean> = {};
export function setPermissions(permissions: Record<string, boolean>) {
  effectivePermissions = { ...permissions };
}
export function can(permission: string): boolean {
  return effectivePermissions[permission] === true;
}
