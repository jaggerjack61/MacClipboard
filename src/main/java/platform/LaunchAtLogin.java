package platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * "Launch at login" implemented with a per-user LaunchAgent plist. Works for both
 * the packaged .app bundle and (best effort) a {@code ./gradlew run} launch.
 */
public final class LaunchAtLogin {

    private static final String LABEL = "local.clipboardhistory.agent";

    private LaunchAtLogin() {
    }

    public static Path plistFile() {
        return Path.of(System.getProperty("user.home"), "Library", "LaunchAgents", LABEL + ".plist");
    }

    public static boolean isEnabled() {
        return Files.exists(plistFile());
    }

    /**
     * Installs (or removes) the login item.
     *
     * @param launchCommand command the agent should run, e.g. ["open", "/Applications/Clipboard.app"]
     * @return true on success
     */
    public static boolean setEnabled(boolean enabled, List<String> launchCommand) {
        Path file = plistFile();
        try {
            if (!enabled) {
                Files.deleteIfExists(file);
                run("launchctl", "bootout", "gui/" + uid() + "/" + LABEL);
                return true;
            }
            Files.createDirectories(file.getParent());
            String plist = buildPlist(launchCommand);
            Files.writeString(file, plist);
            run("launchctl", "bootout", "gui/" + uid() + "/" + LABEL);
            run("launchctl", "bootstrap", "gui/" + uid(), file.toString());
            return true;
        } catch (IOException e) {
            System.getLogger("platform.LaunchAtLogin")
                    .log(System.Logger.Level.WARNING, "could not update login item", e);
            return false;
        }
    }

    private static String uid() {
        try {
            Process p = new ProcessBuilder("/usr/bin/id", "-u").start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            if (!out.isEmpty()) {
                return out;
            }
        } catch (IOException e) {
            // fall through
        }
        return "501";
    }

    private static String buildPlist(List<String> command) {
        StringBuilder args = new StringBuilder();
        for (String arg : command) {
            args.append("        <string>").append(escape(arg)).append("</string>\n");
        }
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                    <key>Label</key>
                    <string>""" + LABEL + """
                </string>
                    <key>ProgramArguments</key>
                    <array>
                """ + args + """
                    </array>
                    <key>RunAtLoad</key>
                    <true/>
                </dict>
                </plist>
                """;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void run(String... cmd) {
        try {
            new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (IOException e) {
            // best effort
        }
    }
}
