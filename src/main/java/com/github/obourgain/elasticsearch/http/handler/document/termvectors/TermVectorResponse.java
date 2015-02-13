package com.github.obourgain.elasticsearch.http.handler.document.termvectors;

import com.github.obourgain.elasticsearch.http.response.entity.TermVector;
import lombok.Getter;
import lombok.Builder;

@Builder
@Getter
public class TermVectorResponse {

    private String index;
    private String type;
    private String id;
    private long version;
    private boolean found;
    private TermVector termVector;

}