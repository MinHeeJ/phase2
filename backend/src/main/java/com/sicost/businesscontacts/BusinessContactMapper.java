package com.sicost.businesscontacts;

import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BusinessContactMapper {
    Map<String,Object> developer(Map<String,Object> query);
    Map<String,Object> partner(Map<String,Object> query);
}
