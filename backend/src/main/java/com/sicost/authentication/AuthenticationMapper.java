package com.sicost.authentication;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import static com.sicost.authentication.AuthenticationModels.*;

@Mapper
public interface AuthenticationMapper {
    Credentials loginAccount(String loginId);
    Credentials lockAccount(Long userId);
    void failedPassword(Long userId);
    void failedOtp(Long userId);
    void completeLogin(@Param("userId") Long userId,@Param("now") LocalDateTime now);
    Long createProof(@Param("challenge") Challenge challenge,@Param("bindingHash") String bindingHash);
    Proof preauth(@Param("bindingHash") String bindingHash,@Param("now") LocalDateTime now);
    Proof resetProof(@Param("hash") String hash,@Param("now") LocalDateTime now);
    void revokeProofs(Long userId);
    int consumeProof(@Param("id") Long id,@Param("now") LocalDateTime now);
    void insertSession(SessionWrite write);
    void revokeSessions(@Param("userId") Long userId,@Param("now") LocalDateTime now);
    void logout(@Param("hash") String hash,@Param("now") LocalDateTime now);
    int activity(@Param("hash") String hash,@Param("now") LocalDateTime now);
    void changePassword(PasswordWrite write);
    void audit(Audit event);
    int rate(@Param("hash") String hash,@Param("now") LocalDateTime now);
    Long outbox(@Param("proofId") Long proofId,@Param("userId") Long userId,@Param("type") String type,@Param("now") LocalDateTime now);
    void mailSent(@Param("id") Long id,@Param("now") LocalDateTime now);
    void mailFailed(Long id);
}
