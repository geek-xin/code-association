package com.geek.association.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A semantic change detected between the base and the head revision. */
public class ChangeRecord {

    public long id;
    public String changeType;
    public String symbolType;
    public String qualifiedName;
    public String signature;
    public String modulePath;
    public String filePath;
    public int startLine;
    public int endLine;
    public String oldValue;
    public String newValue;
    public String semanticSummary;
    public double riskSeed = 0.3;
    public String symbolKey;
    public String detail;
    public List<String> tags = new ArrayList<>();
    public Map<String, Object> extra = new LinkedHashMap<>();
}
