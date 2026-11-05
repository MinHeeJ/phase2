import { useEffect, useState } from "react";
import { api, ApiFailure, readData } from "../../shared/api";

export interface Role {
  id: number;
  name: string;
  description: string | null;
  useYn: "Y" | "N";
  dataScope: "ALL" | "PART" | "PROJECT" | "SELF";
  sensitiveView: boolean;
  partStandardChange: boolean;
  admin: boolean;
  builtIn: boolean;
}
export interface Permission {
  menuId: number;
  name: string;
  screenId: string | null;
  parentId: number | null;
  inq: boolean;
  reg: boolean;
  mdfcn: boolean;
  del: boolean;
  upld: boolean;
  dwnld: boolean;
  cnfrm: boolean;
}
export interface Menu {
  id: number;
  name: string;
  screenId: string | null;
  parentId: number | null;
  icon: string | null;
  sortOrder: number;
  useYn: "Y" | "N";
  mobileVisible: boolean;
}
export interface User {
  id: number;
  name: string;
  loginId: string;
  email: string;
  phone: string | null;
  basePartId: number | null;
  partIds: number[];
  projectIds: number[];
  developerId: number | null;
  roleIds: number[];
  status: "ACTIVE" | "LOCKED" | "DISABLED";
  resignDate: string | null;
  mfaRequired: boolean;
  lastLoginAt: string | null;
}
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}
export interface History {
  id: number;
  createdAt: string;
  userId: number | null;
  loginId: string | null;
  type: string;
  screenId: string | null;
  target: string | null;
  result: string;
  ip: string | null;
}
export const actions = [
  "inq",
  "reg",
  "mdfcn",
  "del",
  "upld",
  "dwnld",
  "cnfrm",
] as const;
export const actionLabels = [
  "조회",
  "등록",
  "수정",
  "삭제",
  "업로드",
  "다운로드",
  "확정",
];
export function useResource<T>(path: string) {
  const [data, setData] = useState<T | null>(null),
    [error, setError] = useState<ApiFailure | null>(null),
    [loading, setLoading] = useState(true),
    [revision, setRevision] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setData(null);
    setLoading(true);
    setError(null);
    void readData<T>(path, controller.signal)
      .then((value) => {
        if (!controller.signal.aborted) setData(value);
      })
      .catch((e) => {
        if (!controller.signal.aborted) setError(e as ApiFailure);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [path, revision]);
  return { data, error, loading, refresh: () => setRevision((n) => n + 1) };
}
export async function command<T = void>(
  path: string,
  method: string,
  body?: unknown,
): Promise<T> {
  return (
    await api<T>(path, {
      method,
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    })
  ).data as T;
}
export async function download(path: string) {
  const response = await fetch(path, { credentials: "same-origin" });
  if (!response.ok)
    throw new ApiFailure(
      "내려받을 권한이 없거나 파일 생성에 실패했습니다.",
      response.status,
      response.headers.get("X-Request-Id"),
    );
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement("a");
  link.href = url;
  link.download = "export.xlsx";
  link.click();
  URL.revokeObjectURL(url);
}
export const permissionChanged = () =>
  window.dispatchEvent(new Event("sicost:permissions-changed"));
