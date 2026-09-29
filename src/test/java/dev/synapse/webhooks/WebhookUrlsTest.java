package dev.synapse.webhooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.synapse.core.errors.ValidationFailedError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Endpoint URLs follow pydantic's {@code HttpUrl}: absolute, http(s), with a host. */
class WebhookUrlsTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "https://hooks.example.com/abc",
        "http://localhost:9000/hook",
        "https://example.com",
        "HTTPS://Example.com/Path?q=1"
    })
    void acceptsAbsoluteHttpUrls(String url) {
        assertThat(WebhookUrls.requireHttpUrl(url)).isEqualTo(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not a url",
        "/relative/path",
        "ftp://example.com/x",
        "https://",
        "example.com/hook",
        ""
    })
    void rejectsEverythingElse(String url) {
        assertThatThrownBy(() -> WebhookUrls.requireHttpUrl(url)).isInstanceOf(ValidationFailedError.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> WebhookUrls.requireHttpUrl(null)).isInstanceOf(ValidationFailedError.class);
    }
}
