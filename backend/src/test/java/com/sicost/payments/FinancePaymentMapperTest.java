package com.sicost.payments;

import static org.assertj.core.api.Assertions.*;
import static com.sicost.payments.FinancePaymentModels.*;
import java.time.LocalDate;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class FinancePaymentMapperTest {
    Configuration configuration() throws Exception {
        var c=new Configuration();String path="mappers/payments/FinancePaymentMapper.xml";
        try(var in=new ClassPathResource(path).getInputStream()) {new XMLMapperBuilder(in,c,path,c.getSqlFragments()).parse();}
        return c;
    }
    @Test void everyMapperMethodHasAnXmlStatement() throws Exception {
        var c=configuration();
        for(var method:FinancePaymentMapper.class.getDeclaredMethods())
            assertThat(c.hasStatement(FinancePaymentMapper.class.getName()+"."+method.getName())).as(method.getName()).isTrue();
    }
    @Test void insertBindsRealRecordComponentsAndReturnsGeneratedIdentity() throws Exception {
        var c=configuration();
        var input=new PaymentInput("2026-10",1,"ETC","ETC",null,null,"받는 사람",1000000,0,0,
            LocalDate.parse("2026-10-10"),"004","001234","예금주",null,null);
        var write=new PaymentWrite(0,input,"MANUAL",null,null,null);
        var b=c.getMappedStatement(FinancePaymentMapper.class.getName()+".insert").getBoundSql(write);
        for(var p:b.getParameterMappings()) assertThatCode(()->c.newMetaObject(write).getValue(p.getProperty())).doesNotThrowAnyException();
        assertThat(b.getSql()).contains("rcvr_nm","bank_cd","acnt_no","RETURNING pay_id");
    }
    @Test void paymentConstructorExactlyMatchesNullableSqlTypes() throws Exception {
        var mappings=configuration().getResultMap(FinancePaymentMapper.class.getName()+".payment").getConstructorResultMappings();
        assertThat(mappings).hasSize(Payment.class.getRecordComponents().length);
        var fields=Payment.class.getRecordComponents();
        for(int i=0;i<fields.length;i++) assertThat(mappings.get(i).getJavaType()).isEqualTo(fields[i].getType());
    }
    @Test void sourceUpsertHasPartialUniqueAndNeverUpdatesExecutionsOrPaid() throws Exception {
        var sql=configuration().getMappedStatement(FinancePaymentMapper.class.getName()+".generate").getBoundSql(null).getSql();
        assertThat(sql).contains("ON CONFLICT(pay_src_cd,src_rec_id,pay_se_cd,pay_ym)","cry_pay_id IS NULL","pay_stts_cd='PLAN'","NOT EXISTS");
    }
}
