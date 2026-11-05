import { afterEach, expect, it, vi } from "vitest";
import { createUploadValidation } from "../pages/uploadsops/client";
import { validateBusinessPlanUpload } from "../pages/businessplan2/client";
import { validateFinanceEntriesUpload } from "../pages/financeentries/client";
import { validatePriorProfitUpload } from "../pages/priorprofit/client";
import { validateDispatchAttendanceUpload } from "../pages/dispatchattendance2/client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }));
const uploads = [
  {
    path: "/api/uploads/B.7/validate",
    send: (file: File) => createUploadValidation("B.7", file),
    fields: {},
  },
  {
    path: "/api/uploads/validate",
    send: (file: File) => createUploadValidation("B.1", file, "2026-11"),
    fields: { form_type: "B.1", month: "2026-11" },
  },
  {
    path: "/api/uploads/business-plan/validate",
    send: validateBusinessPlanUpload,
    fields: {},
  },
  {
    path: "/api/uploads/finance-entries/validate",
    send: validateFinanceEntriesUpload,
    fields: {},
  },
  {
    path: "/api/uploads/prior-profit/validate",
    send: validatePriorProfitUpload,
    fields: {},
  },
  {
    path: "/api/uploads/dispatch-attendance/validate",
    send: (file: File) => validateDispatchAttendanceUpload(file, "2026-11", 71),
    fields: { month: "2026-11", projectId: "71" },
  },
];

afterEach(() => vi.unstubAllGlobals());
it.each(uploads)(
  "uses the server CSRF contract without changing multipart fields at $path",
  async ({ path, send, fields }) => {
    const fetcher = vi.fn(async (url: string, _init?: RequestInit) => {
      if (url === "/api/auth/csrf")
        return response({ headerName: "X-CSRF-TOKEN", csrfToken: "test-csrf" });
      if (url === path)
        return response({ upload_id: 71, row_count: 0, error_count: 0 });
      if (url === "/api/uploads/71/issues?page=0&size=100&sort=row,asc")
        return response({ items: [], total: 0 });
      throw new Error(`Unexpected request ${url}`);
    });
    vi.stubGlobal("fetch", fetcher);
    const file = new File([""], "upload.xlsx");
    await send(file);
    expect(fetcher.mock.calls[0][0]).toBe("/api/auth/csrf");
    const request = fetcher.mock.calls.find(([url]) => url === path)![1]!;
    expect(request.method).toBe("POST");
    expect(request.credentials).toBe("same-origin");
    const headers = new Headers(request.headers);
    expect(headers.get("X-CSRF-TOKEN")).toBe("test-csrf");
    expect(headers.has("Content-Type")).toBe(false);
    const body = request.body as FormData;
    expect(body).toBeInstanceOf(FormData);
    expect(body.get("file")).toBe(file);
    for (const [key, value] of Object.entries(fields))
      expect(body.get(key)).toBe(value);
    expect([...body.keys()].sort()).toEqual(
      ["file", ...Object.keys(fields)].sort(),
    );
  },
);
it.each(uploads)(
  "does not submit $path when CSRF lookup fails",
  async ({ send }) => {
    const fetcher = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ success: false, code: "FORBIDDEN" }), {
        status: 403,
      }),
    );
    vi.stubGlobal("fetch", fetcher);
    await expect(send(new File([""], "upload.xlsx"))).rejects.toMatchObject({
      status: 403,
    });
    expect(fetcher).toHaveBeenCalledOnce();
    expect(fetcher.mock.calls[0][0]).toBe("/api/auth/csrf");
  },
);
