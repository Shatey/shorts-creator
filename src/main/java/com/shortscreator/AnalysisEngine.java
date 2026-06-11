package com.shortscreator;

public enum AnalysisEngine {
    AUTO("auto"),
    JAVA("java"),
    PYTHON("python");

    private final String code;

    AnalysisEngine(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
