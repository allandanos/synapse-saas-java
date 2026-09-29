package dev.synapse.webhooks;

import dev.synapse.core.errors.ValidationFailedError;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

/**
 * Endpoint URL validation, matching pydantic's {@code HttpUrl}: an absolute
 * http(s) URL with a host. Anything else is a 422 {@code validation_failed}
 * with the same {@code errors[]} shape the request parser produces.
 */
public final class WebhookUrls {

    private WebhookUrls() {}

    public static String requireHttpUrl(String value) {
        if (value != null && !value.isBlank()) {
            try {
                URI uri = new URI(value);
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
                if (("http".equals(scheme) || "https".equals(scheme)) && uri.getHost() != null && !uri.getHost().isBlank()) {
                    return value;
                }
            } catch (URISyntaxException ignored) {
                // falls through to the validation problem below
            }
        }
        throw new ValidationFailedError("Invalid request: url", Map.of("errors", List.of(
            Map.of("loc", List.of("body", "url"), "msg", "Input should be a valid URL, relative URL without a base",
                "type", "url_parsing"))));
    }
}
