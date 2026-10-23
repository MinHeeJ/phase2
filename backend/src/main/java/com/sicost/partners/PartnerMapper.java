package com.sicost.partners;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import static com.sicost.partners.PartnerModels.*;

@Mapper
public interface PartnerMapper {
    List<PartnerListItem> list(PartnerQuery query);
    long count(PartnerQuery query);
    PartnerDetail detail(long id);
    PartnerDetail original(long id);
    String duplicateName(String biz);
    boolean activeCode(@Param("group") String group,@Param("code") String code);
    long nextId();
    void insert(PartnerWrite write);
    void update(PartnerWrite write);
    void appendHistory(PartnerHistory history);
}
