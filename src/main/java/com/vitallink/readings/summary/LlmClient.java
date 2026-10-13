package com.vitallink.readings.summary;

/**
 * A language model that turns a prompt into text. Implementations may be slow, fail, or return nonsense; callers
 * must treat the result as advisory and have a fallback (see {@link SummaryService}).
 */
interface LlmClient {

    String complete(String prompt);
}
