package io.github.cdevarenne.gctx.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cdevarenne.gctx.service.SemanticSearch;
import io.github.cdevarenne.gctx.telemetry.TelemetrySink;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * The quickstart is the one document a reader follows literally, so its instructions are checked.
 *
 * <p>Its acceptance criterion is that someone who has never seen this repo reaches a citation
 * block. A link that 404s or a subcommand that was renamed breaks that silently — prose passes
 * review while being false. Same discipline the bundle and index-spec copies get.
 */
class QuickstartTest {

    static final Path DOCS = Path.of("..", "docs");
    static final Path QUICKSTART = DOCS.resolve("quickstart.md");

    /** {@code [text](target)} — the target only, and only when it is a path, not a URL or anchor. */
    static final Pattern LINK = Pattern.compile("\\[[^\\]]+\\]\\((?!https?://|#)([^)#]+)[^)]*\\)");

    /**
     * {@code gctx <sub>} or {@code gctx.jar <sub>}, with any global options skipped so the capture
     * is the subcommand. The alias line {@code gctx='java …'} cannot match: a space is required.
     */
    static final Pattern INVOKED =
            Pattern.compile("\\bgctx(?:\\.jar)? (?:--[\\w-]+(?:[= ]\\S+)? )*([a-z][\\w-]*)");

    private static String text() {
        try {
            return Files.readString(QUICKSTART);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<String> subcommands() {
        return new CommandLine(new GctxCommand(SemanticSearch.UNAVAILABLE, TelemetrySink.NONE))
                .getSubcommands().keySet();
    }

    @Test
    void every_relative_link_resolves() {
        List<String> broken = LINK.matcher(text()).results()
                .map(match -> match.group(1))
                .filter(target -> !Files.exists(DOCS.resolve(target)))
                .toList();

        assertThat(broken).as("the quickstart links to paths that do not exist").isEmpty();
    }

    @Test
    void every_command_it_teaches_exists() {
        Matcher matcher = INVOKED.matcher(text());
        Set<String> taught = matcher.results().map(r -> r.group(1)).collect(Collectors.toSet());

        // Guards the guard: an expression that matched nothing would pass trivially.
        assertThat(taught).as("no gctx invocations were found in the quickstart").isNotEmpty();
        assertThat(subcommands())
                .as("the quickstart teaches subcommands the CLI does not have")
                .containsAll(taught);
    }
}
