package com.shortscreator;

import java.util.concurrent.CompletableFuture;

public final class PreviewSession {
    private final Process ffmpegProcess;
    private final Process ffplayProcess;
    private final CompletableFuture<Void> exitFuture;

    public PreviewSession(Process ffmpegProcess, Process ffplayProcess, CompletableFuture<Void> exitFuture) {
        this.ffmpegProcess = ffmpegProcess;
        this.ffplayProcess = ffplayProcess;
        this.exitFuture = exitFuture;
    }

    public boolean isAlive() {
        return ffmpegProcess.isAlive() || ffplayProcess.isAlive();
    }

    public void destroy() {
        if (ffmpegProcess.isAlive()) {
            ffmpegProcess.destroy();
        }
        if (ffplayProcess.isAlive()) {
            ffplayProcess.destroy();
        }
    }

    public CompletableFuture<Void> onExit() {
        return exitFuture;
    }
}
