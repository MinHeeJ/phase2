package com.sicost.uploadsops;

import static org.assertj.core.api.Assertions.*;
import com.sicost.partners.PartnerModels.PartnerSave;
import static com.sicost.uploadsops.UploadModels.*;
import java.util.List;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class UploadMapperTest {
    @Test void mapperXmlParsesAllStatementsAndBindsRecordProperties()throws Exception {
        Configuration configuration=new Configuration();
        try(var in=new ClassPathResource("mappers/uploads/UploadMapper.xml").getInputStream()){new XMLMapperBuilder(in,configuration,"mappers/uploads/UploadMapper.xml",configuration.getSqlFragments()).parse();}
        for(var method:UploadMapper.class.getDeclaredMethods())assertThat(configuration.hasStatement(UploadMapper.class.getName()+"."+method.getName())).as(method.getName()).isTrue();
        var context=new UploadContext("거래처.xlsx","/server/generated.xlsx",new ParsedUpload(List.of(),List.of()));var result=new UploadValidationResult("거래처.xlsx",null,null,1,1,0,0,List.of());
        var insert=configuration.getMappedStatement(UploadMapper.class.getName()+".insertUpload");var sql=insert.getBoundSql(new UploadWrite(context,result,"APPLIED",null));
        assertThat(sql.getSql()).contains("INSERT INTO sys.upld","RETURNING upld_id");assertThat(sql.getParameterMappings()).extracting("property").contains("context.path","result.totalRows","actor");
        var partner=new PartnerSave("CUST","거래처","123-45-67890",null,null,null,null,null,"tax@company.co.kr",null,null,null);
        var business=configuration.getMappedStatement(UploadMapper.class.getName()+".insertPartner").getBoundSql(new PartnerImport(1,"C-001",partner,1,null));
        assertThat(business.getSql()).contains("ptnr_id,ptnr_no","upld_id","RETURNING ptnr_id");assertThat(business.getParameterMappings()).extracting("property").contains("id","no","input.biz_reg_no","uploadId");
    }
}
