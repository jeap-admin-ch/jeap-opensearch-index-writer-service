package ch.admin.bit.jeap.opensearch.registry.verifier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

class FileNotChangedValidatorTest {

    // ── noSystemDeleted ──────────────────────────────────────────────────────

    @Test
    void noSystemDeleted_passes_whenOldAndNewMatch(@TempDir File oldRoot, @TempDir File newRoot) throws IOException {
        mkdirs(oldRoot, "jme");
        mkdirs(newRoot, "jme");
        ValidationResult result = FileNotChangedValidator.noSystemDeleted(ctx(oldRoot, newRoot));
        assertThat(result.isValid()).isTrue();
    }

    @Test
    void noSystemDeleted_fails_whenSystemRemoved(@TempDir File oldRoot, @TempDir File newRoot) throws IOException {
        mkdirs(oldRoot, "jme");
        mkdirs(oldRoot, "cms");
        mkdirs(newRoot, "jme");
        // cms is in old but not in new
        ValidationResult result = FileNotChangedValidator.noSystemDeleted(ctx(oldRoot, newRoot));
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("cms") && e.contains("deleted"));
    }

    @Test
    void noSystemDeleted_passes_whenNewSystemAdded(@TempDir File oldRoot, @TempDir File newRoot) throws IOException {
        mkdirs(oldRoot, "jme");
        mkdirs(newRoot, "jme");
        mkdirs(newRoot, "cms"); // new system — allowed
        ValidationResult result = FileNotChangedValidator.noSystemDeleted(ctx(oldRoot, newRoot));
        assertThat(result.isValid()).isTrue();
    }

    @Test
    void noSystemDeleted_passes_whenOldRootIsEmpty(@TempDir File oldRoot, @TempDir File newRoot) throws IOException {
        mkdirs(newRoot, "jme");
        ValidationResult result = FileNotChangedValidator.noSystemDeleted(ctx(oldRoot, newRoot));
        assertThat(result.isValid()).isTrue();
    }

    // ── noIndexTypeDeleted ───────────────────────────────────────────────────

    @Test
    void noIndexTypeDeleted_passes_whenOldSystemDoesNotExist(@TempDir File root) throws IOException {
        // New system — no old state to compare
        mkdirs(root, "jme", "jmefoo");
        ValidationContext ctx = ctxWithSystem(root, root, "newSystem");
        assertThat(FileNotChangedValidator.noIndexTypeDeleted(ctx).isValid()).isTrue();
    }

    @Test
    void noIndexTypeDeleted_fails_whenIndexTypeRemoved(@TempDir File oldRoot, @TempDir File newRoot)
            throws IOException {
        mkdirs(oldRoot, "jme", "jmefoo");
        mkdirs(oldRoot, "jme", "jmebar");
        mkdirs(newRoot, "jme", "jmefoo"); // jmebar removed
        ValidationContext ctx = ctxWithSystem(oldRoot, newRoot, "jme");
        ValidationResult result = FileNotChangedValidator.noIndexTypeDeleted(ctx);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("jmebar") && e.contains("deleted"));
    }

    @Test
    void noIndexTypeDeleted_passes_whenIndexTypeAdded(@TempDir File oldRoot, @TempDir File newRoot)
            throws IOException {
        mkdirs(oldRoot, "jme", "jmefoo");
        mkdirs(newRoot, "jme", "jmefoo");
        mkdirs(newRoot, "jme", "jmenew"); // new — allowed
        ValidationContext ctx = ctxWithSystem(oldRoot, newRoot, "jme");
        assertThat(FileNotChangedValidator.noIndexTypeDeleted(ctx).isValid()).isTrue();
    }

    // ── noExistingMappingsChanged ────────────────────────────────────────────

    @Test
    void noExistingMappingsChanged_passes_whenFilesIdentical(@TempDir File oldRoot, @TempDir File newRoot)
            throws IOException {
        String content = "{\"mappings\":{}}";
        File oldDir = mkdirs(oldRoot, "jme", "jmefoo");
        File newDir = mkdirs(newRoot, "jme", "jmefoo");
        write(oldDir, "Foo_mapping_v1_0.json", content);
        write(newDir, "Foo_mapping_v1_0.json", content);
        ValidationContext ctx = ctxWithIndexType(oldRoot, newRoot, "jme", "Foo");
        assertThat(FileNotChangedValidator.noExistingMappingsChanged(ctx).isValid()).isTrue();
    }

    @Test
    void noExistingMappingsChanged_fails_whenFileContentChanges(@TempDir File oldRoot, @TempDir File newRoot)
            throws IOException {
        // dir name must equal typeName.toLowerCase() so the validator resolves the path correctly
        File oldDir = mkdirs(oldRoot, "jme", "foo");
        File newDir = mkdirs(newRoot, "jme", "foo");
        write(oldDir, "Foo_mapping_v1_0.json", "{\"original\":true}");
        write(newDir, "Foo_mapping_v1_0.json", "{\"changed\":true}");
        ValidationContext ctx = ctxWithIndexType(oldRoot, newRoot, "jme", "Foo");
        ValidationResult result = FileNotChangedValidator.noExistingMappingsChanged(ctx);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("changed") && e.contains("not allowed"));
    }

    @Test
    void noExistingMappingsChanged_fails_whenFileDeleted(@TempDir File oldRoot, @TempDir File newRoot)
            throws IOException {
        File oldDir = mkdirs(oldRoot, "jme", "foo");
        mkdirs(newRoot, "jme", "foo"); // dir exists, but file is missing
        write(oldDir, "Foo_mapping_v1_0.json", "{}");
        ValidationContext ctx = ctxWithIndexType(oldRoot, newRoot, "jme", "Foo");
        ValidationResult result = FileNotChangedValidator.noExistingMappingsChanged(ctx);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("deleted"));
    }

    @Test
    void noExistingMappingsChanged_passes_whenNewIndexTypeHasNoOldState(@TempDir File root) throws IOException {
        mkdirs(root, "jme", "jmefoo");
        ValidationContext ctx = ctxWithIndexType(root, root, "jme", "BrandNew");
        assertThat(FileNotChangedValidator.noExistingMappingsChanged(ctx).isValid()).isTrue();
    }

    @Test
    void noExistingMappingsChanged_passes_whenNewFileAdded(@TempDir File oldRoot, @TempDir File newRoot)
            throws IOException {
        File oldDir = mkdirs(oldRoot, "jme", "jmefoo");
        File newDir = mkdirs(newRoot, "jme", "jmefoo");
        String content = "{\"v\":1}";
        write(oldDir, "Foo_mapping_v1_0.json", content);
        write(newDir, "Foo_mapping_v1_0.json", content);
        write(newDir, "Foo_mapping_v1_1.json", "{\"v\":2}"); // new file — allowed
        ValidationContext ctx = ctxWithIndexType(oldRoot, newRoot, "jme", "Foo");
        assertThat(FileNotChangedValidator.noExistingMappingsChanged(ctx).isValid()).isTrue();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ValidationContext ctx(File oldRoot, File newRoot) {
        return ValidationContext.builder()
                .descriptorDir(newRoot)
                .oldDescriptorDir(oldRoot)
                .build();
    }

    private ValidationContext ctxWithSystem(File oldRoot, File newRoot, String system) {
        return ValidationContext.builder()
                .descriptorDir(newRoot)
                .oldDescriptorDir(oldRoot)
                .systemName(system)
                .systemDir(new File(newRoot, system))
                .build();
    }

    private ValidationContext ctxWithIndexType(File oldRoot, File newRoot, String system, String typeName) {
        return ValidationContext.builder()
                .descriptorDir(newRoot)
                .oldDescriptorDir(oldRoot)
                .systemName(system)
                .systemDir(new File(newRoot, system))
                .indexTypeName(typeName)
                .indexTypeDir(new File(new File(newRoot, system), typeName.toLowerCase()))
                .build();
    }

    private static File mkdirs(File base, String... parts) throws IOException {
        File dir = base;
        for (String part : parts) {
            dir = new File(dir, part);
        }
        Files.createDirectories(dir.toPath());
        return dir;
    }

    private static void write(File dir, String name, String content) throws IOException {
        Files.writeString(new File(dir, name).toPath(), content);
    }
}
