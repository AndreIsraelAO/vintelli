package dev.omatheusmesmo.vintelli.local;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability.TerminalRequest;
import io.quarkus.arc.Unremovable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Local terminal of the terminal capability. The command line is handed to a
 * real shell ({@code /bin/sh -c} on POSIX, {@code cmd.exe /c} on Windows),
 * so pipes, redirects, globbing, variable expansion and {@code cd} are
 * interpreted by that shell. The working directory stays confined to the
 * project root. Commands that control the machine or erase data recursively
 * come back as {@code REQUIRES_CONFIRMATION} and only run when the caller
 * reissues the request carrying an explicit confirmation.
 *
 * <p>Working directory policy: every command runs inside the
 * {@link LocalProjectRoot}, with no opt-out. A relative working directory
 * resolves against the root, and any directory that lands outside it, through
 * {@code ..} or behind a symlink, fails before a process starts. A command that
 * times out returns the output it produced so far in the failure detail.
 *
 * <p>The confirmation gate is a heuristic on the program name and is
 * documented on {@link DangerousCommands}: it inspects the first token of the
 * parsed line and does not follow wrappers or indirections.
 */
@Unremovable
@Singleton
public final class LocalTerminalCapability implements TerminalCapability {

    private final LocalProjectRoot projectRoot;
    private final ProcessRunner runner;
    private final Duration defaultTimeout;

    @Inject
    LocalTerminalCapability(LocalProjectRoot projectRoot, ProcessRunner runner,
            @ConfigProperty(name = "vintelli.local.process-timeout", defaultValue = "60M") Duration timeout) {
        this.projectRoot = projectRoot;
        this.runner = runner;
        this.defaultTimeout = timeout;
    }

    /**
     * Non CDI factory for tests, mirroring {@link LocalRunCapability#of}.
     */
    public static LocalTerminalCapability of(LocalProjectRoot projectRoot, Duration timeout) {
        return new LocalTerminalCapability(projectRoot, new ProcessRunner(), timeout);
    }

    @Override
    public String identifier() {
        return "local";
    }

    @Override
    public ProviderHealth health() {
        return ProviderHealth.available();
    }

    @Override
    public String provenance() {
        return "local shell process";
    }

    @Override
    public CapabilityResult<TerminalResult> execute(TerminalRequest request) {
        List<String> parsed;
        try {
            parsed = TerminalCommandParser.parse(request.commandLine());
        } catch (IllegalArgumentException e) {
            return CapabilityResult.failed(new CapabilityFailure("The command line cannot be parsed into a program",
                    e.getMessage()));
        }
        List<String> args = parsed.subList(1, parsed.size());
        if (!request.confirmed() && DangerousCommands.requiresConfirmation(parsed.get(0), args)) {
            return CapabilityResult.ok(new TerminalResult(TerminalOutcome.REQUIRES_CONFIRMATION, -1, ""));
        }
        return run(request);
    }

    private CapabilityResult<TerminalResult> run(TerminalRequest request) {
        Path inside = projectRoot.confine(request.workingDirectory());
        if (inside == null) {
            return CapabilityResult.failed(new CapabilityFailure(
                    "The working directory is outside the project root or cannot be resolved",
                    request.workingDirectory().toString()));
        }
        List<String> command = shellCommand(request.commandLine());
        ProcessRunner.ProcessResult result;
        try {
            result = runner.run(new ProcessRunner.CommandPlan(command, inside, Map.of(), defaultTimeout));
        } catch (ProcessRunner.UncheckedProcessFailure e) {
            return CapabilityResult.failed(new CapabilityFailure("Cannot start the command", e.getMessage()));
        }
        return switch (result.outcome()) {
            case EXITED, CANCELLED -> CapabilityResult.ok(new TerminalResult(
                    TerminalOutcome.EXECUTED, result.exitCode().orElse(-1), result.output()));
            case TIMED_OUT -> CapabilityResult.failed(new CapabilityFailure(
                    "The command timed out", result.output()));
        };
    }

    private static List<String> shellCommand(String commandLine) {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            return List.of("cmd.exe", "/d", "/c", commandLine);
        }
        return List.of("/bin/sh", "-c", commandLine);
    }
}
