package com.github.obourgain.elasticsearch.http.response.entity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.elasticsearch.common.xcontent.XContentParser;
import lombok.Getter;

@Getter
public class Term {

    private String term;
    private Integer docFreq;
    private int termFreq;
    private Integer totalTermFreq;
    private List<Token> tokens;

    public Term parse(XContentParser parser) {
        try {
            term = parser.text();
            parser.nextToken();
            assert parser.currentToken() == XContentParser.Token.START_OBJECT : "expected a START_OBJECT token but was " + parser.currentToken();
            XContentParser.Token token;
            String currentFieldName = null;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                } else if (token.isValue()) {
                    if ("doc_freq".equals(currentFieldName)) {
                        docFreq = parser.intValue();
                    } else if ("term_freq".equals(currentFieldName)) {
                        termFreq = parser.intValue();
                    } else if ("ttf".equals(currentFieldName)) {
                        totalTermFreq = parser.intValue();
                    }
                } else if (token == XContentParser.Token.START_ARRAY) {
                    if ("tokens".equals(currentFieldName)) {
                        tokens = Token.parseList(parser);
                    }
                }
            }
            return this;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected static List<Term> parseTerms(XContentParser parser) {
        try {
            assert parser.currentToken() == XContentParser.Token.START_OBJECT : "expected a START_OBJECT token but was " + parser.currentToken();
            List<Term> terms = new ArrayList<>();
            while (parser.nextToken() != XContentParser.Token.END_OBJECT) {
                assert parser.currentToken() == XContentParser.Token.FIELD_NAME : "expected a FIELD_NAME token but was " + parser.currentToken();
                terms.add(new Term().parse(parser));
            }
            return terms;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
