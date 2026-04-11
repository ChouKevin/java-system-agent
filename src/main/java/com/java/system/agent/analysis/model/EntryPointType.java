package com.java.system.agent.analysis.model;

import java.util.List;

public enum EntryPointType {
    API, MQ, SCHEDULE;

    public static final List<EntryPointType> ALL = List.of(values());
}
