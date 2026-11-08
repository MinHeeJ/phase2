package com.sicost.contacts;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.common.security.SecurityModels.*;

@Mapper
public interface ContactMapper {
    List<Resource> developerResources(Long id);
    List<Resource> partnerResources(Long id);
    Contact developer(Long id);
    Contact partner(Long id);
    record Contact(String phone,String email,String financialEmail,boolean active) {}
}
