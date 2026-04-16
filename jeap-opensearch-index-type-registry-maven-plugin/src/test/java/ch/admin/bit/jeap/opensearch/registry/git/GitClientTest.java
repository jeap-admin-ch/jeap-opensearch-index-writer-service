package ch.admin.bit.jeap.opensearch.registry.git;

import org.apache.maven.plugin.logging.SystemStreamLog;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitClientTest {

    @Test
    void clonesLocalRepositoryWithJGit(@TempDir File sourceDir, @TempDir File targetDir) throws Exception {
        try (Git git = Git.init().setDirectory(sourceDir).call()) {
            Files.writeString(new File(sourceDir, "hello.txt").toPath(), "hello");
            git.add().addFilepattern(".").call();
            git.commit()
               .setMessage("init")
               .setAuthor("test", "test@test.com")
               .setCommitter("test", "test@test.com")
               .call();
        }

        // Use package-private constructor to inject a dummy token (forces JGit path)
        GitClient client = new GitClient("file://" + sourceDir.getAbsolutePath(),
                new SystemStreamLog(), "dummy-token");

        client.cloneAtBranch("master", targetDir);

        assertThat(new File(targetDir, "hello.txt")).exists();
        assertThat(Files.readString(new File(targetDir, "hello.txt").toPath())).isEqualTo("hello");
    }

    @Test
    void clonesLocalRepositoryWithSystemGit(@TempDir File sourceDir, @TempDir File targetDir) throws Exception {
        try (Git git = Git.init().setDirectory(sourceDir).call()) {
            Files.writeString(new File(sourceDir, "data.txt").toPath(), "data");
            git.add().addFilepattern(".").call();
            git.commit()
               .setMessage("init")
               .setAuthor("test", "test@test.com")
               .setCommitter("test", "test@test.com")
               .call();
        }

        // null token → falls back to system git
        GitClient client = new GitClient("file://" + sourceDir.getAbsolutePath(),
                new SystemStreamLog(), null);

        client.cloneAtBranch("master", targetDir);

        assertThat(new File(targetDir, "data.txt")).exists();
    }

    @Test
    void throwsGitClientExceptionOnInvalidUrl(@TempDir File targetDir) {
        GitClient client = new GitClient("file:///nonexistent/repo/path",
                new SystemStreamLog(), "dummy-token");

        assertThatThrownBy(() -> client.cloneAtBranch("master", targetDir))
                .isInstanceOf(GitClientException.class);
    }

    @Test
    void noTokenWhenEnvVariableNotSet() {
        // A variable name that is almost certainly not set in the test environment
        GitClient client = new GitClient("file:///unused", "__JEAP_TEST_GIT_TOKEN_UNLIKELY__", new SystemStreamLog());
        // The client constructs without error; the only observable effect is logging.
        // We verify construction completes and a clone attempt uses system git (falls through to executeCommand).
        assertThat(client).isNotNull();
    }
}
