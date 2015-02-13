package com.github.obourgain.elasticsearch.http.handler.document.delete;

import lombok.Getter;
import lombok.Builder;

@Builder
@Getter
public class DeleteResponse {

    private String index;
    private String type;
    private String id;
    private long version;
    private boolean found;

}
