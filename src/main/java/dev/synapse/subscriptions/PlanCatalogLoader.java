package dev.synapse.subscriptions;

import dev.synapse.core.config.SynapseProperties;
import dev.synapse.core.errors.CatalogInvalidError;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * {@code plans.yaml} → {@link PlanCatalog} (reference: {@code catalog.load_catalog}).
 * The default is the packaged copy of the reference's catalog
 * ({@code classpath:config/plans.yaml}); {@code SYNAPSE_PLANS_FILE} points a
 * product at its own file. Every failure is a {@code plan_catalog_invalid}.
 */
@Component
public class PlanCatalogLoader {

    public static final String CLASSPATH_PREFIX = "classpath:";

    private final SynapseProperties props;

    public PlanCatalogLoader(SynapseProperties props) {
        this.props = props;
    }

    /** The configured catalog ({@code synapse.plans-file}). */
    public PlanCatalog load() {
        return load(props.plansFile());
    }

    public PlanCatalog load(Path path) {
        if (!Files.exists(path)) {
            throw new CatalogInvalidError("Plans file not found: " + path, Map.of("path", path.toString()));
        }
        try {
            return parse(Files.readString(path, StandardCharsets.UTF_8), path.toString());
        } catch (IOException e) {
            throw new CatalogInvalidError("Plans file could not be read: " + e.getMessage(), Map.of("path", path.toString()));
        }
    }

    /** {@code classpath:…} or a filesystem path. */
    public PlanCatalog load(String location) {
        if (location.startsWith(CLASSPATH_PREFIX)) {
            String resource = location.substring(CLASSPATH_PREFIX.length());
            try (InputStream in = PlanCatalogLoader.class.getClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    throw new CatalogInvalidError("Plans file not found: " + location, Map.of("path", location));
                }
                return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), location);
            } catch (IOException e) {
                throw new CatalogInvalidError("Plans file could not be read: " + e.getMessage(), Map.of("path", location));
            }
        }
        return load(Path.of(location));
    }

    public static PlanCatalog parse(String yaml, String path) {
        Object document;
        try {
            document = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        } catch (YAMLException e) {
            throw new CatalogInvalidError("Plans file is not valid YAML: " + e.getMessage(), Map.of("path", path));
        }
        return PlanCatalog.fromRaw(document, path);
    }
}
