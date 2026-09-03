package com.smartquery.orchestration;

import java.util.Set;

/** Operator families supported by the V2 orchestration control plane. */
public final class OperatorTypes {
    private OperatorTypes() {}

    public static final String DATA = "DATA";
    public static final String RULE = "RULE";
    public static final String ML = "ML";
    public static final String AGENT = "AGENT";
    public static final String OUTPUT = "OUTPUT";

    public static final Set<String> ALL = Set.of(DATA, RULE, ML, AGENT, OUTPUT);
}
