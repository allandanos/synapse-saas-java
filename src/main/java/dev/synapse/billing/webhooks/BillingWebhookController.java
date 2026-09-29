package dev.synapse.billing.webhooks;

import dev.synapse.billing.BillingProviderRegistry;
import dev.synapse.billing.WebhookRequest;
import dev.synapse.core.errors.NotFoundError;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider → us. The RAW body is read exactly once, before anything parses it:
 * the signature covers those bytes, and a re-serialised parse would not match.
 * Hence no {@code @RequestBody} here.
 */
@RestController
@RequestMapping("/v1/billing/webhooks")
public class BillingWebhookController {

    private final BillingWebhookService webhooks;

    public BillingWebhookController(BillingWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping("/{provider}")
    public Map<String, Object> receive(@PathVariable String provider, HttpServletRequest request) throws IOException {
        if (!BillingProviderRegistry.PROVIDER_NAMES.contains(provider)) {
            throw new NotFoundError("Unknown billing provider");
        }
        return webhooks.handle(provider, new WebhookRequest(lowerCaseHeaders(request), request.getInputStream().readAllBytes()));
    }

    /** The providers look their headers up in lower case, as the reference does. */
    private static Map<String, String> lowerCaseHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        }
        return headers;
    }
}
