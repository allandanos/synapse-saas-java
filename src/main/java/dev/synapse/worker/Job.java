package dev.synapse.worker;

/**
 * One scheduled maintenance job. {@link #runOnce()} does a single pass and
 * returns the reference's count for that job — what {@code --jobs-run-once}
 * prints and what the scheduler logs.
 */
public interface Job {

    String name();

    int runOnce();
}
