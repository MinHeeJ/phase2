package com.sicost.shortages;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sicost.us5.MonthlyShortageMapper;
import com.sicost.us5.MonthlyShortageModels.*;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class ShortageMapperTest {
    Configuration configuration() throws Exception {
        var c = new Configuration();
        String path = "mappers/us5/MonthlyShortageMapper.xml";
        try (var in = new ClassPathResource(path).getInputStream()) {
            new XMLMapperBuilder(in, c, path, c.getSqlFragments()).parse();
        }
        return c;
    }
    @Test void everyExistingMapperMethodRemainsRegistered() throws Exception {
        var c = configuration();
        for (var method : MonthlyShortageMapper.class.getDeclaredMethods()) {
            assertThat(c.hasStatement(MonthlyShortageMapper.class.getName() + "." + method.getName())).isTrue();
        }
    }
    @Test void filteredQueryBindsYearBoundsAndBothJobPredicatesWithoutDatabase() throws Exception {
        var c = configuration();
        var q = new Query(2028, 73L, "BE", 0, 50, "shortageMm,desc;plan_ym,asc");
        var statement = c.getMappedStatement(MonthlyShortageMapper.class.getName() + ".months");
        var sql = statement.getBoundSql(q);
        var prepared = mock(PreparedStatement.class);
        new DefaultParameterHandler(statement, q, sql).setParameters(prepared);
        verify(prepared).setObject(1, LocalDate.of(2028, 12, 31));
        verify(prepared).setObject(2, LocalDate.of(2028, 1, 1));
        verify(prepared).setLong(3, 73L);
        verify(prepared).setString(4, "2028-01");
        verify(prepared).setString(5, "2028-12");
        verify(prepared).setString(6, "BE");
        verify(prepared).setString(7, "2028-01");
        verify(prepared).setString(8, "2028-12");
        verify(prepared).setString(9, "BE");
        verifyNoMoreInteractions(prepared);
    }
    @Test void absentOptionalFiltersAreNotBoundAsNull() throws Exception {
        var c = configuration();
        var q = new Query(2026, null, null, 0, 50, "shortageMm,desc;plan_ym,asc");
        var sql = c.getMappedStatement(MonthlyShortageMapper.class.getName() + ".months").getBoundSql(q);
        assertThat(sql.getParameterMappings()).extracting(p -> p.getProperty())
            .containsExactly("lastDay", "firstDay", "firstMonth", "lastMonth", "firstMonth", "lastMonth");
        for (var binding : sql.getParameterMappings()) {
            assertThat(c.newMetaObject(q).getValue(binding.getProperty())).isNotNull();
        }
    }
    @Test void constructorMaterializesEveryColumnWithMatchingTypeAndOrder() throws Exception {
        var c = configuration();
        var mappings = c.getResultMap(MonthlyShortageMapper.class.getName() + ".monthlyShortageRow")
            .getConstructorResultMappings();
        var fields = Row.class.getRecordComponents();
        assertThat(mappings).hasSize(fields.length);
        assertThat(mappings.stream().map(m -> m.getColumn()).toList()).isEqualTo(
            List.of("prj_id", "prj_nm", "job_cd", "job_nm", "plan_ym", "plan_mm", "actual_mm", "shortage_mm"));
        for (int i = 0; i < fields.length; i++) {
            assertThat(mappings.get(i).getJavaType()).isEqualTo(fields[i].getType());
        }
    }
}
