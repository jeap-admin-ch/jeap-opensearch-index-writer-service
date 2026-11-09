package ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing;

import ch.admin.bit.jeap.opensearch.indextype.IndexType;
import ch.admin.bit.jeap.opensearch.indextype.Origin;
import ch.admin.bit.jeap.opensearch.indextype.SearchItem;
import ch.admin.bit.jeap.opensearch.indextype.SearchItemIndexed;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.indextype.IndexTypeRepository;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.CurrentSearchItemException.Reason;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.deser.DeserializationProblemHandler;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.NullNode;

import org.springframework.cglib.proxy.Enhancer;
import org.springframework.cglib.proxy.MethodInterceptor;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CurrentSearchItemFetcherTest {

    private static final String BASE_URI = "https://provider/api";
    private static final String OAUTH_CLIENT = "maintenance-client";
    private static final String INDEX_TYPE = "DecreeDocument";
    private static final String ORIGIN_ID = "12345";
    private static final Origin ORIGIN = new Origin(ORIGIN_ID, "rev-7", null, null, null, null, null);

    record DecreeDocumentV1(String title) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LenientDocument(String title) {
    }

    static class AnySetterDocument {
        public String title;
        public final Map<String, Object> other = new HashMap<>();

        @JsonAnySetter
        void set(String name, Object value) {
            other.put(name, value);
        }
    }

    record NestingDocument(LenientDocument nested) {
    }

    private final JsonMapper jsonMapper = new JsonMapper();

    @Mock
    private SearchItemProvider searchItemProvider;

    @Mock
    private IndexTypeRepository indexTypeRepository;

    @Mock
    private IndexType<DecreeDocumentV1> decreeDocumentV1;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private CurrentSearchItemFetcher fetcher;

    @BeforeEach
    void setUp() {
        fetcher = new CurrentSearchItemFetcher(searchItemProvider, indexTypeRepository, meterRegistry, jsonMapper);
        // Shared defaults; not every test reaches the index type or the repository
        lenient().when(decreeDocumentV1.originType()).thenReturn(INDEX_TYPE);
        lenient().when(decreeDocumentV1.majorVersion()).thenReturn(1);
        lenient().when(decreeDocumentV1.minorVersion()).thenReturn(3);
        lenient().when(decreeDocumentV1.dataClass()).thenReturn(DecreeDocumentV1.class);
        lenient().when(indexTypeRepository.findByOriginTypeAndMajorVersion(INDEX_TYPE, 1)).thenReturn(Optional.of(decreeDocumentV1));
    }

    private void register(IndexType<?> indexType) {
        when(indexTypeRepository.findByOriginTypeAndMajorVersion(indexType.originType(), indexType.majorVersion()))
                .thenReturn(Optional.of(indexType));
    }

    @Test
    void recordsFetchTimePerIndexType() {
        providerAnswers(new SearchItemResult(1, 3, item(ORIGIN, "{\"title\":\"Decree\"}"), INDEX_TYPE));

        fetcher.fetch(BASE_URI, OAUTH_CLIENT, decreeDocumentV1, ORIGIN_ID);

        assertThat(meterRegistry.get("jeap.opensearch.indexwriter.currentsearchitem.fetch").tag("index_type", INDEX_TYPE).timer().count())
                .isEqualTo(1);
    }

    @Test
    void rejectsUnregisteredIndexTypeWithoutCallingTheProvider() {
        when(indexTypeRepository.findByOriginTypeAndMajorVersion(INDEX_TYPE, 1)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fetcher.fetch(BASE_URI, OAUTH_CLIENT, decreeDocumentV1, ORIGIN_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No index type registered");
        verifyNoInteractions(searchItemProvider);
    }

    public static class PlainIndexType implements IndexType<DecreeDocumentV1> {
        // @formatter:off
        @Override public Class<DecreeDocumentV1> dataClass() { return DecreeDocumentV1.class; }
        @Override public String system() { return "jme"; }
        @Override public String originType() { return INDEX_TYPE; }
        @Override public int majorVersion() { return 1; }
        @Override public int minorVersion() { return 3; }
        @Override public String description() { return "test"; }
        @Override public String documentationUrl() { return null; }
        @Override public List<String> roles() { return List.of(); }
        @Override public Supplier<InputStream> mappingDefinition() { return null; }
        // @formatter:on
    }

    @Test
    void acceptsProxiedInstanceOfTheRegisteredIndexType() {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(PlainIndexType.class);
        enhancer.setCallback((MethodInterceptor) (obj, method, args, proxy) -> proxy.invokeSuper(obj, args));
        PlainIndexType proxied = (PlainIndexType) enhancer.create();
        assertThat(proxied.getClass()).isNotEqualTo(PlainIndexType.class);
        register(new PlainIndexType());
        providerAnswers(new SearchItemResult(1, 3, item(ORIGIN, "{\"title\":\"Decree\"}"), INDEX_TYPE));

        SearchItemIndexed<DecreeDocumentV1> result = fetcher.fetch(BASE_URI, OAUTH_CLIENT, proxied, ORIGIN_ID);

        assertThat(result.data().title()).isEqualTo("Decree");
    }

    @Test
    void rejectsOtherIndexTypeClassWithSameOriginTypeAndVersionsAsTheRegisteredOne() {
        register(new PlainIndexType());
        PlainIndexType other = new PlainIndexType() {
        };

        assertThatThrownBy(() -> fetcher.fetch(BASE_URI, OAUTH_CLIENT, other, ORIGIN_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not the registered index type");
        verifyNoInteractions(searchItemProvider);
    }

    @Test
    void rejectsIndexTypeWithOtherMinorThanTheRegisteredOne() {
        IndexType<?> registered = mock(IndexType.class);
        when(registered.majorVersion()).thenReturn(1);
        when(registered.minorVersion()).thenReturn(4);
        when(indexTypeRepository.findByOriginTypeAndMajorVersion(INDEX_TYPE, 1)).thenReturn(Optional.of(registered));

        assertThatThrownBy(() -> fetcher.fetch(BASE_URI, OAUTH_CLIENT, decreeDocumentV1, ORIGIN_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not the registered index type");
        verifyNoInteractions(searchItemProvider);
    }

    @Test
    void rejectsNullOriginId() {
        assertThatThrownBy(() -> fetcher.fetch(BASE_URI, OAUTH_CLIENT, decreeDocumentV1, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("originId");
        verifyNoInteractions(searchItemProvider);
    }

    @Test
    void requestsFixedMajorAndReturnsTypedItemWithProducedVersions() {
        providerAnswers(new SearchItemResult(1, 3, item(ORIGIN, "{\"title\":\"Decree\"}"), INDEX_TYPE));

        SearchItemIndexed<DecreeDocumentV1> indexed = fetcher.fetch(BASE_URI, OAUTH_CLIENT, decreeDocumentV1, ORIGIN_ID);

        assertThat(indexed.data()).isEqualTo(new DecreeDocumentV1("Decree"));
        assertThat(indexed.origin()).isEqualTo(ORIGIN);
        assertThat(indexed.searchItem().majorVersion()).isEqualTo(1);
        assertThat(indexed.searchItem().minorVersion()).isEqualTo(3);
        verify(searchItemProvider).findCurrentSearchItem(BASE_URI, INDEX_TYPE, ORIGIN_ID, 1, OAUTH_CLIENT);
        verify(searchItemProvider, never()).findSearchItem(any(), any(), any(), any());
    }

    @Test
    void acceptsOlderMinorOfSameMajor() {
        providerAnswers(new SearchItemResult(1, 1, item(ORIGIN, "{\"title\":\"Decree\"}"), INDEX_TYPE));

        assertThat(fetcher.fetch(BASE_URI, OAUTH_CLIENT, decreeDocumentV1, ORIGIN_ID).searchItem().minorVersion()).isEqualTo(1);
    }

    @Test
    void notFound() {
        when(searchItemProvider.findCurrentSearchItem(any(), any(), any(), anyInt(), any())).thenReturn(Optional.empty());

        assertReason(Reason.NOT_FOUND);
    }

    @Test
    void unsupportedMajorIsPropagated() {
        when(searchItemProvider.findCurrentSearchItem(any(), any(), any(), anyInt(), any()))
                .thenThrow(CurrentSearchItemException.unsupportedIndexMajorVersion(INDEX_TYPE, 1, ORIGIN_ID));

        assertReason(Reason.UNSUPPORTED_INDEX_MAJOR_VERSION);
    }

    @Test
    void rejectsOtherMajor() {
        providerAnswers(new SearchItemResult(2, 0, item(ORIGIN, "{\"title\":\"Decree\"}"), INDEX_TYPE));

        assertMismatch("major version 2");
    }

    @Test
    void rejectsMinorUnknownToWriter() {
        providerAnswers(new SearchItemResult(1, 4, item(ORIGIN, "{\"title\":\"Decree\"}"), INDEX_TYPE));

        assertMismatch("minor version 4");
    }

    @Test
    void rejectsOtherIndexType() {
        providerAnswers(new SearchItemResult(1, 3, item(ORIGIN, "{\"title\":\"Decree\"}"), "Decree"));

        assertMismatch("index type 'Decree'");
    }

    @Test
    void rejectsResponseWithoutIndexType_fromProviderIgnoringTheParameter() {
        providerAnswers(new SearchItemResult(1, 3, item(ORIGIN, "{\"title\":\"Decree\"}")));

        assertMismatch("ignores index_major_version");
    }

    @Test
    void rejectsPayloadOfAnotherMajorEvenIfItFitsTheDataClassLeniently() {
        providerAnswers(new SearchItemResult(1, 3, item(ORIGIN, "{\"title\":\"Decree\",\"language\":\"de\"}"), INDEX_TYPE));

        assertMismatch("does not match data class 'DecreeDocumentV1'");
    }

    @Test
    void rejectsUnknownPropertiesEvenIfDataClassIgnoresUnknown() {
        assertRejectsUnknownProperty(LenientDocument.class, "{\"title\":\"Decree\",\"language\":\"de\"}");
    }

    @Test
    void rejectsUnknownPropertiesEvenIfDataClassHasAnySetter() {
        assertRejectsUnknownProperty(AnySetterDocument.class, "{\"title\":\"Decree\",\"language\":\"de\"}");
    }

    @Test
    void rejectsUnknownPropertiesInNestedClassIgnoringUnknown() {
        assertRejectsUnknownProperty(NestingDocument.class, "{\"nested\":{\"title\":\"Decree\",\"language\":\"de\"}}");
    }

    @Test
    void rejectsUnknownPropertiesEvenIfMapperHasProblemHandlerIgnoringThem() {
        JsonMapper lenientMapper = JsonMapper.builder().addHandler(new DeserializationProblemHandler() {
            @Override
            public boolean handleUnknownProperty(DeserializationContext ctxt, JsonParser p, ValueDeserializer<?> deserializer,
                                                 Object beanOrClass, String propertyName) {
                p.skipChildren();
                return true;
            }
        }).build();
        fetcher = new CurrentSearchItemFetcher(searchItemProvider, indexTypeRepository, meterRegistry, lenientMapper);
        providerAnswers(new SearchItemResult(1, 3, item(ORIGIN, "{\"title\":\"Decree\",\"language\":\"de\"}"), INDEX_TYPE));

        assertMismatch("does not match data class 'DecreeDocumentV1'");
    }

    @Test
    @SuppressWarnings("unchecked")
    void lenientPayloadCheckHonoursDataClassHandlingOfUnknownProperties() {
        IndexType<AnySetterDocument> indexType = mock(IndexType.class);
        when(indexType.originType()).thenReturn(INDEX_TYPE);
        when(indexType.majorVersion()).thenReturn(1);
        when(indexType.minorVersion()).thenReturn(3);
        when(indexType.dataClass()).thenReturn(AnySetterDocument.class);
        register(indexType);
        providerAnswers(new SearchItemResult(1, 3, item(ORIGIN, "{\"title\":\"Decree\",\"language\":\"de\"}"), INDEX_TYPE));

        AnySetterDocument data = fetcher.fetch(BASE_URI, OAUTH_CLIENT, indexType, ORIGIN_ID, CurrentSearchItemFetcher.PayloadCheck.LENIENT).data();

        assertThat(data.title).isEqualTo("Decree");
        assertThat(data.other).containsEntry("language", "de");
    }

    @Test
    void lenientPayloadCheckStillRejectsOtherMajor() {
        providerAnswers(new SearchItemResult(2, 0, item(ORIGIN, "{\"title\":\"Decree\"}"), INDEX_TYPE));

        assertThatThrownBy(() -> fetcher.fetch(BASE_URI, OAUTH_CLIENT, decreeDocumentV1, ORIGIN_ID, CurrentSearchItemFetcher.PayloadCheck.LENIENT))
                .isInstanceOf(CurrentSearchItemException.class)
                .hasMessageContaining("major version 2")
                .matches(e -> ((CurrentSearchItemException) e).getReason() == Reason.RESPONSE_MISMATCH);
    }

    @Test
    void rejectsOtherOriginId() {
        Origin other = new Origin("99999", null, null, null, null, null, null);
        providerAnswers(new SearchItemResult(1, 3, item(other, "{\"title\":\"Decree\"}"), INDEX_TYPE));

        assertMismatch("origin id '99999'");
    }

    @Test
    void rejectsMissingData() {
        providerAnswers(new SearchItemResult(1, 3, new SearchItem<>(ORIGIN, NullNode.getInstance()), INDEX_TYPE));

        assertMismatch("no origin or no data");
    }

    @SuppressWarnings("unchecked")
    private <T> void assertRejectsUnknownProperty(Class<T> dataClass, String json) {
        IndexType<T> indexType = mock(IndexType.class);
        when(indexType.originType()).thenReturn(INDEX_TYPE);
        when(indexType.majorVersion()).thenReturn(1);
        when(indexType.minorVersion()).thenReturn(3);
        when(indexType.dataClass()).thenReturn(dataClass);
        register(indexType);
        providerAnswers(new SearchItemResult(1, 3, item(ORIGIN, json), INDEX_TYPE));

        assertThatThrownBy(() -> fetcher.fetch(BASE_URI, OAUTH_CLIENT, indexType, ORIGIN_ID))
                .isInstanceOf(CurrentSearchItemException.class)
                .hasMessageContaining("does not match data class '%s'".formatted(dataClass.getSimpleName()))
                .hasMessageContaining("language");
    }

    private void providerAnswers(SearchItemResult result) {
        when(searchItemProvider.findCurrentSearchItem(BASE_URI, INDEX_TYPE, ORIGIN_ID, 1, OAUTH_CLIENT)).thenReturn(Optional.of(result));
    }

    private SearchItem<JsonNode> item(Origin origin, String json) {
        return new SearchItem<>(origin, jsonMapper.readTree(json));
    }

    private void assertMismatch(String detail) {
        assertThatThrownBy(() -> fetcher.fetch(BASE_URI, OAUTH_CLIENT, decreeDocumentV1, ORIGIN_ID))
                .isInstanceOf(CurrentSearchItemException.class)
                .hasMessageContaining(detail)
                .matches(e -> ((CurrentSearchItemException) e).getReason() == Reason.RESPONSE_MISMATCH)
                .matches(e -> !((CurrentSearchItemException) e).isRetryable());
    }

    private void assertReason(Reason reason) {
        assertThatThrownBy(() -> fetcher.fetch(BASE_URI, OAUTH_CLIENT, decreeDocumentV1, ORIGIN_ID))
                .isInstanceOf(CurrentSearchItemException.class)
                .matches(e -> ((CurrentSearchItemException) e).getReason() == reason)
                .matches(e -> ((CurrentSearchItemException) e).getOriginId().equals(ORIGIN_ID));
    }
}
