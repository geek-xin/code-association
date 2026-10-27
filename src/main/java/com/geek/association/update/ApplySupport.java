package com.geek.association.update;

import java.util.Locale;

/**
 * Whether "apply update" can work here, and - when it cannot - the one sentence plus the one
 * command that gets the user unstuck.
 *
 * Split out of {@link UpdateService#status()} because the answer is a policy decision with three
 * inputs (launch mode, operating system, whether a jar was found) and it is the part that was
 * wrong: the old text asserted "you are probably in an IDE" for every case without a jar, which is
 * false for the way this project is actually developed ({@code mvn spring-boot:run}, where the
 * script is the launcher and the IDE is not involved at all).
 *
 * @param launchMode how the process was started, for the settings card to show as-is
 * @param supported  true when a jar exists that this process can overwrite in place
 * @param reason     why it is not supported, or null when it is
 * @param hint       a command that makes a later apply possible, or null
 */
public record ApplySupport(LaunchMode launchMode, boolean supported, String reason, String hint) {

    /**
     * The decision.
     *
     * Order matters: a development run and a Windows jar run both end up "unsupported", but for
     * unrelated reasons with unrelated fixes, so the launch mode is checked first and the platform
     * only afterwards. A jar on Windows is a supported *installation* that cannot be replaced
     * while it runs - a different problem from having no jar at all.
     */
    public static ApplySupport of(LaunchMode launchMode, boolean windows) {
        if (launchMode == LaunchMode.DEV) {
            return new ApplySupport(launchMode, false,
                    "当前是开发模式启动（mvn spring-boot:run、IDE 或测试直接运行 target/classes），"
                            + "没有可替换的 jar，所以界面里的「应用更新」不会生效。",
                    "mvn -q -DskipTests package && ./start.sh");
        }
        if (launchMode == LaunchMode.OTHER) {
            return new ApplySupport(launchMode, false,
                    "没有定位到正在运行的 jar（可能使用了自定义 classloader 或被裁剪的 classpath），"
                            + "无法就地替换。",
                    "java -jar target/code-association.jar");
        }
        if (windows) {
            return new ApplySupport(launchMode, false,
                    "Windows 会独占锁定正在运行的 jar，服务运行时无法替换，请先停止服务再手动替换。",
                    "stop.bat  →  替换 target\\code-association.jar  →  start.bat");
        }
        return new ApplySupport(launchMode, true, null, null);
    }

    /** The decision for this process. */
    public static ApplySupport detect() {
        return of(LaunchMode.detect(), isWindows());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }
}
