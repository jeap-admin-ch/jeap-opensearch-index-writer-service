package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch.maintenance;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.ConditionalOnMaintenanceEnabled;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobExceptions.JobStorageUnavailableException;
import lombok.RequiredArgsConstructor;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ErrorCause;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.generic.Bodies;
import org.opensearch.client.opensearch.generic.Body;
import org.opensearch.client.opensearch.generic.Request;
import org.opensearch.client.opensearch.generic.Requests;
import org.opensearch.client.opensearch.generic.Response;
import org.opensearch.client.transport.httpclient5.ResponseException;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

// Throttling, server errors and unreachable storage become JobStorageUnavailableException, every other status is left to the caller
@Component
@ConditionalOnMaintenanceEnabled
@RequiredArgsConstructor
class MaintenanceStoreClient {

    record Result(int status, JsonNode body) {
        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private final OpenSearchClient openSearchClient;
    private final JsonMapper jsonMapper;

    Result execute(String method, String endpoint, Map<String, String> query, JsonNode body) {
        Body requestBody = body == null ? null : Bodies.json(jsonMapper.writeValueAsString(body));
        return send(method, endpoint, query, requestBody);
    }

    Result executeNdjson(String endpoint, Map<String, String> query, String ndjson) {
        return send("POST", endpoint, query, Body.from(ndjson.getBytes(StandardCharsets.UTF_8), "application/x-ndjson"));
    }

    private Result send(String method, String endpoint, Map<String, String> query, Body body) {
        Request request = Requests.create(method, endpoint, List.of(), query, body);
        try (Response response = openSearchClient.generic().execute(request)) {
            int status = response.getStatus();
            String responseBody = response.getBody().map(Body::bodyAsString).orElse("");
            if (status == 429 || status >= 500) {
                throw new JobStorageUnavailableException("OpenSearch answered HTTP %d for %s %s: %s".formatted(status, method, endpoint, responseBody), null);
            }
            return new Result(status, parseLeniently(responseBody));
        } catch (JobStorageUnavailableException e) {
            throw e;
        } catch (Exception e) {
            return fromException(method, endpoint, e);
        }
    }

    private Result fromException(String method, String endpoint, Exception e) {
        // The transports throw instead of returning some 4xx responses: ApacheHttpClient5Transport a checked
        // ResponseException, AwsSdk2Transport an unchecked OpenSearchException, possibly wrapped
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ResponseException r) {
                return fromResponseException(method, endpoint, r);
            }
            if (t instanceof OpenSearchException o) {
                return fromOpenSearchException(method, endpoint, o);
            }
        }
        if (isTransportFailure(e)) {
            throw new JobStorageUnavailableException("OpenSearch request %s %s failed: %s".formatted(method, endpoint, e.getMessage()), e);
        }
        // Anything else is a defect, not an outage: retrying cannot help, so it must not be reported as 503
        if (e instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new IllegalStateException("OpenSearch request %s %s failed unexpectedly".formatted(method, endpoint), e);
    }

    // ApacheHttpClient5Transport reports connection problems as IOException, AwsSdk2Transport as SdkClientException
    private static boolean isTransportFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof IOException || t instanceof SdkClientException) {
                return true;
            }
        }
        return false;
    }

    // Proxies in front of the cluster may answer with HTML or plain text; the status must survive an unparseable body
    private JsonNode parseLeniently(String body) {
        if (body.isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            return jsonMapper.readTree(body);
        } catch (JacksonException _) {
            return jsonMapper.getNodeFactory().stringNode(body);
        }
    }

    private Result fromOpenSearchException(String method, String endpoint, OpenSearchException e) {
        int status = e.status();
        requireAvailable(status, method, endpoint, e);
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("status", status);
        ErrorCause error = e.error();
        if (error != null) {
            ObjectNode errorNode = body.putObject("error");
            errorNode.put("type", error.type());
            errorNode.put("reason", error.reason());
        }
        return new Result(status, body);
    }

    // Their body is only available as part of the message
    private Result fromResponseException(String method, String endpoint, ResponseException e) {
        int status = e.status();
        requireAvailable(status, method, endpoint, e);
        String message = e.getMessage() == null ? "" : e.getMessage();
        int start = message.indexOf('{');
        if (start >= 0) {
            try {
                return new Result(status, jsonMapper.readTree(message.substring(start)));
            } catch (RuntimeException _) {
                // body not parseable, the status alone is reported
            }
        }
        return new Result(status, MissingNode.getInstance());
    }

    private static void requireAvailable(int status, String method, String endpoint, Exception cause) {
        if (status == 429 || status >= 500) {
            throw new JobStorageUnavailableException("OpenSearch answered HTTP %d for %s %s".formatted(status, method, endpoint), cause);
        }
    }
}
