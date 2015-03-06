package com.github.obourgain.elasticsearch.http.response.entity;

import static org.elasticsearch.common.xcontent.XContentParser.Token.END_OBJECT;
import static org.elasticsearch.common.xcontent.XContentParser.Token.FIELD_NAME;
import static org.elasticsearch.common.xcontent.XContentParser.Token.START_ARRAY;
import static org.elasticsearch.common.xcontent.XContentParser.Token.START_OBJECT;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import com.google.common.collect.ImmutableMap;
import lombok.Getter;

@Getter
public class Hit {

    private String index;
    private String type;
    private String id;
    private float score;
    private long version;
    private byte[] source;
    private List<String> sort = Collections.emptyList();
    private Map<String, SearchHitField> fields = ImmutableMap.of();

    public Hit parse(XContentParser parser) throws IOException {
        assert parser.currentToken() == START_OBJECT : "expected a START_OBJECT token but was " + parser.currentToken();

        XContentParser.Token token;
        String currentFieldName = null;
        while ((token = parser.nextToken()) != END_OBJECT) {
            if (token == FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (token.isValue()) {
                if ("_id".equals(currentFieldName)) {
                    id = parser.text();
                } else if ("_index".equals(currentFieldName)) {
                    index = parser.text();
                } else if ("_type".equals(currentFieldName)) {
                    type = parser.text();
                } else if ("_version".equals(currentFieldName)) {
                    version = parser.intValue();
                } else if ("_score".equals(currentFieldName)) {
                    score = parser.floatValue();
                }
            } else if (token == START_OBJECT && "_source".equals(currentFieldName)) {
                try(XContentBuilder docBuilder = XContentFactory.contentBuilder(XContentType.JSON)) {
                    docBuilder.copyCurrentStructure(parser);
                    source = docBuilder.bytes().toBytes();
                }
            } else if (token == START_OBJECT && "fields".equals(currentFieldName)) {
                fields = parseSearchHitFields(parser);
            } else if (token == START_ARRAY && "sort".equals(currentFieldName)) {
                assert parser.currentToken() == START_ARRAY : "expected a START_ARRAY token but was " + parser.currentToken();
                sort = parseSort(parser);
            } else {
                throw new IllegalStateException("unknown field " + currentFieldName);
            }
            // see org.elasticsearch.search.internal.InternalSearchHit
            // TODO fields
            // TODO highlight
            // TODO sort
            // TODO matched_queries
            // TODO _explanation
        }
        return this;
    }

    public static List<Hit> parseHitArray(XContentParser parser) {
        assert parser.currentToken() == START_ARRAY : "expected a START_ARRAY token but was " + parser.currentToken();
        try {
            List<Hit> result = new ArrayList<>();
            while (parser.nextToken() != XContentParser.Token.END_ARRAY) {
                assert parser.currentToken() == START_OBJECT : "expected a START_OBJECT token but was " + parser.currentToken();
                result.add(new Hit().parse(parser));
            }
            return result;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static List<String> parseSort(XContentParser parser) {
        assert parser.currentToken() == START_ARRAY : "expected a START_ARRAY token but was " + parser.currentToken();
        try {
            List<String> result = new ArrayList<>();
            while (parser.nextToken() != XContentParser.Token.END_ARRAY) {
                result.add(parser.text());
            }
            return result;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Map<String, SearchHitField> parseSearchHitFields(XContentParser parser) {
        assert parser.currentToken() == START_OBJECT : "expected a START_OBJECT token but was " + parser.currentToken();
        try {
            Map<String, SearchHitField> result = new HashMap<>();
            while (parser.nextToken() != XContentParser.Token.END_OBJECT) {
                SearchHitField field = new SearchHitField().parse(parser);
                result.put(field.getName(), field);
            }
            return result;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
