package com.sicost.contacts;

import java.util.*;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BusinessContactMapper {
    Map<String,Object> developer(Map<String,Object> query);
    Map<String,Object> partner(Map<String,Object> query);
    boolean allowed(Map<String,Object> query);
    void audit(Map<String,Object> query);
}
