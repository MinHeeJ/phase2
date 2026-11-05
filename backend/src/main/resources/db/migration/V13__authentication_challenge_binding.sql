-- Bind preauthentication to an independent, hashed browser proof, never a numeric account ID.
ALTER TABLE sys.auth_tkn ADD COLUMN IF NOT EXISTS binding_hash VARCHAR(64);
COMMENT ON COLUMN sys.auth_tkn.binding_hash IS '인증 서비스가 MFA 사전인증 쿠키의 SHA-256 해시만 저장; OTP와 독립된 증명';
CREATE UNIQUE INDEX IF NOT EXISTS uk_auth_tkn_binding ON sys.auth_tkn(binding_hash) WHERE binding_hash IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_auth_tkn_proof ON sys.auth_tkn(tkn_hash, tkn_ty_cd);
CREATE TABLE IF NOT EXISTS sys.auth_request_window (
    key_hash VARCHAR(64) PRIMARY KEY,
    window_start TIMESTAMP NOT NULL,
    request_count INTEGER NOT NULL CHECK (request_count > 0)
);
COMMENT ON TABLE sys.auth_request_window IS '로그인·재설정 요청의 공유 속도 제한 창이다. 계정과 IP 원문 대신 결합 해시를 저장한다.';
COMMENT ON COLUMN sys.auth_request_window.request_count IS '인증 서비스가 요청마다 원자 증가; 15분 창 경과 후 재설정';
CREATE INDEX IF NOT EXISTS ix_auth_request_window_start ON sys.auth_request_window(window_start);
