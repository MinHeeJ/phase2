package com.sicost.apiops;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UploadResultMapper {
    long existingSalaryCount(@Param("month") String month);
}
