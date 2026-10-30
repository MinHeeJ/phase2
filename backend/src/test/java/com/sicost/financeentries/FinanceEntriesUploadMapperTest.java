package com.sicost.financeentries;

import java.io.InputStream;
import java.util.List;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.sicost.financeentries.FinanceEntriesUploadModels.*;
import static com.sicost.entries.FinanceEntryModels.EntryInput;

class FinanceEntriesUploadMapperTest {
    @Test void loadsAllStatementsAndBindsEveryWriteRecordProperty()throws Exception {
        var configuration=new Configuration();String resource="mappers/financeentries/FinanceEntriesUploadMapper.xml";
        try(InputStream input=getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();new XMLMapperBuilder(input,configuration,resource,configuration.getSqlFragments()).parse();
        }
        String prefix=FinanceEntriesUploadMapper.class.getName()+".";
        for(var method:FinanceEntriesUploadMapper.class.getDeclaredMethods())assertThat(configuration.hasStatement(prefix+method.getName())).as(method.getName()).isTrue();
        var entry=new EntryInput("2026-10","SGA","경비",0,17,null,"N","N","비고",null,"N");
        var parsed=new Parsed("2026-10",List.of(new Preview(5,"NORMAL",entry)),List.of());
        var review=new Review(42,"2026-10","재무.xlsx","/data/upload.xlsx","checksum",parsed);
        var summary=new Summary("42",0,0,List.of(),1,true,0,0,parsed.rows(),"전체 교체");
        var writes=java.util.Map.of("insert",new EntryWrite(entry,42,null),"saveReview",new ReviewWrite(review,summary,"APPLIED",null));
        writes.forEach((name,write)-> {
            var meta=configuration.newMetaObject(write);var sql=configuration.getMappedStatement(prefix+name).getBoundSql(write);
            for(var parameter:sql.getParameterMappings())assertThatCode(()->meta.getValue(parameter.getProperty())).doesNotThrowAnyException();
        });
        assertThat(configuration.getMappedStatement(prefix+"insert").isDirtySelect()).isTrue();
        assertThat(configuration.getMappedStatement(prefix+"insert").getBoundSql(new EntryWrite(entry,42,null)).getSql()).contains("RETURNING entry_id","upld_id");
    }
}
