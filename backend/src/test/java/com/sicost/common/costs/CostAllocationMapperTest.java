package com.sicost.common.costs;

import com.sicost.apiops.ProjectBoardMapper;
import com.sicost.billingsops.BillingModels;
import com.sicost.common.security.CostActions;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;
import static com.sicost.common.costs.CostAllocationModels.*;

class CostAllocationMapperTest {
    static Configuration configuration() throws Exception {
        var c = new Configuration();
        c.setMapUnderscoreToCamelCase(true);
        c.setArgNameBasedConstructorAutoMapping(true);
        for (String path : List.of("mappers/apiops/ProjectBoardMapper.xml", "mappers/billingsops/BillingMapper.xml")) {
            try (var input = new ClassPathResource(path).getInputStream()) {
                new XMLMapperBuilder(input, c, path, c.getSqlFragments()).parse();
            }
        }
        return c;
    }
    @Test void projectionsMatchExactRecordConstructorNamesTypesAndOrderWithoutDatabase() throws Exception {
        var c = configuration();
        var projections = Map.of(
            ProjectBoardMapper.class.getName() + ".allocationSalary", Salary.class,
            ProjectBoardMapper.class.getName() + ".allocationAssignment", Assignment.class,
            ProjectBoardMapper.class.getName() + ".allocationExpense", Expense.class,
            ProjectBoardMapper.class.getName() + ".allocationTurnkey", Turnkey.class,
            ProjectBoardMapper.class.getName() + ".allocationSnapshot", Allocation.class,
            "com.sicost.billingsops.BillingMapper.projectRevenue", BillingModels.ProjectRevenue.class);
        for (var entry : projections.entrySet()) {
            var components = entry.getValue().getRecordComponents();
            var mappings = c.getResultMap(entry.getKey()).getConstructorResultMappings();
            assertThat(mappings).hasSize(components.length);
            for (int i = 0; i < components.length; i++) {
                assertThat(mappings.get(i).getColumn()).isEqualTo(components[i].getName());
                assertThat(mappings.get(i).getJavaType()).isEqualTo(components[i].getType());
            }
        }
    }
    @Test void sourceQueriesReadOnlyAndDoNotReadBillingMmOrVatAsCosts() throws Exception {
        var c = configuration();
        for (String name : List.of("allocationSalaries", "allocationAssignments", "allocationExpenses", "allocationTurnkey", "allocationSnapshots")) {
            var statement = c.getMappedStatement(ProjectBoardMapper.class.getName() + "." + name);
            assertThat(statement.getSqlCommandType()).isEqualTo(org.apache.ibatis.mapping.SqlCommandType.SELECT);
            assertThat(statement.getBoundSql("2026-10").getSql()).doesNotContain("bill_dtl", "vat_amt");
        }
    }
    @Test void projectRevenueUsesConfirmedSupplyAndPreservesNegativeAmendments() throws Exception {
        var sql = configuration().getMappedStatement("com.sicost.billingsops.BillingMapper.projectRevenue")
            .getBoundSql(new BillingModels.RevenuePeriod(7, "2026-01", "2026-10")).getSql();
        assertThat(sql).contains("sum(b.sply_amt)", "b.bill_stts_cd='CONFIRM'", "b.prj_id=?", "b.atrb_ym", "b.bill_dt")
            .doesNotContain("bill_dtl", "rcpt_amt", "vat_amt", "amnd_yn='N'");
    }
    @Test void permissionKeysAreMetadataOnlyAndDownloadsDoNotInventOperationBindings() {
        assertThat(CostActions.key("listCostPeople")).isEqualTo("UI-03.read");
        assertThat(CostActions.key("simulateCostProject")).isEqualTo("UI-04.read");
        assertThat(CostActions.key("updatePartSettlementCard")).isEqualTo("UI-19.update");
        assertThat(CostActions.key("confirmPartSettlement")).isEqualTo("UI-19.confirm");
        assertThat(CostActions.key("cancelPartSettlement")).isEqualTo("UI-19.cancel");
        assertThat(CostActions.key("listCostRenegotiations")).isEqualTo("UI-32.read");
        assertThat(CostActions.key("UI-32.download")).isEqualTo("UI-32.download");
        assertThatThrownBy(() -> CostActions.key("downloadCostPeople")).isInstanceOf(IllegalArgumentException.class);
    }
}
