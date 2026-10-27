package com.geek.association.transfer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Accepts the tag field in either shape a client might send.
 *
 * <p>A form or a hand-written curl sends <code>"tags": "a, b"</code>, while the web UI and the
 * export file send an array. Binding the model field to {@code List<String>} alone would reject
 * the first shape with a 400 before any normalisation runs, so the field is bound through this
 * deserializer instead and both shapes converge on the same list.
 */
public class SourceTagsDeserializer extends JsonDeserializer<List<String>> {

    @Override
    public List<String> deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.VALUE_NULL) {
            return new ArrayList<>();
        }
        if (token == JsonToken.VALUE_STRING) {
            return SourceTags.normalize(parser.getText());
        }
        if (token == JsonToken.START_ARRAY) {
            List<String> raw = new ArrayList<>();
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                if (parser.currentToken() == JsonToken.VALUE_NULL) {
                    continue;
                }
                raw.add(parser.getText());
            }
            return SourceTags.normalize(raw);
        }
        // Anything else (a number, an object) is not a tag list; refusing the whole request over
        // it would be worse than filing the source with no tags.
        parser.skipChildren();
        return new ArrayList<>();
    }
}
