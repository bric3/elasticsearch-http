package org.elasticsearch.action.admin.indices.template.get;

import java.util.List;
import org.elasticsearch.cluster.metadata.IndexTemplateMetaData;

/**
 * @author olivier bourgain
 */
public class GetIndexTemplatesResponseAccessor {

    public static GetIndexTemplatesResponse create(List<IndexTemplateMetaData> metaData) {
        return new GetIndexTemplatesResponse(metaData);
    }
}
