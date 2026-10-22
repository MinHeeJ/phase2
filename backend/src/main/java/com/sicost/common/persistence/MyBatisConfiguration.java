package com.sicost.common.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan(basePackages = "com.sicost", annotationClass = Mapper.class)
public class MyBatisConfiguration {
}
