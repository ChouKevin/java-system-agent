package com.java.system.agent.slack;

import com.slack.api.bolt.socket_mode.SocketModeApp;
import org.springframework.context.SmartLifecycle;

import java.util.Objects;

/**
 * 管理單一 Slack Socket Mode connection 的應用 lifecycle
 */
public final class SlackSocketModeManager implements SmartLifecycle {

    private static final int PHASE = 200;

    private final SocketModeApp socketModeApp;
    private volatile boolean running;

    public SlackSocketModeManager(SocketModeApp socketModeApp) {
        this.socketModeApp = Objects.requireNonNull(socketModeApp, "Socket Mode app must not be null");
    }

    /**
     * 非阻塞地啟動 Socket Mode
     */
    @Override
    public synchronized void start() {
        try {
            socketModeApp.startAsync();
            running = true;
        } catch (Exception exception) {
            throw new IllegalStateException("Slack Socket Mode start failed", exception);
        }
    }

    /**
     * 關閉 Socket Mode connection
     */
    @Override
    public synchronized void stop() {
        stop(() -> { });
    }

    @Override
    public synchronized void stop(Runnable callback) {
        Objects.requireNonNull(callback, "lifecycle stop callback must not be null");
        try {
            socketModeApp.stop();
            running = false;
        } catch (Exception exception) {
            throw new IllegalStateException("Slack Socket Mode stop failed", exception);
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
