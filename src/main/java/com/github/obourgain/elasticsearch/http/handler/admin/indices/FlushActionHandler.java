package com.github.obourgain.elasticsearch.http.handler.admin.indices;

import static com.github.obourgain.elasticsearch.http.response.ValidStatusCodes._404;
import java.util.Set;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.flush.FlushAction;
import org.elasticsearch.action.admin.indices.flush.FlushRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.github.obourgain.elasticsearch.http.client.HttpClient;
import com.github.obourgain.elasticsearch.http.client.HttpIndicesAdminClient;
import com.github.obourgain.elasticsearch.http.concurrent.ListenerAsyncCompletionHandler;
import com.github.obourgain.elasticsearch.http.request.HttpRequestUtils;
import com.github.obourgain.elasticsearch.http.response.admin.indices.flush.FlushResponse;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;

/**
 * @author olivier bourgain
 */
public class FlushActionHandler {

    private static final Logger logger = LoggerFactory.getLogger(FlushActionHandler.class);

    private final HttpIndicesAdminClient indicesAdminClient;

    public FlushActionHandler(HttpIndicesAdminClient indicesAdminClient) {
        this.indicesAdminClient = indicesAdminClient;
    }

    public FlushAction getAction() {
        return FlushAction.INSTANCE;
    }

    public void execute(FlushRequest request, final ActionListener<FlushResponse> listener) {
        logger.debug("flush request {}", request);
        try {
            HttpClient httpClient = indicesAdminClient.getHttpClient();

            String indices = HttpRequestUtils.indicesOrAll(request);

            AsyncHttpClient.BoundRequestBuilder httpRequest = httpClient.asyncHttpClient.preparePost(httpClient.getUrl() + "/" + indices + "/_flush");

            HttpRequestUtils.addIndicesOptions(httpRequest, request);
            httpRequest.addQueryParam("force", String.valueOf(request.force()));
            httpRequest.addQueryParam("full", String.valueOf(request.full()));
            httpRequest.addQueryParam("wait_if_ongoing", String.valueOf(request.waitIfOngoing()));
            httpRequest
                    .execute(new ListenerAsyncCompletionHandler<FlushResponse>(listener) {
                        @Override
                        protected FlushResponse convert(Response response) {
                            return FlushResponse.parse(response);
                        }

                        @Override
                        protected Set<Integer> non200ValidStatuses() {
                            return _404;
                        }
                    });
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }
}
