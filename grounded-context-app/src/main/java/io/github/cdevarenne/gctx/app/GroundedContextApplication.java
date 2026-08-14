package io.github.cdevarenne.gctx.app;

import io.github.cdevarenne.gctx.bundle.BundleException;
import io.github.cdevarenne.gctx.service.SemanticSearch;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import picocli.CommandLine;
import picocli.CommandLine.IFactory;

/**
 * Spring Boot entry point for the CLI.
 *
 * <p>Spring's job here is wiring, not behavior: it supplies whichever {@link SemanticSearch} is
 * available and hands control to picocli. Every answer is still produced by the core module,
 * which knows nothing about Spring.
 */
@SpringBootApplication
public class GroundedContextApplication implements CommandLineRunner {

    private final IFactory factory;
    private final SemanticSearch semantic;
    private int exitCode;

    public GroundedContextApplication(IFactory factory, SemanticSearch semantic) {
        this.factory = factory;
        this.semantic = semantic;
    }

    /**
     * The fallback engine. Registered only when no real one is configured, so the exploratory
     * branch refuses instead of failing when Elasticsearch is absent.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(SemanticSearch.class)
    static SemanticSearch unavailableSemanticSearch() {
        return SemanticSearch.UNAVAILABLE;
    }

    @Override
    public void run(String... args) {
        CommandLine commandLine = new CommandLine(new GctxCommand(semantic), factory);
        commandLine.setExecutionExceptionHandler((exception, cmd, parseResult) -> {
            // A malformed bundle or an unparseable date is a real error, distinct from the
            // refusal that exit code 1 signals.
            if (exception instanceof BundleException
                    || exception instanceof java.time.format.DateTimeParseException) {
                System.err.println("error: " + exception.getMessage());
                return GctxCommand.EXIT_ERROR;
            }
            throw exception;
        });
        exitCode = commandLine.execute(args);
    }

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(
                SpringApplication.run(GroundedContextApplication.class, args)));
    }

    @Bean
    org.springframework.boot.ExitCodeGenerator exitCodeGenerator() {
        return () -> exitCode;
    }
}
