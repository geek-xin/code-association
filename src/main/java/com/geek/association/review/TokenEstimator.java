package com.geek.association.review;

/**
 * A deliberately conservative token estimate, used to bound prompts before they are sent.
 *
 * The project has no tokenizer dependency and adding one would pin the build to one provider's
 * vocabulary. A character-class estimate is enough for the job it does: deciding whether a diff
 * chunk fits the budget, and reporting the estimated cost in the stats. Being a little high is the
 * safe direction - it makes the pipeline drop a file slightly early rather than send a prompt the
 * provider truncates.
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double tokens = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isCjk(cp)) {
                tokens += 1.0;
            } else if (cp == ' ' || cp == '\n' || cp == '\r' || cp == '\t') {
                tokens += 0.25;
            } else if (cp < 128) {
                tokens += 0.3;
            } else {
                tokens += 0.6;
            }
            i += Character.charCount(cp);
        }
        return (int) Math.ceil(tokens);
    }

    public static boolean isCjk(int cp) {
        return (cp >= 0x2E80 && cp <= 0x9FFF)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFF00 && cp <= 0xFFEF)
                || (cp >= 0x3000 && cp <= 0x303F)
                || (cp >= 0x20000 && cp <= 0x2FA1F);
    }
}
