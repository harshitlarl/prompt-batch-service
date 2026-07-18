package com.example.promptbatch.resources;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PingResourceTest {

    @Test
    void pingReturnsOkStatus() {
        PingResource resource = new PingResource();

        var response = resource.ping();

        assertThat(response)
                .containsEntry("status", "ok")
                .containsEntry("message", "pong");
    }
}
