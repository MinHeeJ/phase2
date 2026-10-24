package com.sicost.uploadsops;

import static org.assertj.core.api.Assertions.*;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class PersonnelUploadMapperTest {
    @Test void mapsEveryMethodAndNeverWritesGeneratedCostsOrUnknownExpenseProvenance()throws Exception {
        Configuration configuration=new Configuration();
        for(String file:new String[]{"mappers/uploads/UploadMapper.xml","mappers/uploads/PersonnelUploadMapper.xml"})try(var in=new ClassPathResource(file).getInputStream()){new XMLMapperBuilder(in,configuration,file,configuration.getSqlFragments()).parse();}
        for(var method:PersonnelUploadMapper.class.getDeclaredMethods())assertThat(configuration.hasStatement(PersonnelUploadMapper.class.getName()+"."+method.getName())).as(method.getName()).isTrue();
        String prefix=PersonnelUploadMapper.class.getName()+".";
        String insert=configuration.getMappedStatement(prefix+"insertSalary").getBoundSql(new PersonnelUploadModels.SalaryWrite(1,"2026-10",Map.of(),1,null)).getSql();
        assertThat(insert.substring(0,insert.indexOf("RETURNING"))).doesNotContain("indv_lbr_cst_amt","real_pay_amt");assertThat(insert).contains("RETURNING","indv_lbr_cst_amt","real_pay_amt");
        String delete=configuration.getMappedStatement(prefix+"deleteExpenses").getBoundSql("2026-10").getSql();assertThat(delete).contains("USING sys.upld","u.form_cd='B.1'","e.upld_id=u.upld_id","e.prj_id IS NULL","e.expns_ym=","'TEAM','PEXP'");
        assertThat(configuration.getMappedStatement(prefix+"insertDeveloper").getBoundSql(new PersonnelUploadModels.DeveloperWrite(1,"K-0001",Map.of(),1,null)).getSql()).contains("dev_id,mng_no").doesNotContain("ON CONFLICT","UPDATE");
    }
}
