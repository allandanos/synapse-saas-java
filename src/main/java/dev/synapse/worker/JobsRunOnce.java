package dev.synapse.worker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * {@code --jobs-run-once [--all | <name>…]}: await each named job exactly once,
 * print {@code name: count} per line and exit — what a scheduler-triggered
 * Cloud Run job executes instead of the always-on loop (reference:
 * {@code synapse-cli jobs run-once}).
 *
 * <p>Exit 2 when no job is selected or a name is unknown, 1 when a job failed,
 * 0 otherwise. {@code Application} turns the web server and the scheduler off
 * for this mode, so the process does nothing but the jobs.
 */
@Component
@Order(10)
public class JobsRunOnce implements ApplicationRunner {

    public static final String COMMAND_OPTION = "jobs-run-once";
    public static final String ALL_OPTION = "all";

    private final JobRegistry jobs;
    private final ConfigurableApplicationContext context;

    public JobsRunOnce(JobRegistry jobs, ConfigurableApplicationContext context) {
        this.jobs = jobs;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(COMMAND_OPTION)) {
            return;
        }
        List<String> selected = args.containsOption(ALL_OPTION) ? jobs.names() : names(args);
        if (selected.isEmpty()) {
            System.err.println("Name at least one job or pass --all. Known: " + String.join(", ", jobs.names()));
            exit(2);
            return;
        }
        List<String> unknown = selected.stream().filter(name -> !jobs.has(name)).toList();
        if (!unknown.isEmpty()) {
            System.err.println("Unknown job(s): " + String.join(", ", unknown) + ". Known: " + String.join(", ", jobs.names()));
            exit(2);
            return;
        }
        Map<String, String> results = new LinkedHashMap<>();
        boolean failed = false;
        for (String name : selected) {
            try {
                int count = jobs.run(name);
                // -1 means another worker held the lock; report it rather than lying about a count
                results.put(name, count < 0 ? "skipped (locked)" : String.valueOf(count));
            } catch (RuntimeException e) { // report, keep going: one job must not hide the others
                results.put(name, "error: " + e.getMessage());
                failed = true;
            }
        }
        results.forEach((name, outcome) -> System.out.println(name + ": " + outcome));
        exit(failed ? 1 : 0);
    }

    /** Positional names, plus `--<job>` spellings so both CLI styles work. */
    private List<String> names(ApplicationArguments args) {
        List<String> selected = new ArrayList<>(args.getNonOptionArgs());
        args.getOptionNames().stream().filter(jobs::has).forEach(selected::add);
        return selected;
    }

    private void exit(int code) {
        System.exit(SpringApplication.exit(context, () -> code));
    }
}
