package ch.admin.bit.jeap.opensearch.client.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchItemClientExceptionTest {

    @Test
    void isARuntimeException() {
        assertThat(RuntimeException.class)
                .isAssignableFrom(SearchItemClientException.class);
    }

    @Test
    void messageConstructor_preservesMessage_andCauseIsNull() {
        SearchItemClientException ex = new SearchItemClientException("boom");

        assertThat(ex.getMessage()).isEqualTo("boom");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void messageAndCauseConstructor_preservesBoth() {
        Throwable cause = new IllegalStateException("inner");
        SearchItemClientException ex = new SearchItemClientException("outer", cause);

        assertThat(ex.getMessage()).isEqualTo("outer");
        assertThat(ex.getCause()).isSameAs(cause);
    }
}
