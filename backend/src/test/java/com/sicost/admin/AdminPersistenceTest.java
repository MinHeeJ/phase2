package com.sicost.admin;

import com.fasterxml.jackson.databind.*;
import com.sicost.common.TestDatabaseIdentifier;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sicost.common.api.*;
import com.sicost.common.persistence.SchemaContractVerifier;
import com.sicost.common.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named="TEST_DATABASE_URL", matches="jdbc:postgresql:.*")
class AdminPersistenceTest {
    private String database;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private MockMvc mvc;
    private ObjectMapper json;
    private Connection admin() throws Exception { return DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"),System.getenv("TEST_DATABASE_USERNAME"),System.getenv("TEST_DATABASE_PASSWORD")); }
    @BeforeEach void restoreIndependentDatabase() throws Exception {
        database="sicost_admin_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=admin()) { TestDatabaseIdentifier.create(connection,database); }
        String source=System.getenv("TEST_DATABASE_URL"); int query=source.indexOf('?'); String base=query<0?source:source.substring(0,query);
        String url=base.substring(0,base.lastIndexOf('/')+1)+database+(query<0?"":source.substring(query));
        dataSource=new DriverManagerDataSource(url,System.getenv("TEST_DATABASE_USERNAME"),System.getenv("TEST_DATABASE_PASSWORD")); jdbc=new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        json=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var factory=new SqlSessionFactoryBean(); factory.setDataSource(dataSource); factory.setMapperLocations(new ClassPathResource("mappers/admin/AdminMapper.xml"));
        var configuration=new org.apache.ibatis.session.Configuration(); configuration.setJdbcTypeForNull(org.apache.ibatis.type.JdbcType.NULL); factory.setConfiguration(configuration);
        AdminMapper mapper=new SqlSessionTemplate(factory.getObject()).getMapper(AdminMapper.class);
        var target=new AdminService(mapper,new CurrentUser(),new PermissionService(),json,Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        var proxy=new ProxyFactory(target); proxy.setProxyTargetClass(true); proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),new AnnotationTransactionAttributeSource()));
        var validator=new LocalValidatorFactoryBean(); validator.afterPropertiesSet();
        mvc=MockMvcBuilders.standaloneSetup(new AdminController((AdminService)proxy.getProxy(),new CurrentUser(),json)).setControllerAdvice(new ApiExceptionHandler()).setValidator(validator).addFilters(new RequestIdFilter()).build();
    }
    @AfterEach void removeIndependentDatabase() throws Exception { if(database!=null) try(var connection=admin()) { TestDatabaseIdentifier.drop(connection,database); } }
    private JsonNode getData(String path) throws Exception { return json.readTree(mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data"); }
    private com.fasterxml.jackson.databind.node.ArrayNode codes(String group) throws Exception { return (com.fasterxml.jackson.databind.node.ArrayNode)getData("/api/admin/code-groups/"+group+"/codes?size=100").path("items"); }
    private void putCodes(String group,JsonNode body,int expected) throws Exception { mvc.perform(put("/api/admin/code-groups/"+group+"/codes").contentType("application/json").content(body.toString())).andExpect(status().is(expected)); }
    private List<Map<String,Object>> history() { return jdbc.queryForList("SELECT * FROM cmn.cd_hist ORDER BY cd_hist_id"); }
    @Test void metadataAndListCountsAreReal() throws Exception {
        new SchemaContractVerifier(dataSource,json).verify(); assertThat(getData("/api/admin/code-groups").path("total").asInt()).isEqualTo(36);
        assertThat(codes("JOB").size()).isEqualTo(12); assertThat(getData("/api/admin/excel-maps").path("total").asInt()).isEqualTo(jdbc.queryForObject("SELECT count(*) FROM cmn.excel_map",Integer.class));
        getData("/api/admin/params"); getData("/api/admin/params/VAT_RATE/versions");
    }
    @Test void fullSaveAddsCodeAndDisablesWithoutRemovingNames() throws Exception {
        var rows=codes("JOB"); var added=json.createObjectNode().put("dtl_cd","TEST1").put("cd_nm","테스트").put("sort_seq",999).put("use_yn","Y"); rows.add(added); putCodes("JOB",rows,200);
        assertThat(jdbc.queryForObject("SELECT use_yn FROM cmn.cd_dtl WHERE cd_grp_id='JOB' AND dtl_cd='TEST1'",String.class)).isEqualTo("Y");
        var ops=(com.fasterxml.jackson.databind.node.ObjectNode)java.util.stream.StreamSupport.stream(rows.spliterator(),false).filter(r->r.path("dtl_cd").asText().equals("OPS")).findFirst().orElseThrow(); ops.put("use_yn","N"); putCodes("JOB",rows,200);
        assertThat(codes("JOB").toString()).contains("OPS"); assertThat(jdbc.queryForObject("SELECT count(*) FROM cmn.cd_hist WHERE dtl_cd='OPS' AND cd_chg_ty_cd='DISABLE'",Integer.class)).isEqualTo(1);
        ops.put("use_yn","Y"); putCodes("JOB",rows,200); assertThat(jdbc.queryForObject("SELECT count(*) FROM cmn.cd_hist WHERE dtl_cd='OPS' AND cd_chg_ty_cd='ENABLE'",Integer.class)).isEqualTo(1);
    }
    @Test void duplicateAndMissingBatchRollbackEverything() throws Exception {
        var rows=codes("JOB"); var before=jdbc.queryForList("SELECT * FROM cmn.cd_dtl ORDER BY cd_grp_id,dtl_cd"); var hist=history();
        ((com.fasterxml.jackson.databind.node.ObjectNode)rows.get(0)).put("cd_nm","변경"); rows.add(rows.get(0).deepCopy()); putCodes("JOB",rows,409);
        assertThat(jdbc.queryForList("SELECT * FROM cmn.cd_dtl ORDER BY cd_grp_id,dtl_cd")).isEqualTo(before); assertThat(history()).isEqualTo(hist);
        rows=codes("JOB"); rows.remove(0); putCodes("JOB",rows,400); assertThat(history()).isEqualTo(hist);
    }
    @Test void systemMixedRequestDoesNotRename() throws Exception {
        var rows=codes("CONTRACT_TYPE"); var before=rows.deepCopy(); var hist=history(); var first=(com.fasterxml.jackson.databind.node.ObjectNode)rows.get(0); first.put("cd_nm","턴키(일괄)").put("use_yn","N"); putCodes("CONTRACT_TYPE",rows,409); assertThat(codes("CONTRACT_TYPE")).isEqualTo(before); assertThat(history()).isEqualTo(hist);
    }
    @Test void updatedAttributeAndLastWriterHistoryArePersisted() throws Exception {
        var rows=codes("POSITION"); var tl=(com.fasterxml.jackson.databind.node.ObjectNode)java.util.stream.StreamSupport.stream(rows.spliterator(),false).filter(r->r.path("dtl_cd").asText().equals("TL")).findFirst().orElseThrow(); tl.put("attr1_val","250000"); putCodes("POSITION",rows,200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cmn.cd_hist WHERE cd_grp_id='POSITION' AND dtl_cd='TL' AND item_nm='attr1_val' AND bfr_val='200000' AND aft_val='250000' AND chg_user_id IS NULL",Integer.class)).isEqualTo(1);
        tl.put("cd_nm","첫 값"); putCodes("POSITION",rows,200); tl.put("cd_nm","나중 값"); putCodes("POSITION",rows,200); assertThat(jdbc.queryForObject("SELECT cd_nm FROM cmn.cd_dtl WHERE cd_grp_id='POSITION' AND dtl_cd='TL'",String.class)).isEqualTo("나중 값");
        var filtered=getData("/api/admin/code-history?group=POSITION"); assertThat(filtered.path("total").asInt()).isEqualTo(filtered.path("items").size());
    }
    @Test void groupCreatePersistsBizSortAndAdd() throws Exception {
        int max=jdbc.queryForObject("SELECT max(sort_seq) FROM cmn.cd_grp",Integer.class);
        mvc.perform(post("/api/admin/code-groups").contentType("application/json").content("{\"cd_grp_id\":\"NEW_GROUP\",\"cd_grp_nm\":\"신규\"}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT sort_seq FROM cmn.cd_grp WHERE cd_grp_id='NEW_GROUP'",Integer.class)).isEqualTo(max+1); assertThat(jdbc.queryForObject("SELECT cd_grp_se_cd FROM cmn.cd_grp WHERE cd_grp_id='NEW_GROUP'",String.class)).isEqualTo("BIZ"); assertThat(getData("/api/admin/code-history?group=NEW_GROUP").path("items").get(0).path("dtl_cd").isNull()).isTrue();
    }
    @Test void excelGeneratedIdentityPatchAndTripleUniqueness() throws Exception {
        String body="{\"excel_lbl_nm\":\"테스트표기\",\"trns_cd_grp_id\":\"JOB\",\"trns_cd\":\"BE\",\"map_role_cd\":\"MAIN\"}";
        mvc.perform(post("/api/admin/excel-maps").contentType("application/json").content(body)).andExpect(status().isOk());
        long id=jdbc.queryForObject("SELECT map_id FROM cmn.excel_map WHERE excel_lbl_nm='테스트표기'",Long.class); var hist=history();
        mvc.perform(post("/api/admin/excel-maps").contentType("application/json").content(body)).andExpect(status().isConflict()); assertThat(history()).isEqualTo(hist);
        mvc.perform(patch("/api/admin/excel-maps/"+id).contentType("application/json").content("{\"map_role_cd\":\"SUB\",\"rmrk_desc\":\"수정\",\"use_yn\":\"N\"}")).andExpect(status().isOk());
        var row=jdbc.queryForMap("SELECT * FROM cmn.excel_map WHERE map_id=?",id); assertThat(row).containsEntry("excel_lbl_nm","테스트표기").containsEntry("trns_cd","BE").containsEntry("map_role_cd","SUB").containsEntry("rmrk_desc","수정").containsEntry("use_yn","N");
        assertThat(getData("/api/admin/excel-maps?size=100").toString()).contains("테스트표기","백엔드");
        mvc.perform(post("/api/admin/excel-maps").contentType("application/json").content(body.replace("\"BE\"","\"OPS\""))).andExpect(status().isOk());
    }
    @Test void paramUpsertPreservesPastAndRefusesClosed() throws Exception {
        var before=jdbc.queryForList("SELECT * FROM cmn.oprt_std ORDER BY oprt_std_id,aply_bgn_ym"); var hist=history();
        mvc.perform(put("/api/admin/params/VAT_RATE/versions/2026-09").contentType("application/json").content("{\"num_val\":11}")).andExpect(status().isConflict()); assertThat(history()).isEqualTo(hist); assertThat(jdbc.queryForList("SELECT * FROM cmn.oprt_std ORDER BY oprt_std_id,aply_bgn_ym")).isEqualTo(before);
        for(int value:List.of(11,12)) mvc.perform(put("/api/admin/params/VAT_RATE/versions/2026-11").contentType("application/json").content("{\"num_val\":"+value+"}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cmn.oprt_std WHERE oprt_std_id='VAT_RATE' AND aply_bgn_ym='2026-11'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT num_val FROM cmn.oprt_std WHERE oprt_std_id='VAT_RATE' AND aply_bgn_ym<='2026-10' ORDER BY aply_bgn_ym DESC LIMIT 1",java.math.BigDecimal.class)).isEqualByComparingTo("10");
        assertThat(getData("/api/admin/params/VAT_RATE/versions").path("items").get(0).path("num_val").asInt()).isEqualTo(12);
    }
    @Test void historyLengthFailureRollsBackEarlierDml() throws Exception {
        var rows=codes("JOB"); var before=rows.deepCopy(); var hist=history();
        ((com.fasterxml.jackson.databind.node.ObjectNode)rows.get(0)).put("cd_nm","이전 변경");
        ((com.fasterxml.jackson.databind.node.ObjectNode)rows.get(1)).put("attr1_val","\\".repeat(100));
        // Plain code history fits; an excel JSON value can exceed the history column after escaping.
        String label="\\".repeat(100); String body=json.writeValueAsString(Map.of("excel_lbl_nm",label,"trns_cd_grp_id","JOB","trns_cd","BE","map_role_cd","MAIN"));
        mvc.perform(post("/api/admin/excel-maps").contentType("application/json").content(body)).andExpect(status().isOk());
        long id=jdbc.queryForObject("SELECT map_id FROM cmn.excel_map WHERE excel_lbl_nm=?",Long.class,label);
        var histAfter=history(); String memo="\u0001".repeat(200);
        mvc.perform(patch("/api/admin/excel-maps/"+id).contentType("application/json").content(json.writeValueAsString(Map.of("map_role_cd","SUB","rmrk_desc",memo)))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT map_role_cd FROM cmn.excel_map WHERE map_id=?",String.class,id)).isEqualTo("MAIN"); assertThat(history()).isEqualTo(histAfter); assertThat(codes("JOB")).isEqualTo(before);
    }
}
