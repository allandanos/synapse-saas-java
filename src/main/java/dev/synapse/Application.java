package dev.synapse;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Three entry points, one jar:
 * <ul>
 *   <li>default — the API, with the worker's cadences in-process
 *       ({@code SYNAPSE_WORKER_ENABLED});</li>
 *   <li>{@code --worker} — the standalone worker: no web server, jobs only;</li>
 *   <li>{@code --jobs-run-once [--all|names…]}, {@code --plans-sync},
 *       {@code --seed-dev} — one-shot commands that print their result and exit.</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class Application {

    public static final String WORKER_OPTION = "--worker";
    public static final String JOBS_RUN_ONCE_OPTION = "--jobs-run-once";
    public static final String PLANS_SYNC_OPTION = "--plans-sync";
    public static final String SEED_DEV_OPTION = "--seed-dev";

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(Application.class);
        application.setDefaultProperties(modeProperties(args));
        application.run(args);
    }

    /** A one-shot command needs no web server; only {@code --worker} keeps the scheduler. */
    static Map<String, Object> modeProperties(String[] args) {
        Map<String, Object> properties = new LinkedHashMap<>();
        boolean oneShot = has(args, JOBS_RUN_ONCE_OPTION) || has(args, PLANS_SYNC_OPTION) || has(args, SEED_DEV_OPTION);
        if (oneShot || has(args, WORKER_OPTION)) {
            properties.put("spring.main.web-application-type", "none");
        }
        if (oneShot) {
            properties.put("synapse.worker-enabled", "false");
        }
        return properties;
    }

    private static boolean has(String[] args, String option) {
        return Arrays.stream(args).anyMatch(arg -> arg.equals(option) || arg.startsWith(option + "="));
    }
}
