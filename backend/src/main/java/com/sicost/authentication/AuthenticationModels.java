package com.sicost.authentication;

import java.time.LocalDate;
import java.time.LocalDateTime;

public final class AuthenticationModels {
    private AuthenticationModels() {}
    public record Login(String loginId,String password) {
        @Override public String toString() { return "Login[redacted]"; }
    }
    public record PasswordChange(String currentPassword,String newPassword,String confirmPassword) {
        @Override public String toString() { return "PasswordChange[redacted]"; }
    }
    public record Reset(String token,String newPassword,String confirmPassword) {
        @Override public String toString() { return "Reset[redacted]"; }
    }
    public record Credentials(Long userId,String loginId,String passwordHash,String email,String status,
                              LocalDate resignDate,boolean mfa,boolean passwordChangeRequired,LocalDateTime passwordChangedAt,int otpFailures) {
        @Override public String toString() { return "Credentials[redacted]"; }
    }
    public record Proof(Long id,Long userId,String hash,String type,LocalDateTime expiresAt) {
        @Override public String toString() { return "Proof[redacted]"; }
    }
    public record Challenge(String raw,String hash,String type,Long userId,LocalDateTime now,LocalDateTime expiresAt) {
        @Override public String toString() { return "Challenge[redacted]"; }
    }
    public record SessionWrite(Long userId,String hash,LocalDateTime now,LocalDateTime expiresAt) {
        @Override public String toString() { return "SessionWrite[redacted]"; }
    }
    public record PasswordWrite(Long userId,String hash,LocalDateTime now) {
        @Override public String toString() { return "PasswordWrite[redacted]"; }
    }
    public record Audit(Long userId,String type,String result,String screenId,String target,LocalDateTime now) {}
    public record LoginResult(String state,boolean passwordChangeRequired,boolean passwordAgeWarning,LocalDateTime expiresAt) {}
    public record AuthResult(LoginResult result,String session,String preauth) {
        @Override public String toString() { return "AuthResult[redacted]"; }
    }
}
