package com.sicost.dispatchattendance2;

import static org.assertj.core.api.Assertions.*;
import static com.sicost.dispatchattendance2.DispatchAttendanceUploadModels.*;
import java.time.YearMonth;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class DispatchAttendanceUploadMapperTest {
    Configuration configuration() throws Exception {
        var c=new Configuration();String path="mappers/dispatchattendance2/DispatchAttendanceUploadMapper.xml";
        try(var in=new ClassPathResource(path).getInputStream()){new XMLMapperBuilder(in,c,path,c.getSqlFragments()).parse();}return c;
    }
    String sql(Configuration c,String method,Object parameter){return c.getMappedStatement(DispatchAttendanceUploadMapper.class.getName()+"."+method).getBoundSql(parameter).getSql().replaceAll("\\s+"," ").trim();}
    @Test void explicitConstructorLabelsAndTypesMatchEveryRecord()throws Exception {
        var c=configuration();var prefix=DispatchAttendanceUploadMapper.class.getName()+".";
        for(var method:DispatchAttendanceUploadMapper.class.getDeclaredMethods())assertThat(c.hasStatement(prefix+method.getName())).isTrue();
        for(var entry:Map.of("project",Project.class,"assignment",Assignment.class,"attendance",Attendance.class).entrySet()){
            var mapping=c.getResultMap(prefix+entry.getKey()).getConstructorResultMappings();var fields=entry.getValue().getRecordComponents();
            assertThat(mapping).hasSize(fields.length);
            for(int n=0;n<fields.length;n++){assertThat(mapping.get(n).getColumn()).isEqualTo(fields[n].getName());assertThat(mapping.get(n).getJavaType()).isEqualTo(fields[n].getType());}
        }
    }
    @Test void scopeDeletionIsWholeScopeAndConfirmedCheckDoesNotDependOnFilePeople()throws Exception {
        var c=configuration();var m=YearMonth.of(2026,10);var scope=new Scope(1,m.toString(),m.atDay(1),m.atEndOfMonth());
        assertThat(sql(c,"delete",scope)).isEqualTo("DELETE FROM bill.dspch_atnd WHERE prj_id=? AND work_ym=?");
        assertThat(sql(c,"billed",scope)).contains("a.prj_id=?","d.work_ym=?","b.bill_stts_cd='CONFIRM'").doesNotContain("dev_id=?","DRAFT");
        assertThat(sql(c,"assignments",scope)).contains("a.asgn_stts_cd!='CANCELLED'","greatest(?,a.bgn_dt)","max(x.aply_bgn_dt)","m.mm_qty AS assignment_mm");
    }
    @Test void attendanceInsertUsesV1FieldsAndGeneratedIdWithoutSourceWrites()throws Exception {
        var c=configuration();String text=sql(c,"insert",new AttendanceWrite(new Scope(1,"2026-10",null,null),new Attendance(null,2,3,null,null,null,null),4,null));
        assertThat(text).contains("INSERT INTO bill.dspch_atnd","mth_work_day_qty","RETURNING atnd_id").doesNotContain("asgn.asgn_mth","bill.bill_dtl");
    }
}
