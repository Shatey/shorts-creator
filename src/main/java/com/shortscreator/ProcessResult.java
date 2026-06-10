package com.shortscreator;

public record ProcessResult(int exitCode, String output) {
    public boolean isSuccess() {
        return exitCode == 0;
    }
}
