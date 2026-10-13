package com.vitallink.readings.summary;

import org.springframework.stereotype.Component;

/**
 * Stands in for a real model (assumption A17), so the service runs without credentials. A real client would call the
 * provider's API over HTTP; everything around it (timeout, call limit, fallback) stays the same.
 */
@Component
class StubLlmClient implements LlmClient {

    @Override
    public String complete(String prompt) {
        return "Stubbed model response: with a real model configured, this would be a short plain-language summary"
                + " of the facts below, written for a care coach.";
    }
}
