package com.github.serbentd.eve.batch.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.batch.integration.partition.StepExecutionRequest;

/**
 * Jackson Mixin providing JSON creator bindings for Spring Batch's {@link StepExecutionRequest}.
 */
public abstract class StepExecutionRequestMixin {

    @JsonCreator
    public StepExecutionRequestMixin(
            @JsonProperty("stepName") String stepName,
            @JsonProperty("jobExecutionId") Long jobExecutionId,
            @JsonProperty("stepExecutionId") Long stepExecutionId) {
    }
}
