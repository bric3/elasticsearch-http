package com.github.obourgain.elasticsearch.http.response.entity.aggs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.tuple.Pair;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

public class GeoDistance extends AbstractAggregation {

    private List<Bucket> buckets;

    public GeoDistance(String name) {
        super(name);
    }

    public List<Bucket> getBuckets() {
        return buckets;
    }

    public static GeoDistance parse(XContentParser parser, String name) {
        try {
            GeoDistance range = new GeoDistance(name);
            XContentParser.Token token;
            String currentFieldName = null;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                } else if (token == XContentParser.Token.START_ARRAY && "buckets".equals(currentFieldName)) {
                    range.buckets = parseBuckets(parser);
                }
            }
            return range;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected static List<Bucket> parseBuckets(XContentParser parser) throws IOException {
        XContentParser.Token token;
        List<Bucket> result = new ArrayList<>();
        while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
            if (token == XContentParser.Token.START_OBJECT) {
                result.add(parseBucket(parser));
            }
        }
        return result;
    }

    protected static Bucket parseBucket(XContentParser parser) throws IOException {
        XContentParser.Token token;
        String currentFieldName = null;
        Bucket bucket = new Bucket();
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (token.isValue()) {
                if ("from".equals(currentFieldName)) {
                    bucket.from = parser.doubleValue();
                } else if ("to".equals(currentFieldName)) {
                    bucket.to = parser.doubleValue();
                } else if ("doc_count".equals(currentFieldName)) {
                    bucket.docCount = parser.longValue();
                } else if ("key".equals(currentFieldName)) {
                    bucket.key = parser.text();
                }
            } else if (token == XContentParser.Token.START_OBJECT) {
                Pair<String, XContentBuilder> agg = Aggregations.parseInnerAgg(parser, currentFieldName);
                bucket.addSubAgg(agg.getKey(), agg.getValue());
            }
        }
        return bucket;
    }

    @Getter
    @EqualsAndHashCode(callSuper = true)
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Bucket extends AbstractBucket {
        private String key;
        private Double from;
        private Double to;
        private long docCount;
    }
}
