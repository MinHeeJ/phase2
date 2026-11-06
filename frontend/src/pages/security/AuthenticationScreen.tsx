import { useEffect, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { Button } from "../../shared/Controls";
import { get, write } from "./client";
import styles from "./Security.module.css";

interface LoginResult {
  state: "MFA_REQUIRED" | "PASSWORD_CHANGE_REQUIRED" | "AUTHENTICATED";
  expiresInSeconds?: number;
  passwordAgeWarning?: boolean;
}
export default function AuthenticationScreen() {
  const navigate = useNavigate(),
    location = useLocation();
  const reset = location.hash.startsWith("#reset=")
    ? location.hash.slice(7)
    : "";
  const [mode, setMode] = useState(
    reset ? "reset" : location.hash === "#password" ? "password" : "login",
  );
  const [loginId, setLoginId] = useState(""),
    [password, setPassword] = useState(""),
    [replacement, setReplacement] = useState(""),
    [confirmation, setConfirmation] = useState(""),
    [code, setCode] = useState("");
  const [visible, setVisible] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [message, setMessage] = useState(""),
    [remaining, setRemaining] = useState(0);
  useEffect(() => {
    if (mode !== "mfa") return;
    const timer = window.setInterval(
      () => setRemaining((value) => Math.max(0, value - 1)),
      1000,
    );
    return () => window.clearInterval(timer);
  }, [mode]);
  const destination = async () => {
    const nav = await get<{ firstAllowedScreenId: string | null }>(
      "/api/navigation",
    );
    if (nav.firstAllowedScreenId)
      navigate(`/${nav.firstAllowedScreenId}`, { replace: true });
    else {
      setMode("none");
      setMessage("접근 가능한 메뉴가 없습니다. 관리자에게 문의하세요.");
    }
  };
  const result = async (value: LoginResult) => {
    setPassword("");
    setCode("");
    if (value.state === "MFA_REQUIRED") {
      setMode("mfa");
      setRemaining(value.expiresInSeconds ?? 0);
    } else if (value.state === "PASSWORD_CHANGE_REQUIRED") {
      setMode("password");
      setMessage("업무 이용 전 비밀번호를 변경하세요.");
    } else await destination();
  };
  const run = async (action: () => Promise<void>) => {
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      await action();
    } catch (e) {
      setError(e instanceof Error ? e.message : "요청에 실패했습니다.");
    } finally {
      setBusy(false);
    }
  };
  const submit = () =>
    run(async () => {
      if (mode === "login")
        await result(
          await write<LoginResult>("/api/auth/login", "POST", {
            loginId: loginId.trim(),
            password,
          }),
        );
      else if (mode === "mfa")
        await result(
          await write<LoginResult>("/api/auth/mfa/verify", "POST", { code }),
        );
      else if (mode === "forgot") {
        await write("/api/auth/forgot-password", "POST", {
          loginId: loginId.trim(),
        });
        setMessage("등록된 계정이면 재설정 안내가 발송됩니다.");
      } else {
        if (replacement !== confirmation)
          throw new Error("새 비밀번호와 확인값이 다릅니다.");
        await write(
          `/api/auth/${mode === "reset" ? "reset-password" : "change-password"}`,
          "POST",
          {
            ...(mode === "reset"
              ? { token: reset }
              : { currentPassword: password }),
            newPassword: replacement,
            confirmPassword: confirmation,
          },
        );
        setPassword("");
        setReplacement("");
        setConfirmation("");
        setMode("login");
        navigate("/UI-22", { replace: true });
        setMessage("변경했습니다. 다시 로그인하세요.");
      }
    });
  return (
    <main
      className={styles.login}
      data-screen-id="SCR-UI-22"
      data-testid="authentication-screen"
    >
      <section className={`${styles.panel} ${styles.card}`}>
        <h1>프로젝트·원가 관리</h1>
        <h2>
          {
            (
              {
                login: "로그인",
                mfa: "이메일 2차 인증",
                password: "비밀번호 변경",
                forgot: "비밀번호 찾기",
                reset: "비밀번호 재설정",
                none: "접근 가능한 메뉴 없음",
              } as Record<string, string>
            )[mode]
          }
        </h2>
        {error && <p role="alert">{error}</p>}
        {message && <p role="status">{message}</p>}
        {mode !== "none" && (
          <form
            className={styles.form}
            onSubmit={(event) => {
              event.preventDefault();
              void submit();
            }}
          >
            {(mode === "login" || mode === "forgot") && (
              <label>
                아이디
                <input
                  data-testid="login-id-input"
                  value={loginId}
                  onChange={(e) => setLoginId(e.target.value)}
                  required
                  maxLength={50}
                  autoComplete="username"
                />
              </label>
            )}
            {(mode === "login" || mode === "password") && (
              <label>
                {mode === "password" ? "기존 비밀번호" : "비밀번호"}
                <input
                  data-testid="login-password-input"
                  type={visible ? "text" : "password"}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  required
                  autoComplete="current-password"
                />
              </label>
            )}
            {(mode === "login" || mode === "password") && (
              <Button
                type="button"
                data-testid="password-visibility-button"
                onClick={() => setVisible(!visible)}
              >
                {visible ? "비밀번호 숨기기" : "비밀번호 보기"}
              </Button>
            )}
            {mode === "mfa" && (
              <>
                <p>메일로 받은 6자리 코드 · 남은 시간 {remaining}초</p>
                <label>
                  인증 코드
                  <input
                    data-testid="mfa-code-input"
                    inputMode="numeric"
                    autoComplete="one-time-code"
                    pattern="[0-9]{6}"
                    maxLength={6}
                    value={code}
                    onChange={(e) => setCode(e.target.value)}
                    required
                  />
                </label>
                <Button
                  type="button"
                  data-testid="mfa-resend-button"
                  disabled={busy}
                  onClick={() =>
                    void run(async () =>
                      result(
                        await write<LoginResult>(
                          "/api/auth/mfa/resend",
                          "POST",
                        ),
                      ),
                    )
                  }
                >
                  재전송
                </Button>
              </>
            )}
            {(mode === "password" || mode === "reset") && (
              <>
                <p>8자 이상, 영문·숫자·특수문자를 각각 포함하세요.</p>
                <label>
                  새 비밀번호
                  <input
                    data-testid="new-password-input"
                    type="password"
                    minLength={8}
                    value={replacement}
                    onChange={(e) => setReplacement(e.target.value)}
                    autoComplete="new-password"
                    required
                  />
                </label>
                <label>
                  새 비밀번호 확인
                  <input
                    data-testid="confirm-password-input"
                    type="password"
                    value={confirmation}
                    onChange={(e) => setConfirmation(e.target.value)}
                    autoComplete="new-password"
                    required
                  />
                </label>
              </>
            )}
            <Button
              data-testid="authentication-submit-button"
              type="submit"
              disabled={busy}
            >
              {busy
                ? "처리 중…"
                : mode === "mfa"
                  ? "인증 확인"
                  : mode === "login"
                    ? "로그인"
                    : "저장·요청"}
            </Button>
          </form>
        )}
        <div className={styles.toolbar}>
          {mode === "login" ? (
            <Button
              data-testid="forgot-password-button"
              onClick={() => {
                setMode("forgot");
                setError("");
                setPassword("");
              }}
            >
              비밀번호 찾기
            </Button>
          ) : (
            <Button
              data-testid="authentication-cancel-button"
              disabled={busy}
              onClick={() =>
                void run(async () => {
                  if (mode === "mfa")
                    await write("/api/auth/mfa/cancel", "POST");
                  setCode("");
                  setPassword("");
                  setMode("login");
                  navigate("/UI-22", { replace: true });
                })
              }
            >
              취소·로그인으로
            </Button>
          )}
          {(mode === "password" || mode === "none") && (
            <Button
              data-testid="authentication-logout-button"
              onClick={() =>
                void run(async () => {
                  await write("/api/auth/logout", "POST");
                  setMode("login");
                })
              }
            >
              로그아웃
            </Button>
          )}
        </div>
      </section>
    </main>
  );
}
