import { useEffect, useState, type FormEvent } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { api, ApiFailure } from "../../shared/api";
import { Button } from "../../shared/Controls";
import { loginDestination } from "./ProtectedApplication";
import styles from "./SecurityScreens.module.css";

interface LoginResult {
  state: "MFA_REQUIRED" | "PASSWORD_CHANGE_REQUIRED" | "AUTHENTICATED";
  expiresAt: string;
  passwordAgeWarning: boolean;
}
export default function LoginScreen() {
  const location = useLocation(),
    navigate = useNavigate();
  const [mode, setMode] = useState<
    "login" | "mfa" | "password" | "forgot" | "reset"
  >(
    location.hash === "#password"
      ? "password"
      : location.hash.startsWith("#reset=")
        ? "reset"
        : "login",
  );
  const [token, setToken] = useState(() =>
    location.hash.startsWith("#reset=") ? location.hash.slice(7) : "",
  );
  const [loginId, setLoginId] = useState("");
  const [password, setPassword] = useState(""),
    [next, setNext] = useState(""),
    [confirm, setConfirm] = useState(""),
    [code, setCode] = useState("");
  const [visible, setVisible] = useState(false),
    [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<ApiFailure | null>(null),
    [message, setMessage] = useState("");
  const [expires, setExpires] = useState<string | null>(null),
    [remaining, setRemaining] = useState(0);
  useEffect(() => {
    if (location.hash.startsWith("#reset=")) {
      window.history.replaceState(null, "", location.pathname);
    }
  }, []);
  useEffect(() => {
    if (!expires) return;
    const tick = () =>
      setRemaining(
        Math.max(
          0,
          Math.ceil(
            (new Date(
              expires.endsWith("Z") || /[+-]\d\d:\d\d$/.test(expires)
                ? expires
                : `${expires}+09:00`,
            ).getTime() -
              Date.now()) /
              1000,
          ),
        ),
      );
    tick();
    const timer = window.setInterval(tick, 1000);
    return () => window.clearInterval(timer);
  }, [expires]);
  const clear = () => {
    setPassword("");
    setNext("");
    setConfirm("");
    setCode("");
  };
  const result = async (value: LoginResult) => {
    if (value.state === "MFA_REQUIRED") {
      setMode("mfa");
      setExpires(value.expiresAt);
      return;
    }
    if (value.state === "PASSWORD_CHANGE_REQUIRED") {
      setMode("password");
      return;
    }
    const destination = await loginDestination();
    if (destination) navigate(`/${destination}`, { replace: true });
    else navigate("/", { replace: true });
  };
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setFailure(null);
    setMessage("");
    if ((mode === "password" || mode === "reset") && next !== confirm) {
      setFailure(
        new ApiFailure(
          "비밀번호 확인이 일치하지 않습니다.",
          400,
          null,
          "confirmPassword",
        ),
      );
      return;
    }
    setPending(true);
    try {
      if (mode === "login")
        await result(
          (
            await api<LoginResult>("/api/auth/login", {
              method: "POST",
              body: JSON.stringify({ loginId: loginId.trim(), password }),
            })
          ).data,
        );
      else if (mode === "mfa")
        await result(
          (
            await api<LoginResult>("/api/auth/mfa/verify", {
              method: "POST",
              body: JSON.stringify({ code }),
            })
          ).data,
        );
      else if (mode === "forgot") {
        await api("/api/auth/forgot-password", {
          method: "POST",
          body: JSON.stringify({ loginId: loginId.trim() }),
        });
        setMessage("등록된 이메일로 안내를 보냈습니다.");
      } else {
        const body =
          mode === "reset"
            ? { token, newPassword: next, confirmPassword: confirm }
            : {
                currentPassword: password,
                newPassword: next,
                confirmPassword: confirm,
              };
        await api(
          mode === "reset"
            ? "/api/auth/reset-password"
            : "/api/auth/change-password",
          { method: "POST", body: JSON.stringify(body) },
        );
        setToken("");
        setMode("login");
        setMessage("비밀번호가 변경되었습니다. 다시 로그인하세요.");
      }
    } catch (error) {
      setFailure(
        error instanceof ApiFailure
          ? error
          : new ApiFailure("요청을 처리할 수 없습니다.", null, null),
      );
    } finally {
      clear();
      setPending(false);
    }
  };
  const cancel = async () => {
    setPending(true);
    setFailure(null);
    try {
      if (mode === "mfa") await api("/api/auth/mfa/cancel", { method: "POST" });
      if (mode === "password")
        await api("/api/auth/logout", { method: "POST" });
      clear();
      setToken("");
      setMode("login");
      setMessage("");
    } catch (error) {
      setFailure(error as ApiFailure);
    } finally {
      setPending(false);
    }
  };
  const title = {
    login: "로그인",
    mfa: "이메일 2단계 인증",
    password: "비밀번호 변경",
    forgot: "비밀번호 찾기",
    reset: "새 비밀번호 설정",
  }[mode];
  return (
    <main
      className={styles.login}
      data-testid="login-screen"
      data-screen-id="UI-22"
    >
      <form className={styles.card} onSubmit={submit} aria-busy={pending}>
        <p>프로젝트·원가 관리</p>
        <h1>{title}</h1>
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
          <label>
            {mode === "password" ? "기존 비밀번호" : "비밀번호"}
            <input
              data-testid="login-password"
              type={visible ? "text" : "password"}
              autoComplete="current-password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </label>
        )}
        {(mode === "login" || mode === "password") && (
          <Button
            data-testid="password-visibility"
            type="button"
            aria-pressed={visible}
            onClick={() => setVisible((v) => !v)}
          >
            {visible ? "비밀번호 숨기기" : "비밀번호 보기"}
          </Button>
        )}
        {mode === "mfa" && (
          <>
            <p>등록된 이메일로 발송한 6자리 코드를 입력하세요.</p>
            <label>
              인증 코드
              <input
                data-testid="mfa-code"
                inputMode="numeric"
                autoComplete="one-time-code"
                pattern="[0-9]{6}"
                maxLength={6}
                required
                value={code}
                onChange={(e) => setCode(e.target.value)}
              />
            </label>
            <p role="timer">남은 시간 {remaining}초</p>
            <Button
              data-testid="mfa-resend"
              type="button"
              disabled={pending}
              onClick={async () => {
                setPending(true);
                try {
                  await result(
                    (
                      await api<LoginResult>("/api/auth/mfa/resend", {
                        method: "POST",
                      })
                    ).data,
                  );
                  setCode("");
                } catch (e) {
                  setFailure(e as ApiFailure);
                } finally {
                  setPending(false);
                }
              }}
            >
              코드 재전송
            </Button>
          </>
        )}
        {(mode === "password" || mode === "reset") && (
          <>
            <p>8자 이상·영문/숫자/특수문자 각 1자 이상</p>
            <label>
              새 비밀번호
              <input
                data-testid="new-password"
                type="password"
                autoComplete="new-password"
                minLength={8}
                required
                value={next}
                onChange={(e) => setNext(e.target.value)}
              />
            </label>
            <label>
              새 비밀번호 확인
              <input
                data-testid="confirm-password"
                type="password"
                autoComplete="new-password"
                required
                value={confirm}
                onChange={(e) => setConfirm(e.target.value)}
              />
            </label>
          </>
        )}
        {failure && (
          <p role="alert">
            {failure.message}
            {failure.field && ` (${failure.field})`}
            {failure.requestId && ` 문의번호: ${failure.requestId}`}
          </p>
        )}
        {message && <p role="status">{message}</p>}
        <Button
          data-testid="authentication-submit"
          type="submit"
          disabled={pending || (mode === "mfa" && remaining === 0)}
        >
          {pending
            ? "처리 중…"
            : mode === "forgot"
              ? "링크 요청"
              : mode === "mfa"
                ? "인증 확인"
                : mode === "login"
                  ? "로그인"
                  : "저장"}
        </Button>
        {mode === "login" ? (
          <Button
            data-testid="forgot-password"
            type="button"
            onClick={() => {
              clear();
              setFailure(null);
              setMode("forgot");
            }}
          >
            비밀번호 찾기
          </Button>
        ) : (
          <Button
            data-testid="authentication-cancel"
            type="button"
            disabled={pending}
            onClick={() => void cancel()}
          >
            취소·로그인으로
          </Button>
        )}
      </form>
    </main>
  );
}
