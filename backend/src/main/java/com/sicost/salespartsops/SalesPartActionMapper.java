package com.sicost.salespartsops;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.salespartsops.SalesPartActionModels.*;

@Mapper
public interface SalesPartActionMapper {
    Part part(String id);
    Part lockPart(String id);
    List<Part> allParts();
    List<Option> options();
    List<Standard> standards(Read query);
    String lastClosed();
    long people(Read query);
    long projects(String id);
    boolean nameExists(PartWrite write);
    void rename(PartWrite write);
    void state(PartWrite write);
    void deleteStandard(String id);
    long standardReferences(String id);
    void closeStandard(EndWrite write);
    String insertStandard(StandardWrite write);
    void history(History entry);
    void download(Download entry);
}
