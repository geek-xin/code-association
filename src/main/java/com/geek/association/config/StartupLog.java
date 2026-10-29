package com.geek.association.config;

import com.geek.association.update.Version;
import com.geek.association.util.LogEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Two structured lines that bracket the lifetime of one process: {@code app.start} and
 * {@code app.stop}.
 *
 * Why this class exists at all: every other milestone in the log is only interpretable if the run
 * it belongs to can be identified afterwards. "analysis.failed at 02:30" is a different story
 * depending on which version was running, on which port, from which data directory, with the
 * scheduler on or off - and after a restart the previous run's {@code logs/} directory is the only
 * place those facts survive. One line at the top of the file answers them without re-deriving
 * anything from configuration files that may have been edited since.
 *
 * Deliberately prints no secrets: no {@code code-association.update.token}, no AI API key, no
 * GitLab personal access token, no proxy URL. Only switches ("on"/"off"), "set"/"none" and paths,
 * which is what makes the line safe to paste into a bug report.
 */
@Component
public class StartupLog {

    private static final Logger log = LoggerFactory.getLogger(StartupLog.class);

    private static final long BYTES_PER_MB = 1024L * 1024L;

    private final ApplicationContext context;
    private final AppProperties properties;
    private final Environment environment;

    /** Guards the stop line: more than one context closing must not produce two of them. */
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    public StartupLog(ApplicationContext context, AppProperties properties, Environment environment) {
        this.context = context;
        this.properties = properties;
        this.environment = environment;
    }

    /**
     * The startup banner, once the application is actually ready.
     *
     * {@link ApplicationReadyEvent} rather than {@code @PostConstruct}: the port is only known after
     * the web server has bound it, and a line printed before that could name a port that then failed
     * to bind - the one case where the banner must not lie.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info(LogEvents.of("app.start",
                "version", Version.running(),
                "mode", mode(),
                "port", port(),
                "dataDir", properties.dataPath(),
                "logDir", properties.logPath(),
                "java", System.getProperty("java.version", "-"),
                "os", os(),
                "maxHeapMb", maxHeapMb(),
                "schedule", onOff(properties.getSchedule().isEnabled()),
                "update", onOff(properties.getUpdate().isEnabled()),
                // "ready" is AppProperties.Ai.ready(), the single definition the UI and the AI
                // endpoints use, so the banner cannot claim AI works when the settings card says it
                // does not.
                "ai", properties.getAi().ready() ? "ready" : "not-ready",
                // Presence only. The value is a credential and must never reach a log file; this
                // field answers "did this process see a GitLab token" without answering "which".
                "gitlabToken", properties.getGitlab() != null
                        && properties.getGitlab().getToken() != null
                        && !properties.getGitlab().getToken().isBlank() ? "set" : "none",
                "pid", ProcessHandle.current().pid()));
    }

    /**
     * The shutdown line, with how long this instance lived.
     *
     * JVM uptime rather than a field stamped on the ready event: a process that crashed before it
     * became ready still has a meaningful uptime, and "ran 3 seconds" versus "ran 3 days" is the
     * first thing a restart loop investigation needs.
     */
    @EventListener(ContextClosedEvent.class)
    public void onClosed() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        log.info(LogEvents.of("app.stop",
                "version", Version.running(),
                "uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime()));
    }

    /** Web or CLI: the analyze sub-command runs without a web server, and the log says which. */
    private String mode() {
        return context instanceof WebServerApplicationContext ? "web" : "cli";
    }

    /**
     * The port the server actually bound.
     *
     * Read from the web server rather than from {@code server.port}: with {@code --server.port=0}
     * the configured value is 0 and the real one is ephemeral, and the banner is where somebody
     * looks to find out which port to curl.
     */
    private String port() {
        // CLI 模式（analyze 子命令）根本没有监听端口；打上配置里的 9996 会让人以为服务在跑。
        if (!"web".equals(mode())) {
            return "-";
        }
        if (context instanceof WebServerApplicationContext web) {
            try {
                WebServer server = web.getWebServer();
                if (server != null) {
                    return String.valueOf(server.getPort());
                }
            } catch (RuntimeException e) {
                // No server yet (or already stopped); the environment below still has an answer.
            }
        }
        String local = environment.getProperty("local.server.port");
        if (local != null && !local.isBlank()) {
            return local;
        }
        String configured = environment.getProperty("server.port");
        return configured == null || configured.isBlank() ? "-" : configured;
    }

    private static String os() {
        return System.getProperty("os.name", "-") + " " + System.getProperty("os.arch", "-");
    }

    /** Ceiling of the heap, which is what "ran out of memory" gets compared against later. */
    private static long maxHeapMb() {
        return Runtime.getRuntime().maxMemory() / BYTES_PER_MB;
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }
}
