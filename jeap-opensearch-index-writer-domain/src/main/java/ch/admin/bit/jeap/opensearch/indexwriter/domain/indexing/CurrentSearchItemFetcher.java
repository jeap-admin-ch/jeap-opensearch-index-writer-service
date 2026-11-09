package ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing;

import ch.admin.bit.jeap.opensearch.indextype.IndexType;
import ch.admin.bit.jeap.opensearch.indextype.SearchItem;
import ch.admin.bit.jeap.opensearch.indextype.SearchItemIndexed;
import ch.admin.bit.jeap.opensearch.indextype.SearchItemMetadata;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.indextype.IndexTypeRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.AnnotationIntrospector;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.MapperConfig;
import tools.jackson.databind.introspect.Annotated;
import tools.jackson.databind.introspect.AnnotationIntrospectorPair;
import tools.jackson.databind.introspect.NopAnnotationIntrospector;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

@Service
@Slf4j
public class CurrentSearchItemFetcher {

    public enum PayloadCheck {
        STRICT,
        LENIENT
    }

    private static final String FETCH_TIME_METRICS_NAME = "jeap.opensearch.indexwriter.currentsearchitem.fetch";

    private final SearchItemProvider searchItemProvider;
    private final IndexTypeRepository indexTypeRepository;
    private final MeterRegistry meterRegistry;
    private final JsonMapper jsonMapper;
    private final JsonMapper strictJsonMapper;

    public CurrentSearchItemFetcher(SearchItemProvider searchItemProvider, IndexTypeRepository indexTypeRepository,
                                    MeterRegistry meterRegistry, JsonMapper jsonMapper) {
        this.searchItemProvider = searchItemProvider;
        this.indexTypeRepository = indexTypeRepository;
        this.meterRegistry = meterRegistry;
        this.jsonMapper = jsonMapper;
        this.strictJsonMapper = jsonMapper.rebuild()
                .annotationIntrospector(new StrictAnnotationIntrospector(jsonMapper.deserializationConfig().getAnnotationIntrospector()))
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .clearProblemHandlers()
                .build();
    }

    public <T> SearchItemIndexed<T> fetch(String baseUri, String oauthClientId, IndexType<T> indexType, String originId) {
        return fetch(baseUri, oauthClientId, indexType, originId, PayloadCheck.STRICT);
    }

    public <T> SearchItemIndexed<T> fetch(String baseUri, String oauthClientId, IndexType<T> indexType, String originId,
                                          PayloadCheck payloadCheck) {
        Objects.requireNonNull(indexType, "indexType");
        Objects.requireNonNull(originId, "originId");
        Objects.requireNonNull(payloadCheck, "payloadCheck");
        requireRegistered(indexType);
        String originType = indexType.originType();
        int major = indexType.majorVersion();

        SearchItemResult result = timed(originType,
                () -> searchItemProvider.findCurrentSearchItem(baseUri, originType, originId, major, oauthClientId))
                .orElseThrow(() -> CurrentSearchItemException.notFound(originType, major, originId));

        verifyMatches(indexType, originId, result);
        T data = toTypedData(indexType, originId, result.searchItem().data(), payloadCheck);

        SearchItemMetadata metadata = new SearchItemMetadata(Instant.now(), result.indexMajorVersion(), result.indexMinorVersion());
        return new SearchItem<>(result.searchItem().origin(), data).withMetadata(metadata);
    }

    // Only registered index types have their template, physical index and write alias set up at startup
    private void requireRegistered(IndexType<?> indexType) {
        IndexType<?> registered = indexTypeRepository.findByOriginTypeAndMajorVersion(indexType.originType(), indexType.majorVersion())
                .orElseThrow(() -> new IllegalArgumentException("No index type registered for originType='%s' majorVersion=%d"
                        .formatted(indexType.originType(), indexType.majorVersion())));
        if (registered == indexType) {
            return;
        }
        // Callers pass their own instance (e.g. new MyIndexType()), so compare classes rather than identity
        // getUserClass also tolerates a caller-side Spring proxy of the class
        Class<?> registeredClass = ClassUtils.getUserClass(registered);
        Class<?> indexTypeClass = ClassUtils.getUserClass(indexType);
        if (registeredClass != indexTypeClass || registered.minorVersion() != indexType.minorVersion()) {
            throw new IllegalArgumentException("Index type %s v%d.%d is not the registered index type %s v%d.%d".formatted(
                    indexTypeClass.getName(), indexType.majorVersion(), indexType.minorVersion(),
                    registeredClass.getName(), registered.majorVersion(), registered.minorVersion()));
        }
    }

    private <R> R timed(String originType, Supplier<R> action) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return action.get();
        } finally {
            sample.stop(Timer.builder(FETCH_TIME_METRICS_NAME)
                    .tag("index_type", originType)
                    .register(meterRegistry));
        }
    }

    private void verifyMatches(IndexType<?> indexType, String originId, SearchItemResult result) {
        if (result.indexType() == null) {
            throw mismatch(indexType, originId, "the provider did not report the index type; it probably ignores index_major_version");
        }
        if (!indexType.originType().equals(result.indexType())) {
            throw mismatch(indexType, originId, "the provider returned index type '%s'".formatted(result.indexType()));
        }
        if (result.indexMajorVersion() != indexType.majorVersion()) {
            throw mismatch(indexType, originId, "the provider returned major version %d".formatted(result.indexMajorVersion()));
        }
        if (result.indexMinorVersion() < 0 || result.indexMinorVersion() > indexType.minorVersion()) {
            throw mismatch(indexType, originId, "the provider returned minor version %d, this writer knows minor versions up to %d"
                    .formatted(result.indexMinorVersion(), indexType.minorVersion()));
        }
        SearchItem<JsonNode> searchItem = result.searchItem();
        if (searchItem == null || searchItem.origin() == null || searchItem.data() == null || searchItem.data().isNull()) {
            throw mismatch(indexType, originId, "the response has no origin or no data");
        }
        if (!originId.equals(searchItem.origin().id())) {
            throw mismatch(indexType, originId, "the provider returned origin id '%s'".formatted(searchItem.origin().id()));
        }
    }

    // STRICT rejects unknown properties so that a payload of another major cannot be silently squeezed into the
    // requested data class, which may also be shared across minors
    private <T> T toTypedData(IndexType<T> indexType, String originId, JsonNode data, PayloadCheck payloadCheck) {
        JsonMapper mapper = payloadCheck == PayloadCheck.LENIENT ? jsonMapper : strictJsonMapper;
        try {
            return mapper.readerFor(indexType.dataClass()).readValue(data);
        } catch (JacksonException | IllegalArgumentException e) {
            throw CurrentSearchItemException.mismatch(indexType.originType(), indexType.majorVersion(), originId,
                    "the payload does not match data class '%s': %s".formatted(indexType.dataClass().getSimpleName(), e.getMessage()), e);
        }
    }

    private static CurrentSearchItemException mismatch(IndexType<?> indexType, String originId, String detail) {
        return CurrentSearchItemException.mismatch(indexType.originType(), indexType.majorVersion(), originId, detail);
    }

    // Keeps the configured annotation handling but disables @JsonIgnoreProperties(ignoreUnknown = true) and
    // @JsonAnySetter, which would otherwise override FAIL_ON_UNKNOWN_PROPERTIES
    private static final class StrictAnnotationIntrospector extends AnnotationIntrospectorPair {

        private StrictAnnotationIntrospector(AnnotationIntrospector delegate) {
            super(delegate, NopAnnotationIntrospector.instance);
        }

        @Override
        public JsonIgnoreProperties.Value findPropertyIgnoralByName(MapperConfig<?> config, Annotated a) {
            JsonIgnoreProperties.Value ignorals = super.findPropertyIgnoralByName(config, a);
            return ignorals == null ? null : ignorals.withoutIgnoreUnknown();
        }

        @Override
        public Boolean hasAnySetter(MapperConfig<?> config, Annotated a) {
            return Boolean.FALSE;
        }
    }
}
