package com.geek.association.review;

/**
 * The one model-transport dependency of the review pipeline.
 *
 * Keeping it an interface rather than calling {@code AiExplainer} keeps the pipeline testable with a
 * plain lambda, and keeps every HTTP detail (endpoint normalisation, auth header, timeout, key
 * redaction) in exactly one class.
 */
@FunctionalInterface
public interface ModelClient {

    /**
     * Sends one system + user message pair and returns the assistant content.
     *
     * @param maxTokens response cap when the caller knows it; {@code null} leaves the cap to the provider
     */
    String complete(String systemPrompt, String prompt, Integer maxTokens) throws Exception;

    /**
     * The same call, told how much of the section budget is left, so the transport can shorten its
     * own timeout instead of spending a full one on a section that is already out of time.
     *
     * <p>A default method on purpose: the pipeline must stay testable with a plain three-argument
     * lambda, and only the real HTTP transport in {@code AiExplainer} has a timeout to shorten. A
     * test client that ignores the deadline is still a valid client - the pipeline checks the
     * budget itself before every call, so the deadline is honoured even here.
     */
    default String complete(String systemPrompt, String prompt, Integer maxTokens, long timeoutMillis)
            throws Exception {
        return complete(systemPrompt, prompt, maxTokens);
    }
}
