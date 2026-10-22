package com.sicost;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.sicost.persistence")
public class PersistenceConfiguration {
}
