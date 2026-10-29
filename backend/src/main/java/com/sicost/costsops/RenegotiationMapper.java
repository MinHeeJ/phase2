package com.sicost.costsops;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.costsops.RenegotiationModels.*;

@Mapper
public interface RenegotiationMapper {
    List<Source> candidates(SourceQuery query);
    void download(Download download);
}
