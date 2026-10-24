package com.sicost.partners;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.partners.PartnerModels.*;

@Mapper
public interface PartnerMapper {
    List<PartnerListItem> list(PartnerQuery query);
    long count(PartnerQuery query);
    PartnerDetail detail(long id);
    PartnerDetail lock(long id);
    long nextId();
    String duplicateName(String biz);
    boolean activeCode(PartnerCode code);
    Long insert(PartnerWrite write);
    void update(PartnerWrite write);
    void appendHistory(PartnerHistory history);
}
