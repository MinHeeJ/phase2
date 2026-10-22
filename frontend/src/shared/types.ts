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
  name: "기본 사용자",
  roleIds: [1],
};
export const currentScope: DataScope = "ALL";
export function can(_permission: string): boolean {
  return true;
}
