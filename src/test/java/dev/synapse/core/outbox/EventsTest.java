package dev.synapse.core.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class EventsTest {

    @Test
    void internalEventsNeverFanOut() {
        assertThat(Events.audienceFor(Events.MEMBER_INVITE_EMAIL)).isEqualTo("internal");
        assertThat(Events.audienceFor(Events.USER_PASSWORD_RESET_LINK)).isEqualTo("internal");
        assertThat(Events.audienceFor(Events.MEMBER_INVITED)).isEqualTo("public");
        assertThat(Events.audienceFor(Events.ORG_CREATED)).isEqualTo("public");
        assertThat(Events.INTERNAL_EVENTS).containsExactlyInAnyOrder(
            "member.invite_email", "user.password_reset_link", "invoice.email", "authz.tuples_changed");
    }

    /** The vocabulary is the contract's: a constant with no catalogue entry (or the reverse) is drift. */
    @Test
    void theVocabularyMatchesTheContract() throws IOException, ReflectiveOperationException {
        JsonNode catalog = new ObjectMapper().readTree(Files.readString(Path.of("contracts/events.json")));
        Set<String> declaredPublic = new TreeSet<>();
        Set<String> declaredInternal = new TreeSet<>();
        for (Field field : Events.class.getDeclaredFields()) {
            if (field.getType() != String.class || !Modifier.isStatic(field.getModifiers())
                || !Modifier.isPublic(field.getModifiers())) {
                continue;
            }
            String value = (String) field.get(null);
            if (value.startsWith("public") || value.startsWith("internal")) {
                continue; // the audience labels, not event types
            }
            (Events.INTERNAL_EVENTS.contains(value) ? declaredInternal : declaredPublic).add(value);
        }
        assertThat(declaredPublic).containsExactlyElementsOf(names(catalog.get("public")));
        assertThat(declaredInternal).containsExactlyElementsOf(names(catalog.get("internal")));
    }

    private static List<String> names(JsonNode array) {
        Set<String> sorted = new TreeSet<>();
        array.forEach(node -> sorted.add(node.asText()));
        return List.copyOf(sorted);
    }
}
