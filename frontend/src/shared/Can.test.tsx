import { render, screen } from "@testing-library/react";
import { afterEach, expect, it } from "vitest";
import { Can } from "./Can";
import { setIdentity } from "./types";

const identity = {
  userId: 417,
  name: "권한 검증 사용자",
  roleIds: [],
  roles: [],
};
afterEach(() => setIdentity(null));

it.each([
  ["view", "inq"],
  ["ask", "inq"],
  ["recommend", "inq"],
  ["decision", "mdfcn"],
  ["receipt", "mdfcn"],
  ["remind", "mdfcn"],
  ["export", "dwnld"],
  ["cancel", "cnfrm"],
  ["reopen", "cnfrm"],
  ["approve", "cnfrm"],
])(
  "renders the %s action only for its corresponding %s grant",
  (action, column) => {
    setIdentity(identity, { "UI-13": { [column]: true } });
    const view = render(
      <Can perm={`UI-13.${action}`}>
        <button>허용 업무 실행</button>
      </Can>,
    );
    expect(
      screen.getByRole("button", { name: "허용 업무 실행" }),
    ).toBeVisible();
    setIdentity(identity, {
      "UI-13": { [column]: false },
      "UI-29": { [column]: true },
    });
    view.rerender(
      <Can perm={`UI-13.${action}`}>
        <button>허용 업무 실행</button>
      </Can>,
    );
    expect(
      screen.queryByRole("button", { name: "허용 업무 실행" }),
    ).not.toBeInTheDocument();
  },
);

it("does not promote read-only access into receipt editing or confirmation", () => {
  setIdentity(identity, { "UI-13": { inq: true } });
  render(
    <>
      <Can perm="UI-13.read">
        <button>청구 조회</button>
      </Can>
      <Can perm="UI-13.receipt">
        <button>입금 저장</button>
      </Can>
      <Can perm="UI-13.confirm">
        <button>청구 확정</button>
      </Can>
    </>,
  );
  expect(screen.getByRole("button", { name: "청구 조회" })).toBeVisible();
  expect(
    screen.queryByRole("button", { name: "입금 저장" }),
  ).not.toBeInTheDocument();
  expect(
    screen.queryByRole("button", { name: "청구 확정" }),
  ).not.toBeInTheDocument();
});
