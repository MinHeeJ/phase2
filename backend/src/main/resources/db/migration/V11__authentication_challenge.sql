-- Browser pre-authentication context is independent of the OTP/RESET proof.
ALTER TABLE sys.auth_tkn ADD COLUMN IF NOT EXISTS challenge_id_hash VARCHAR(64) NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_auth_tkn_challenge ON sys.auth_tkn(challenge_id_hash) WHERE challenge_id_hash IS NOT NULL;
COMMENT ON COLUMN sys.auth_tkn.challenge_id_hash IS 'AuthenticationService가 발급한 MFA 사전 인증 쿠키의 SHA-256. OTP 해시와 분리하며 세션 권한으로 사용하지 않는다.';
