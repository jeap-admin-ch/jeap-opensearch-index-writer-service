package ch.admin.bit.jeap.opensearch.registry.verifier;

import lombok.Builder;
import lombok.Value;

import java.io.File;

@Value
@Builder(toBuilder = true)
public class ValidationContext {
    File descriptorDir;
    File oldDescriptorDir;
    String systemName;
    File systemDir;
    String indexTypeName;
    File indexTypeDir;
    File descriptor;
}
