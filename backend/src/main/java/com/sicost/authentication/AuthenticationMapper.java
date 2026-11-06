package com.sicost.authentication;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuthenticationMapper {
    Map<String,Object> account(Map<String,Object> query);
    Map<String,Object> session(Map<String,Object> query);
    Map<String,Object> challenge(Map<String,Object> query);
    List<Long> roles(long userId);
    int failed(Map<String,Object> query);
    int complete(Map<String,Object> query);
    int insertSession(Map<String,Object> query);
    int activity(Map<String,Object> query);
    int revoke(Map<String,Object> query);
    int invalidateTokens(Map<String,Object> query);
    long insertChallenge(Map<String,Object> query);
    int consume(Map<String,Object> query);
    int failedOtp(Map<String,Object> query);
    int password(Map<String,Object> query);
    int audit(Map<String,Object> query);
    long delivery(Map<String,Object> query);
    int deliveryResult(Map<String,Object> query);
    int rateLock(String key);
    long attempts(Map<String,Object> query);
}
