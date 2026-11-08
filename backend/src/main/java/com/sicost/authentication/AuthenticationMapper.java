package com.sicost.authentication;

import java.util.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AuthenticationMapper {
    Map<String,Object> login(String loginId);
    Map<String,Object> account(Long id);
    void failed(Long id);
    void success(Long id);
    void password(Map<String,Object> write);
    void revokeProofs(Long id);
    Long token(Map<String,Object> write);
    Map<String,Object> challenge(String hash);
    Map<String,Object> reset(String hash);
    void consume(Long id);
    void failedOtp(Long id);
    void rotate(Map<String,Object> write);
    Long outbox(Map<String,Object> write);
    void delivered(Long id);
    void deliveryFailed(Long id);
    void tokenDelivered(Long id);
    void rateLock(String key);
    long attempts(String key);
    void attempt(Map<String,Object> write);
}
