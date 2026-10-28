package com.geek.association.web;

import com.geek.association.util.LogEvents;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Turns validation failures into a 400 with a readable message.
 *
 * Without this a rejected source or a missing directory surfaces as a generic 500 and the UI can
 * only say "Internal Server Error". The message is what the user actually needs.
 *
 * Both paths also write one {@code event=api.error} line, because the HTTP status alone never says
 * which request produced it: the path and method do, and they are the first thing needed to tell
 * "the source form was rejected" apart from "the analysis endpoint is down". Request bodies are
 * never logged - they carry locations with tokens and whole run payloads.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e,
                                                          HttpServletRequest request) {
        String message = e.getMessage() == null || e.getMessage().isBlank()
                ? "请求参数不正确" : e.getMessage();
        // DEBUG, not INFO: every rejected form field lands here, and a user fixing a typo should not
        // push the milestones this log exists for off the page. The 400 body already tells the user
        // what went wrong; the log line only makes it greppable after the fact.
        log.debug(LogEvents.of("api.error", "status", 400,
                "method", methodOf(request), "path", pathOf(request), "error", message));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", message));
    }

    /**
     * Anything that is not a validation failure is logged with its stack, then rethrown.
     *
     * The exception is rethrown on purpose: Spring's own error handling then produces exactly the
     * response it produced before this method existed. Returning a body here instead would change
     * what every client sees for an unexpected failure, which is not what "add a log line" is
     * allowed to do.
     *
     * Framework exceptions are skipped. They are rendered by Spring's own resolvers, and most of
     * them are 4xx (a malformed JSON body, a missing parameter, an unknown path) - labelling those
     * as ERROR status=500 would put "the client sent garbage" into the error log, which is exactly
     * the noise that makes an error log useless. Only an exception nobody else handled becomes a
     * 500, and only that one is logged here.
     */
    @ExceptionHandler(Exception.class)
    public void serverError(Exception e, HttpServletRequest request) throws Exception {
        if (isFrameworkException(e)) {
            throw e;
        }
        log.error(LogEvents.of("api.error", "status", 500,
                "method", methodOf(request), "path", pathOf(request),
                "error", e.getClass().getSimpleName()), e);
        throw e;
    }

    /**
     * Whether Spring, not this application, decided the meaning of the failure.
     *
     * The package prefix is checked as well as the {@link ErrorResponse} interface because the two
     * do not cover the same set: {@code HttpMessageNotReadableException} (malformed request body)
     * is a framework 400 but does not implement {@code ErrorResponse}.
     */
    private static boolean isFrameworkException(Throwable e) {
        if (e instanceof ErrorResponse) {
            return true;
        }
        String name = e.getClass().getName();
        return name.startsWith("org.springframework.web.")
                || name.startsWith("org.springframework.http.converter.")
                || name.startsWith("org.springframework.validation.");
    }

    private static String methodOf(HttpServletRequest request) {
        return request == null ? "-" : request.getMethod();
    }

    private static String pathOf(HttpServletRequest request) {
        return request == null ? "-" : request.getRequestURI();
    }
}
