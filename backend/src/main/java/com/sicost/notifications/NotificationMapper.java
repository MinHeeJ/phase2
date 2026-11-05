package com.sicost.notifications;

import java.util.*;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface NotificationMapper {
    List<Map<String,Object>> list(Map<String,Object> query);
    long count(Map<String,Object> query);
    Map<String,Object> owned(Map<String,Object> query);
    boolean visible(Map<String,Object> query);
    int read(Map<String,Object> query);
    int readAll(Map<String,Object> query);
}
