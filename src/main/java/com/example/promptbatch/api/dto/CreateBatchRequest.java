package com.example.promptbatch.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateBatchRequest {

    @NotEmpty
    @JsonProperty
    private List<String> prompts;
}
