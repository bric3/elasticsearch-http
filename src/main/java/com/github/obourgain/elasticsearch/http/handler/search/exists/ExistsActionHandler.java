package com.github.obourgain.elasticsearch.http.handler.search.exists;

import static com.github.obourgain.elasticsearch.http.request.HttpRequestUtils.indicesOrAll;
import static com.github.obourgain.elasticsearch.http.response.ErrorHandler.HANDLES_404;
import java.nio.charset.Charset;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.exists.ExistsAction;
import org.elasticsearch.action.exists.ExistsRequest;
import org.elasticsearch.action.exists.ExistsRequestAccessor;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.github.obourgain.elasticsearch.http.client.HttpClient;
import com.github.obourgain.elasticsearch.http.concurrent.ListenerCompleterObserver;
import com.github.obourgain.elasticsearch.http.request.RequestUriBuilder;
import io.netty.buffer.ByteBuf;
import io.reactivex.netty.protocol.http.client.HttpClientRequest;
import io.reactivex.netty.protocol.http.client.HttpClientResponse;
import rx.Observable;
import rx.functions.Func1;

/**
 * @author olivier bourgain
 */
public class ExistsActionHandler {

    private static final Logger logger = LoggerFactory.getLogger(ExistsActionHandler.class);

    private final HttpClient httpClient;

    public ExistsActionHandler(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public ExistsAction getAction() {
        return ExistsAction.INSTANCE;
    }

    public void execute(ExistsRequest request, final ActionListener<ExistsResponse> listener) {
        logger.debug("Exists request {}", request);
        try {
            String indices = indicesOrAll(request);
            RequestUriBuilder uriBuilder;
            if (request.types() != null && request.types().length > 0) {
                String types = Strings.arrayToCommaDelimitedString(request.types());
                uriBuilder = new RequestUriBuilder(indices, types);
            } else {
                uriBuilder = new RequestUriBuilder(indices);
            }
            uriBuilder.addEndpoint("/_search/exists");

            uriBuilder.addQueryParameterIfNotNull("routing", request.routing());
            uriBuilder.addQueryParameterIfNotNull("preference", request.preference());

            float minScore = ExistsRequestAccessor.minScore(request);
            uriBuilder.addQueryParameterIfNotMinusOne("min_score", (long) minScore);
            uriBuilder.addIndicesOptions(request);

            HttpClientRequest<ByteBuf> httpRequest = HttpClientRequest.createGet(uriBuilder.toString());
            BytesReference source = ExistsRequestAccessor.source(request);
            httpRequest.withContent(source.toBytes());

            httpClient.getHttpClient().submit(httpRequest)
                    .flatMap(HANDLES_404)
                    .flatMap(new Func1<HttpClientResponse<ByteBuf>, Observable<ExistsResponse>>() {
                        @Override
                        public Observable<ExistsResponse> call(final HttpClientResponse<ByteBuf> response) {
                            return response.getContent().flatMap(new Func1<ByteBuf, Observable<ExistsResponse>>() {
                                @Override
                                public Observable<ExistsResponse> call(ByteBuf byteBuf) {
                                    System.out.println(byteBuf.toString(Charset.defaultCharset()));
                                    return ExistsResponse.parse(byteBuf);
                                }
                            });
                        }
                    })
                    .single()
                    .subscribe(new ListenerCompleterObserver<>(listener));

        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

}
