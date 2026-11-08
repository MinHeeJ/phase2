import { useEffect, useState, type FormEvent } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { Button } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure, readData } from "../../shared/api";
import { command } from "../systemadmin/client";
import type { MenuNode } from "../shared/api";
import styles from "./Login.module.css";

export default function LoginScreen() {
  const location = useLocation(),
    navigate = useNavigate();
  const [mode, setMode] = useState(
    location.hash.startsWith("#reset=")
      ? "reset"
      : location.hash === "#password"
        ? "password"
        : "login",
  );
  const [token] = useState(() =>
    location.hash.startsWith("#reset=") ? location.hash.slice(7) : "",
  );
  const [loginId, setLoginId] = useState(""),
    [password, setPassword] = useState(""),
    [show, setShow] = useState(false),
    [code, setCode] = useState("");
  const [newPassword, setNewPassword] = useState(""),
    [confirmation, setConfirmation] = useState(""),
    [error, setError] = useState<ApiFailure | null>(null),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState("");
  const [expiresAt, setExpiresAt] = useState(0),
    [remaining, setRemaining] = useState(0);
  useEffect(() => {
    if (token) window.history.replaceState(null, "", location.pathname);
  }, [token, location.pathname]);
  useEffect(() => {
    if (mode !== "mfa") return;
    const tick = () =>
      setRemaining(Math.max(0, Math.ceil((expiresAt - Date.now()) / 1000)));
    tick();
    const timer = window.setInterval(tick, 1000);
    return () => clearInterval(timer);
  }, [mode, expiresAt]);
  const destination = async () => {
    const menus = await readData<MenuNode[]>("/api/navigation");
    const leaves = (nodes: MenuNode[]): MenuNode[] =>
      nodes.flatMap((n) => (n.screenId ? [n] : leaves(n.children)));
    const choices = leaves(menus);
    const next = choices.find((n) => n.screenId === "UI-16") ?? choices[0];
    if (next?.screenId) navigate(`/${next.screenId}`, { replace: true });
    else {
      setMessage("접근 가능한 메뉴가 없습니다.");
      setMode("no-menu");
    }
  };
  const outcome = async (result: { status: string; expiresAt?: string }) => {
    setPassword("");
    setCode("");
    if (result.status === "MFA_REQUIRED") {
      setMode("mfa");
      setExpiresAt(Date.parse(result.expiresAt!));
    } else if (result.status === "PASSWORD_CHANGE_REQUIRED")
      setMode("password");
    else await destination();
  };
  const run = async (fn: () => Promise<void>) => {
    if (busy) return;
    setBusy(true);
    setError(null);
    setMessage("");
    try {
      await fn();
    } catch (e) {
      setError(e as ApiFailure);
    } finally {
      setBusy(false);
    }
  };
  const submit = (e: FormEvent) => {
    e.preventDefault();
    void run(async () => {
      if (mode === "login")
        await outcome(
          await command("/api/auth/login", "POST", {
            loginId: loginId.trim(),
            password,
          }),
        );
      else if (mode === "mfa")
        await outcome(await command("/api/auth/mfa/verify", "POST", { code }));
      else if (mode === "forgot") {
        await command("/api/auth/forgot-password", "POST", {
          loginId: loginId.trim(),
        });
        setMessage("등록된 계정이면 재설정 안내를 발송합니다.");
      } else {
        if (newPassword !== confirmation)
          throw new ApiFailure(
            "비밀번호 확인이 일치하지 않습니다.",
            400,
            null,
            "confirmPassword",
          );
        await command(
          mode === "reset"
            ? "/api/auth/reset-password"
            : "/api/auth/change-password",
          "POST",
          {
            ...(mode === "reset" ? { token } : { currentPassword: password }),
            newPassword,
            confirmPassword: confirmation,
          },
        );
        setNewPassword("");
        setConfirmation("");
        setPassword("");
        setMode("login");
        setMessage("비밀번호를 변경했습니다. 다시 로그인하세요.");
      }
    });
  };
  return (
    <main
      className={styles.page}
      data-testid="login-screen"
      data-screen-id="UI-22"
    >
      <section className={styles.card}>
        <h1>프로젝트·원가 관리</h1>
        <h2>
          {
            (
              {
                login: "로그인",
                mfa: "이메일 2차 인증",
                forgot: "비밀번호 찾기",
                reset: "비밀번호 재설정",
                password: "비밀번호 변경",
                "no-menu": "접근 가능한 메뉴 없음",
              } as Record<string, string>
            )[mode]
          }
        </h2>
        <ErrorBanner error={error} />
        {error?.field && (
          <p role="alert">
            {error.field}: {error.message}
          </p>
        )}
        {message && <p role="status">{message}</p>}
        <form className={styles.form} onSubmit={submit}>
          {(mode === "login" || mode === "forgot") && (
            <label>
              아이디
              <input
                data-testid="login-id"
                autoComplete="username"
                maxLength={50}
                required
                value={loginId}
                onChange={(e) => setLoginId(e.target.value)}
              />
            </label>
          )}
          {(mode === "login" || mode === "password") && (
            <>
              <label>
                {mode === "password" ? "기존 비밀번호" : "비밀번호"}
                <input
                  data-testid="login-password"
                  autoComplete="current-password"
                  type={show ? "text" : "password"}
                  required
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                />
              </label>
              <Button
                data-testid="login-password-visibility"
                type="button"
                onClick={() => setShow((v) => !v)}
              >
                {show ? "비밀번호 숨기기" : "비밀번호 보기"}
              </Button>
            </>
          )}
          {mode === "mfa" && (
            <>
              <p>
                메일로 받은 6자리 코드를 입력하세요. 남은 시간 {remaining}초
              </p>
              <label>
                인증 코드
                <input
                  data-testid="login-mfa-code"
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  maxLength={6}
                  pattern="[0-9]{6}"
                  required
                  value={code}
                  onChange={(e) => setCode(e.target.value)}
                />
              </label>
              <Button
                data-testid="login-mfa-resend"
                type="button"
                pending={busy}
                onClick={() =>
                  void run(async () => {
                    const r = await command<{ expiresAt: string }>(
                      "/api/auth/mfa/resend",
                      "POST",
                    );
                    setCode("");
                    setExpiresAt(Date.parse(r.expiresAt));
                  })
                }
              >
                재전송
              </Button>
            </>
          )}
          {(mode === "reset" || mode === "password") && (
            <>
              <p>
                8자 이상, 문자·숫자·특수문자를 포함하세요. 변경 후 기존 세션은
                종료됩니다.
              </p>
              <label>
                새 비밀번호
                <input
                  data-testid="login-new-password"
                  type="password"
                  autoComplete="new-password"
                  minLength={8}
                  required
                  value={newPassword}
                  onChange={(e) => setNewPassword(e.target.value)}
                />
              </label>
              <label>
                새 비밀번호 확인
                <input
                  data-testid="login-confirm-password"
                  type="password"
                  autoComplete="new-password"
                  required
                  value={confirmation}
                  onChange={(e) => setConfirmation(e.target.value)}
                />
              </label>
            </>
          )}
          {mode !== "no-menu" && (
            <Button
              data-testid="login-submit"
              variant="primary"
              type="submit"
              pending={busy}
            >
              {mode === "login"
                ? "로그인"
                : mode === "mfa"
                  ? "인증"
                  : mode === "forgot"
                    ? "안내 요청"
                    : "비밀번호 저장"}
            </Button>
          )}
          <div className={styles.actions}>
            {mode === "login" ? (
              <Button
                data-testid="login-forgot"
                type="button"
                onClick={() => {
                  setMode("forgot");
                  setPassword("");
                }}
              >
                비밀번호 찾기
              </Button>
            ) : (
              <Button
                data-testid="login-cancel"
                type="button"
                onClick={() => {
                  setCode("");
                  setPassword("");
                  setMode("login");
                  setError(null);
                }}
              >
                로그인으로
              </Button>
            )}
          </div>
        </form>
      </section>
    </main>
  );
}
