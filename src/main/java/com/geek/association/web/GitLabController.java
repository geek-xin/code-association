package com.geek.association.web;

import com.geek.association.config.GitLabSettings;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The GitLab personal access token, edited in the settings page.
 *
 * <p>A controller of its own rather than another pair of methods on {@link ApiController}: the
 * token is the only state here, the feature is self-contained, and keeping it out of the analysis
 * controller means the constructor that several tests build directly does not grow for it.
 *
 * <p>Validation failures are {@link IllegalArgumentException}s, which the existing advice turns
 * into a 400 with the Chinese sentence the form shows in its toast.
 */
@RestController
@RequestMapping("/api")
public class GitLabController {

    private final GitLabSettings settings;

    public GitLabController(GitLabSettings settings) {
        this.settings = settings;
    }

    /** The settings card reads this: whether a token is stored, its mask, and where it lives. */
    @GetMapping("/gitlab")
    public Map<String, Object> gitlab() {
        return settings.view();
    }

    /**
     * Applies and persists the token without a restart.
     *
     * A PUT because it replaces the credential as a whole. The body may be absent: that is the
     * form saying "nothing changed", and it must not erase a stored token.
     */
    @PutMapping("/gitlab")
    public Map<String, Object> save(@RequestBody(required = false) Map<String, Object> body) {
        return settings.update(body);
    }
}
