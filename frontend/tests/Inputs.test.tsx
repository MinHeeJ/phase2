import { useState } from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import {
  DateInput,
  Form,
  MoneyInput,
  MonthInput,
  SelectInput,
  TextInput,
  TextareaInput,
} from "../src/shared/Inputs";

function Money() {
  const [value, setValue] = useState<number | null>(null);
  return (
    <>
      <MoneyInput
        name="amount"
        label="금액"
        required
        value={value}
        onValueChange={setValue}
      />
      <output>{value === null ? "empty" : value}</output>
    </>
  );
}
function Text({ multiline = false }) {
  const [value, setValue] = useState("");
  const Component = multiline ? TextareaInput : TextInput;
  return (
    <Component
      label="이름"
      name="name"
      value={value}
      onValueChange={setValue}
      maxLength={50}
    />
  );
}
describe("shared inputs", () => {
  it("pasted won becomes formatted numeric value", () => {
    render(<Money />);
    fireEvent.change(screen.getByLabelText("금액"), {
      target: { value: "1,500,000원" },
    });
    expect(screen.getByLabelText("금액")).toHaveValue("1,500,000");
    expect(screen.getByRole("status")).toHaveTextContent("1500000");
  });
  it.each(["0", "999999999999"])("allows exact boundary %s", (value) => {
    render(<Money />);
    fireEvent.change(screen.getByLabelText("금액"), { target: { value } });
    expect(screen.getByRole("status")).toHaveTextContent(value);
  });
  it.each(["-1", "1000000000000", "one", "1.1"])(
    "rejects %s and preserves last valid value",
    (value) => {
      render(<Money />);
      const input = screen.getByLabelText("금액");
      fireEvent.change(input, { target: { value: "100" } });
      fireEvent.change(input, { target: { value } });
      expect(input).toHaveValue("100");
      expect(screen.getByRole("alert")).toBeInTheDocument();
    },
  );
  it("empty amount remains null and validates on blur", () => {
    render(<Money />);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    fireEvent.blur(screen.getByLabelText("금액"));
    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("empty");
  });
  it.each([false, true])(
    "blocks character 51 with counter; multiline=%s",
    (multiline) => {
      render(<Text multiline={multiline} />);
      const input = screen.getByLabelText("이름");
      fireEvent.change(input, { target: { value: "a".repeat(50) } });
      expect(screen.getByText("50/50")).toBeInTheDocument();
      fireEvent.change(input, { target: { value: "a".repeat(51) } });
      expect(input).toHaveValue("a".repeat(50));
    },
  );
  it("save focuses first invalid field and never calls save", () => {
    const save = vi.fn();
    render(
      <Form onSave={save}>
        <TextInput
          name="code"
          label="코드"
          value=""
          required
          onValueChange={() => {}}
        />
        <TextInput
          name="name"
          label="이름"
          value=""
          required
          onValueChange={() => {}}
        />
        <button type="submit">저장</button>
      </Form>,
    );
    fireEvent.click(screen.getByText("저장"));
    expect(screen.getByLabelText("코드")).toHaveFocus();
    expect(save).not.toHaveBeenCalled();
    expect(screen.getByLabelText("코드")).toHaveAttribute(
      "aria-invalid",
      "true",
    );
  });
  it("readonly and disabled inputs cannot edit", () => {
    render(
      <TextInput
        name="code"
        label="코드"
        value="BE"
        readOnly
        onValueChange={() => {}}
      />,
    );
    expect(screen.getByLabelText("코드")).toHaveAttribute("readonly");
  });
  it("renders calendar controls and API-supplied options", () => {
    const changed = vi.fn();
    render(
      <>
        <DateInput
          name="date"
          label="날짜"
          value="2026-10-01"
          onValueChange={changed}
        />
        <MonthInput
          name="month"
          label="연월"
          value="2026-10"
          onValueChange={changed}
        />
        <SelectInput
          name="choice"
          label="구분"
          value=""
          onValueChange={changed}
          options={[{ value: "BE", label: "백엔드" }]}
        />
      </>,
    );
    expect(screen.getByLabelText("날짜")).toHaveAttribute("type", "date");
    expect(screen.getByLabelText("연월")).toHaveAttribute("type", "month");
    fireEvent.change(screen.getByLabelText("구분"), {
      target: { value: "BE" },
    });
    expect(changed).toHaveBeenCalledWith("BE");
  });
});
