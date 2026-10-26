package com.si.cost.admin;

import com.si.cost.common.api.PageRequest;
import com.si.cost.common.user.DataScope;
import java.math.BigDecimal;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import static com.si.cost.admin.AdminModels.*;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named="TEST_DATABASE_URL",matches=".+")
class AdminMapperIT {
    @Test void postgresMapsEveryAdminResourceAndRollsBackChangesAndAuditTogether() throws Exception {
        String url=System.getenv("TEST_DATABASE_URL"); String user=System.getenv("TEST_DB_USERNAME"); String password=System.getenv("TEST_DB_PASSWORD");
        Flyway.configure().dataSource(url,user,password).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var source=new UnpooledDataSource("org.postgresql.Driver",url,user,password);
        var configuration=new Configuration(new Environment("contract",new JdbcTransactionFactory(),source));
        configuration.setMapUnderscoreToCamelCase(true); configuration.setArgNameBasedConstructorAutoMapping(true);
        String resource="mappers/admin/AdminMapper.xml";
        try(var xml=new ClassPathResource(resource).getInputStream()) { new XMLMapperBuilder(xml,configuration,resource,configuration.getSqlFragments()).parse(); }
        var factory=new SqlSessionFactoryBuilder().build(configuration);
        try(var session=factory.openSession(false)) {
            var mapper=session.getMapper(AdminMapper.class); var order=new PageRequest(0,100,"sort_seq",false);
            assertThat(mapper.groupCount()).isEqualTo(36); assertThat(mapper.groups(order,DataScope.ALL)).hasSize(36);
            assertThat(mapper.group("CONTRACT_TYPE").kind()).isEqualTo("SYSTEM");
            assertThat(mapper.codes("JOB",order,DataScope.ALL)).hasSize(12); assertThat(mapper.codeCount("JOB")).isEqualTo(12);
            var old=mapper.allCodes("POSITION").stream().filter(row -> row.code().equals("TL")).findFirst().orElseThrow();
            long before=mapper.historyCount("POSITION");
            mapper.updateCode("POSITION","TL",Map.of("attr1_val","250000"));
            mapper.audit(new Audit("POSITION","TL","UPDATE","attr1_val",old.attr1(),"250000"));
            assertThat(mapper.allCodes("POSITION").stream().filter(row -> row.code().equals("TL")).findFirst().orElseThrow().attr1()).isEqualTo("250000");
            assertThat(mapper.historyCount("POSITION")).isEqualTo(before+1);
            var history=mapper.history("POSITION",new PageRequest(0,50,"chg_at",true),DataScope.ALL);
            assertThat(history.get(0).before()).isEqualTo(old.attr1()); assertThat(history.get(0).userId()).isNull();
            mapper.insertGroup(new GroupCreate("INTEGRATION_GROUP","검증 그룹",null,null,null));
            assertThat(mapper.group("INTEGRATION_GROUP").kind()).isEqualTo("BIZ");
            assertThat(mapper.group("INTEGRATION_GROUP").use()).isEqualTo("Y");
            var newMap=new MapCreate("관리 대응 검증","JOB","BE","MAIN",null);
            assertThat(mapper.activeTarget("JOB","BE")).isTrue();
            long mapId=mapper.insertMap(newMap); assertThat(mapId).isPositive(); assertThat(mapper.duplicateMap(newMap)).isTrue();
            mapper.updateMap(mapId,Map.of("map_role_cd","SUB","rmrk_desc","수정 메모","use_yn","N"));
            mapper.updateCode("JOB","BE",Map.of("use_yn","N"));
            assertThat(mapper.activeTarget("JOB","BE")).isFalse();
            assertThat(mapper.excelMap(mapId).codeName()).isNotBlank(); assertThat(mapper.excelMap(mapId).id()).isEqualTo(Long.toString(mapId));
            assertThat(mapper.maps(new PageRequest(0,100,"excel_lbl_nm",false),DataScope.ALL)).anyMatch(row -> row.id().equals(Long.toString(mapId)) && row.use().equals("N"));
            assertThat(mapper.mapCount()).isPositive();
            assertThat(mapper.lastClosed()).isEqualTo("2026-09");
            ParamSource metadata=mapper.paramSource("VAT_RATE");
            assertThat(metadata.name()).isEqualTo("부가세율");
            mapper.saveParam(metadata,"2026-11",new ParamSave(new BigDecimal("11"),null));
            assertThat(mapper.paramBefore("VAT_RATE","2026-10").number()).isEqualByComparingTo("10");
            assertThat(mapper.paramBefore("VAT_RATE","2026-11").number()).isEqualByComparingTo("11");
            long versionCount=mapper.versionCount("VAT_RATE");
            mapper.saveParam(metadata,"2026-11",new ParamSave(new BigDecimal("12"),null));
            assertThat(mapper.versionCount("VAT_RATE")).isEqualTo(versionCount);
            assertThat(mapper.versions("VAT_RATE",new PageRequest(0,100,"aply_bgn_ym",true),DataScope.ALL).get(0).number()).isEqualByComparingTo("12");
            assertThat(mapper.currentParams("2026-10",new PageRequest(0,100,"oprt_std_id",false),DataScope.ALL)).anyMatch(row -> row.key().equals("VAT_RATE") && row.number().compareTo(BigDecimal.TEN)==0);
            assertThat(mapper.currentParamCount("2026-10")).isPositive();
            session.rollback(); session.clearCache();
            assertThat(mapper.group("INTEGRATION_GROUP")).isNull(); assertThat(mapper.historyCount("POSITION")).isEqualTo(before);
            assertThat(mapper.allCodes("POSITION").stream().filter(row -> row.code().equals("TL")).findFirst().orElseThrow().attr1()).isEqualTo(old.attr1());
        }
    }
}
