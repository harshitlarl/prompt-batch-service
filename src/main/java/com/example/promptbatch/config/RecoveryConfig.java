package com.example.promptbatch.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/**
 * Knobs for {@code StaleTaskRecoveryService}: the background sweep that continuously looks for
 * prompts left unfinished by a worker/container that crashed or hung mid-task, independent of
 * any instance restarting. Only takes effect with the {@code postgres} store, since it's the
 * only repository that durably tracks per-prompt attempts across instances.
 */
@Getter
@Setter
public class RecoveryConfig {

    /** Turns the background sweep on/off. Startup recovery (one-shot) is unaffected. */
    @JsonProperty
    private boolean enabled = true;

    /** How often the sweep runs. */
    @Min(1)
    @JsonProperty
    private long intervalSeconds = 30;

    /**
     * How long a prompt can go without a result (and without being re-attempted) before it's
     * considered stale - i.e. long enough that whichever worker last claimed it has plausibly
     * crashed, been killed, or hung, rather than just being slow.
     */
    @Min(1)
    @JsonProperty
    private long staleAfterSeconds = 120;

    /**
     * Once a prompt has been attempted this many times with no successful result, the sweep
     * stops retrying it and instead records a terminal failure, so a permanently-broken prompt
     * (e.g. one that always throws) can't be retried forever and keep its batch stuck.
     */
    @Min(1)
    @JsonProperty
    private int maxAttempts = 5;
}
