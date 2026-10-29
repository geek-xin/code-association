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
}
