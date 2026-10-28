package com.sicost.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.mapper.ClassPathMapperScanner;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.filter.AnnotationTypeFilter;

class BillingIntegrationRegistrationTest {
    @Test void billingPhasesRegisterTogetherWithoutReplacingComponentsOrMappers() {
        var registry = new DefaultListableBeanFactory();
        registry.setAllowBeanDefinitionOverriding(false);
        var components = new ClassPathBeanDefinitionScanner(registry);
        components.addExcludeFilter(new AnnotationTypeFilter(Configuration.class));
        components.scan("com.sicost.billings", "com.sicost.billingsops");

        var mappers = new ClassPathMapperScanner(registry);
        mappers.setAnnotationClass(Mapper.class);
        mappers.registerFilters();
        mappers.scan("com.sicost.billings", "com.sicost.billingsops");

        assertThat(registry.getBeanDefinitionNames()).contains(
            "billingController", "billingOperationsController",
            "billingService", "billingOperationsService",
            "billingAmountService", "billingOperationsAmountService",
            "billingMapper", "billingOperationsMapper");
        assertThat(registry.getBeanDefinition("billingService").getBeanClassName())
            .isEqualTo("com.sicost.billings.BillingService");
        assertThat(registry.getBeanDefinition("billingOperationsService").getBeanClassName())
            .isEqualTo("com.sicost.billingsops.BillingService");
    }
}
