package com.si.cost.common.persistence;

import java.time.Clock;
import java.time.ZoneId;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@MapperScan(basePackages = "com.si.cost", annotationClass = Mapper.class)
public class PersistenceConfiguration {
    @Bean
    public Clock productClock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
