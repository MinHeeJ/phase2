import { useState } from "react";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect, vi } from "vitest";
import { Can, MoneyInput, TextField, Busy } from "../src/components/Common";
function Inputs() {
  const [money, setMoney] = useState<number | null>(null);
  const [text, setText] = useState("");
  return (
    <>
      <MoneyInput label="금액" value={money} onChange={setMoney} />
      <output aria-label="내부 금액">{money}</output>
      <TextField
        label="그룹명"
        maxLength={50}
        value={text}
        onChange={(e) => setText(e.target.value)}
      />
    </>
  );
}
describe("사용자와 공통 입력", () => {
  it.each(["UI-27.read", "UI-27.create", "UI-27.update", "UI-27.delete"])(
    "현재 권한 자리 %s는 모두 허용",
    (perm) => {
      render(
        <Can perm={perm}>
          <button>동작</button>
        </Can>,
      );
      expect(screen.getByRole("button", { name: "동작" })).toBeEnabled();
    },
  );
  it("금액 붙여넣기와 최대 글자 수를 실제 입력", async () => {
    const user = userEvent.setup();
    render(<Inputs />);
    await user.click(screen.getByRole("textbox", { name: "금액" }));
    await user.paste("1,500,000원");
    expect(screen.getByLabelText("내부 금액")).toHaveTextContent("1500000");
    const text = screen.getByRole("textbox", { name: /그룹명/ });
    await user.type(text, "가".repeat(51));
    expect(text).toHaveValue("가".repeat(50));
    expect(screen.getByText("50/50")).toBeVisible();
  });
  it("처리 중 버튼은 추가 요청 차단", async () => {
    const click = vi.fn();
    render(
      <Busy busy onClick={click}>
        저장
      </Busy>,
    );
    await userEvent.click(screen.getByRole("button"));
    expect(click).not.toHaveBeenCalled();
  });
});
