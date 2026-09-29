package dev.synapse.billing;

import java.util.Locale;
import java.util.Map;

/**
 * Raw webhook material (reference: {@code billing/protocol.py:WebhookRequest}).
 * {@code body} is the exact bytes the provider signed — never a re-serialised parse.
 * Header names are lower-cased by the controller, as the reference does.
 */
public record WebhookRequest(Map<String, String> headers, byte[] body) {

    public WebhookRequest {
        headers = Map.copyOf(headers);
        body = body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    public String header(String lowerCaseName) {
        return headers.getOrDefault(lowerCaseName.toLowerCase(Locale.ROOT), "");
    }
}
