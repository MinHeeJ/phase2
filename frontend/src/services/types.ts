export type ApiResponse<T = undefined> = {
  success: true;
  meta: Record<string, unknown>;
} & ([T] extends [undefined] ? { data?: T } : { data: T });
export type ApiError = {
  success: false;
  meta: Record<string, unknown>;
  code: string;
  message: string;
  field?: string | null;
  requestId: string;
};
export type PageData<T> = {
  items: T[];
  total: number;
  page: number;
  size: 20 | 50 | 100;
};
export type DataScope = "ALL" | "PART" | "PROJECT" | "SELF";
export type MenuNode = {
  menuId: number;
  name: string;
  screenId: string | null;
  icon: string | null;
  mobile: boolean;
  children: MenuNode[];
};
