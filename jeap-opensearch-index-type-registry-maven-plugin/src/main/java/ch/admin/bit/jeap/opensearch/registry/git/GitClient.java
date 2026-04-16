package ch.admin.bit.jeap.opensearch.registry.git;

import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

public class GitClient {
    private final String gitUrl;
    private final String token;
    private final Log log;

    public GitClient(String gitUrl, String gitTokenEnvVariableName, Log log) {
        this.gitUrl = gitUrl;
        this.log = (log != null ? log : new SystemStreamLog());
        this.token = resolveToken(gitTokenEnvVariableName);
    }

    // Package-private for testing — allows injecting a token without requiring an env variable
    GitClient(String gitUrl, Log log, String token) {
        this.gitUrl = gitUrl;
        this.log = (log != null ? log : new SystemStreamLog());
        this.token = token;
    }

    public void cloneAtBranch(String branch, File targetDirectory) {
        if (token != null) {
            cloneWithToken(branch, targetDirectory);
        } else {
            cloneWithSystemGit(branch, targetDirectory);
        }
    }

    @SuppressWarnings("EmptyTryBlock")
    private void cloneWithToken(String branch, File targetDirectory) {
        log.info("Using JGit with token to clone '%s' at branch '%s'.".formatted(gitUrl, branch));
        try (Git ignored = Git.cloneRepository()
                .setURI(gitUrl)
                .setBranch(branch)
                .setDirectory(targetDirectory)
                .setCredentialsProvider(credentialsProvider())
                .call()) {
            // resource auto-close
        } catch (GitAPIException e) {
            throw new GitClientException(
                    "Failed to clone branch '%s' from '%s': %s".formatted(branch, gitUrl, e.getMessage()), e);
        }
    }

    private void cloneWithSystemGit(String branch, File targetDirectory) {
        log.info("Using system git to clone '%s' at branch '%s'.".formatted(gitUrl, branch));
        executeCommand("git", "clone", "--branch", branch, "--single-branch", gitUrl,
                targetDirectory.getAbsolutePath());
    }

    private void executeCommand(String... command) {
        try {
            Process process = new ProcessBuilder(command).start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new GitClientException("Git command failed with exit code: " + exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitClientException("Git command was interrupted", e);
        } catch (IOException e) {
            throw new GitClientException("Git command execution failed: " + e.getMessage(), e);
        }
    }

    private CredentialsProvider credentialsProvider() {
        return new UsernamePasswordCredentialsProvider("no-username-with-token", token);
    }

    private String resolveToken(String envVariableName) {
        if (envVariableName == null) {
            return null;
        }
        log.info("Git token env variable name: " + envVariableName);
        String token = Optional.ofNullable(System.getenv(envVariableName))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .orElse(null);
        log.info("Git token env variable '%s' is %s.".formatted(envVariableName, token != null ? "set" : "not set"));
        return token;
    }
}
