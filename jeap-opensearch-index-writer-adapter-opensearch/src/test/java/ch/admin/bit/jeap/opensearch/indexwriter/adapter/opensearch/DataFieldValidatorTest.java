package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import ch.admin.bit.jeap.opensearch.indextype.Origin;
import ch.admin.bit.jeap.opensearch.indextype.SearchItem;
import ch.admin.bit.jeap.opensearch.indextype.SearchItemIndexed;
import ch.admin.bit.jeap.opensearch.indextype.SearchItemMetadata;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DataFieldValidatorTest {

    private static final String INDEX_WRITE_ALIAS = "orders_v1_write";
    private static final int MAJOR_VERSION = 1;
    private static final int MINOR_VERSION = 3;

    private DataFieldValidator validator;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        validator = new DataFieldValidator(objectMapper);

        Logger logger = (Logger) LoggerFactory.getLogger(DataFieldValidator.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @Test
    void validateDataFields_logsWarning_whenDataContainsUndeclaredFields() {
        validator.cacheMapping(INDEX_WRITE_ALIAS, mappingsWithDataProperties("order_id", "status"));

        validator.validateDataFields(INDEX_WRITE_ALIAS, "doc-123",
                buildSearchItem(Map.of("order_id", "123", "undeclared_field", "value")));

        assertThat(logs.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage())
                            .contains("undeclared_field")
                            .contains(INDEX_WRITE_ALIAS);
                });
    }

    @Test
    void validateDataFields_noWarning_whenDataMatchesDeclaredFields() {
        validator.cacheMapping(INDEX_WRITE_ALIAS, mappingsWithDataProperties("order_id", "status"));

        validator.validateDataFields(INDEX_WRITE_ALIAS, "doc-123",
                buildSearchItem(Map.of("order_id", "123", "status", "active")));

        assertThat(logs.list).noneMatch(e -> e.getLevel() == Level.WARN);
    }

    @Test
    void validateDataFields_noWarning_whenNoCacheEntry() {
        validator.validateDataFields(INDEX_WRITE_ALIAS, "doc-123",
                buildSearchItem(Map.of("some_field", "value")));

        assertThat(logs.list).noneMatch(e -> e.getLevel() == Level.WARN);
    }

    @Test
    void validateDataFields_noWarning_whenDataIsNull() {
        validator.cacheMapping(INDEX_WRITE_ALIAS, mappingsWithDataProperties("order_id"));

        validator.validateDataFields(INDEX_WRITE_ALIAS, "doc-123", buildSearchItem(null));

        assertThat(logs.list).noneMatch(e -> e.getLevel() == Level.WARN);
    }

    @Test
    void validateDataFields_noWarning_whenDataIsNotAnObject() {
        validator.cacheMapping(INDEX_WRITE_ALIAS, mappingsWithDataProperties("order_id"));

        validator.validateDataFields(INDEX_WRITE_ALIAS, "doc-123", buildSearchItem("plain-string-data"));

        assertThat(logs.list).noneMatch(e -> e.getLevel() == Level.WARN);
    }

    private static JsonObject mappingsWithDataProperties(String... fieldNames) {
        var dataPropsBuilder = Json.createObjectBuilder();
        for (String field : fieldNames) {
            dataPropsBuilder.add(field, Json.createObjectBuilder().add("type", "keyword"));
        }
        return Json.createObjectBuilder()
                .add("dynamic", false)
                .add("properties", Json.createObjectBuilder()
                        .add("data", Json.createObjectBuilder()
                                .add("type", "object")
                                .add("properties", dataPropsBuilder)))
                .build();
    }

    private static <T> SearchItemIndexed<T> buildSearchItem(T data) {
        Origin origin = new Origin("id-1", "1", null, null, Instant.now(), Instant.now(), null);
        SearchItem<T> base = new SearchItem<>(origin, data);
        SearchItemMetadata meta = SearchItemMetadata.initial(MAJOR_VERSION, MINOR_VERSION);
        return SearchItemIndexed.of(base, meta);
    }
}
