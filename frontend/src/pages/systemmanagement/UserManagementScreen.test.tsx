import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import UserManagementScreen from "./UserManagementScreen";

const reply = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }));
let fetcher: ReturnType<typeof vi.fn>;
let roleUnavailable: boolean;

beforeEach(() => {
  roleUnavailable = false;
  fetcher = vi.fn(async (path: string) => {
    if (path === "/api/sales-parts/options")
      return reply([{ id: "731", name: "검증 영업 파트", hq: false }]);
    if (path === "/api/admin/roles") {
      if (roleUnavailable)
        return new Response(
          JSON.stringify({
            success: false,
            message: "역할을 조회할 수 없습니다.",
            code: "FORBIDDEN",
          }),
          { status: 403 },
        );
      return reply([{ id: 837, name: "검증 역할", useYn: "Y" }]);
    }
    if (path.startsWith("/api/admin/users/export?"))
      return new Response(
        new Blob(["export"], {
          type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        }),
      );
    if (path.startsWith("/api/admin/users?")) {
      const query = new URLSearchParams(path.split("?")[1]);
      return reply({
        items: [],
        total: 101,
        page: Number(query.get("page")),
        size: 50,
      });
    }
    throw new Error(`Unexpected request: ${path}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => vi.unstubAllGlobals());

const userQueries = () =>
  fetcher.mock.calls
    .map(([path]) => String(path))
    .filter((path) => path.startsWith("/api/admin/users?"))
    .map((path) => new URLSearchParams(path.split("?")[1]));

async function selectFilters() {
  await screen.findByRole("option", { name: "검증 영업 파트" });
  await screen.findByRole("option", { name: "검증 역할" });
  fireEvent.change(screen.getByLabelText("파트", { exact: true }), {
    target: { value: "731" },
  });
  fireEvent.change(screen.getByLabelText("역할", { exact: true }), {
    target: { value: "837" },
  });
  fireEvent.change(screen.getByLabelText("이름", { exact: true }), {
    target: { value: "검색 이름" },
  });
  fireEvent.click(screen.getByTestId("user-search-submit"));
  await waitFor(() => expect(userQueries().at(-1)?.get("roleId")).toBe("837"));
}

describe("user administration API bindings", () => {
  it("loads real option responses and retains submitted filters across paging", async () => {
    render(<UserManagementScreen />);
    await selectFilters();
    expect(userQueries().at(-1)?.get("partId")).toBe("731");
    expect(userQueries().at(-1)?.get("name")).toBe("검색 이름");
    fireEvent.click(screen.getByTestId("users-next"));
    await waitFor(() => expect(userQueries().at(-1)?.get("page")).toBe("2"));
    expect(userQueries().at(-1)?.get("partId")).toBe("731");
    expect(userQueries().at(-1)?.get("roleId")).toBe("837");

    fireEvent.change(screen.getByTestId("user-filter-part"), {
      target: { value: "" },
    });
    fireEvent.change(screen.getByTestId("user-filter-role"), {
      target: { value: "" },
    });
    fireEvent.click(screen.getByTestId("user-search-submit"));
    await waitFor(() => expect(userQueries().at(-1)?.get("page")).toBe("1"));
    expect(userQueries().at(-1)?.get("partId")).toBe("");
    expect(userQueries().at(-1)?.get("roleId")).toBe("");
    expect(screen.getByTestId("user-management-screen")).toHaveAttribute(
      "data-screen-id",
      "UI-23",
    );
  });

  it("exports the submitted filters rather than an unsaved search draft", async () => {
    vi.stubGlobal(
      "URL",
      class extends URL {
        static createObjectURL = vi.fn(() => "blob:test-export");
        static revokeObjectURL = vi.fn();
      },
    );
    const click = vi
      .spyOn(HTMLAnchorElement.prototype, "click")
      .mockImplementation(() => undefined);
    try {
      render(<UserManagementScreen />);
      await selectFilters();
      fireEvent.change(screen.getByTestId("user-filter-part"), {
        target: { value: "" },
      });
      fireEvent.click(screen.getByTestId("user-export"));
      await waitFor(() => expect(click).toHaveBeenCalledOnce());
      const path = fetcher.mock.calls
        .map(([value]) => String(value))
        .find((value) => value.startsWith("/api/admin/users/export?"))!;
      const query = new URLSearchParams(path.split("?")[1]);
      expect(query.get("partId")).toBe("731");
      expect(query.get("roleId")).toBe("837");
      expect(query.has("page")).toBe(false);
    } finally {
      click.mockRestore();
    }
  });

  it("shows option permission failures and retries without inventing roles", async () => {
    roleUnavailable = true;
    render(<UserManagementScreen />);
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("역할을 조회할 수 없습니다.");
    expect(screen.getByTestId("user-filter-role")).toBeDisabled();
    expect(
      screen.queryByRole("option", { name: "검증 역할" }),
    ).not.toBeInTheDocument();
    roleUnavailable = false;
    fireEvent.click(within(alert).getByRole("button", { name: "다시 시도" }));
    await screen.findByRole("option", { name: "검증 역할" });
    expect(screen.getByTestId("user-filter-role")).toBeEnabled();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});
