package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch.maintenance;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.ConditionalOnMaintenanceEnabled;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance.JobTargetNotFoundException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobExceptions.JobTargetLayoutNotSupportedException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobExceptions.JobTargetNotWritableException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.ResolvedTarget;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobStorage.JobTargetResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Map;

@Component
@ConditionalOnMaintenanceEnabled
@RequiredArgsConstructor
class OpenSearchJobTargetResolver implements JobTargetResolver {

    private final MaintenanceStoreClient client;

    @Override
    public ResolvedTarget resolveWriteTarget(String writeAlias) {
        MaintenanceStoreClient.Result alias = client.execute("GET", "/_alias/" + writeAlias, Map.of(), null);
        if (alias.status() == 404) {
            throw new JobTargetNotFoundException(writeAlias);
        }
        OpenSearchJobRepository.requireOk(alias, "resolve write alias " + writeAlias);
        // Several indices mean a past rollover: documents written through the alias would duplicate those in older
        // indices, so such layouts are refused even if one of them is the write index
        int indexCount = alias.body().size();
        if (indexCount != 1) {
            throw new JobTargetLayoutNotSupportedException(writeAlias, indexCount);
        }
        var entry = alias.body().properties().iterator().next();
        String index = entry.getKey();
        JsonNode isWriteIndex = entry.getValue().path("aliases").path(writeAlias).path("is_write_index");
        if (isWriteIndex.isBoolean() && !isWriteIndex.booleanValue()) {
            throw new JobTargetNotWritableException(writeAlias, index);
        }
        MaintenanceStoreClient.Result settings = client.execute("GET", "/" + index + "/_settings/index.uuid", Map.of(), null);
        OpenSearchJobRepository.requireOk(settings, "read settings of index " + index);
        JsonNode uuid = settings.body().path(index).path("settings").path("index").path("uuid");
        return new ResolvedTarget(index, uuid.isString() ? uuid.stringValue() : null);
    }
}
