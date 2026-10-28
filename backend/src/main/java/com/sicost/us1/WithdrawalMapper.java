package com.sicost.us1;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.us1.WithdrawalModels.*;

@Mapper
public interface WithdrawalMapper {
    List<Source> sources();
}
