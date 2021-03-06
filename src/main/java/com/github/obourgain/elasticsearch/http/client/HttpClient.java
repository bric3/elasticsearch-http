package com.github.obourgain.elasticsearch.http.client;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Future;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.count.CountRequest;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.deletebyquery.DeleteByQueryRequest;
import org.elasticsearch.action.exists.ExistsRequest;
import org.elasticsearch.action.explain.ExplainRequest;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.MultiGetRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.mlt.MoreLikeThisRequest;
import org.elasticsearch.action.percolate.MultiPercolateRequest;
import org.elasticsearch.action.percolate.PercolateRequest;
import org.elasticsearch.action.search.ClearScrollRequest;
import org.elasticsearch.action.search.MultiSearchRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchScrollRequest;
import org.elasticsearch.action.suggest.SuggestRequest;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.action.termvector.TermVectorRequest;
import org.elasticsearch.action.update.UpdateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.github.obourgain.elasticsearch.http.concurrent.SnapshotableCopyOnWriteArray;
import com.github.obourgain.elasticsearch.http.handler.document.bulk.BulkActionHandler;
import com.github.obourgain.elasticsearch.http.handler.document.bulk.BulkResponse;
import com.github.obourgain.elasticsearch.http.handler.document.delete.DeleteActionHandler;
import com.github.obourgain.elasticsearch.http.handler.document.delete.DeleteResponse;
import com.github.obourgain.elasticsearch.http.handler.document.deleteByQuery.DeleteByQueryActionHandler;
import com.github.obourgain.elasticsearch.http.handler.document.deleteByQuery.DeleteByQueryResponse;
import com.github.obourgain.elasticsearch.http.handler.document.get.GetActionHandler;
import com.github.obourgain.elasticsearch.http.handler.document.get.GetResponse;
import com.github.obourgain.elasticsearch.http.handler.document.index.IndexActionHandler;
import com.github.obourgain.elasticsearch.http.handler.document.index.IndexResponse;
import com.github.obourgain.elasticsearch.http.handler.document.morelikethis.MoreLikeThisActionHandler;
import com.github.obourgain.elasticsearch.http.handler.document.multiget.MultiGetActionHandler;
import com.github.obourgain.elasticsearch.http.handler.document.multiget.MultiGetResponse;
import com.github.obourgain.elasticsearch.http.handler.document.termvectors.TermVectorResponse;
import com.github.obourgain.elasticsearch.http.handler.document.termvectors.TermVectorsActionHandler;
import com.github.obourgain.elasticsearch.http.handler.document.update.UpdateActionHandler;
import com.github.obourgain.elasticsearch.http.handler.document.update.UpdateResponse;
import com.github.obourgain.elasticsearch.http.handler.search.clearscroll.ClearScrollActionHandler;
import com.github.obourgain.elasticsearch.http.handler.search.clearscroll.ClearScrollResponse;
import com.github.obourgain.elasticsearch.http.handler.search.count.CountActionHandler;
import com.github.obourgain.elasticsearch.http.handler.search.count.CountResponse;
import com.github.obourgain.elasticsearch.http.handler.search.exists.ExistsActionHandler;
import com.github.obourgain.elasticsearch.http.handler.search.exists.ExistsResponse;
import com.github.obourgain.elasticsearch.http.handler.search.explain.ExplainActionHandler;
import com.github.obourgain.elasticsearch.http.handler.search.explain.ExplainResponse;
import com.github.obourgain.elasticsearch.http.handler.search.multipercolate.MultiPercolateActionHandler;
import com.github.obourgain.elasticsearch.http.handler.search.multipercolate.MultiPercolateResponse;
import com.github.obourgain.elasticsearch.http.handler.search.percolate.PercolateActionHandler;
import com.github.obourgain.elasticsearch.http.handler.search.percolate.PercolateResponse;
import com.github.obourgain.elasticsearch.http.handler.search.search.MultiSearchActionHandler;
import com.github.obourgain.elasticsearch.http.handler.search.search.MultiSearchResponse;
import com.github.obourgain.elasticsearch.http.handler.search.search.SearchActionHandler;
import com.github.obourgain.elasticsearch.http.handler.search.search.SearchResponse;
import com.github.obourgain.elasticsearch.http.handler.search.search.SearchScrollActionHandler;
import com.github.obourgain.elasticsearch.http.handler.search.suggest.SuggestActionHandler;
import com.github.obourgain.elasticsearch.http.handler.search.suggest.SuggestResponse;
import com.google.common.base.Supplier;
import io.netty.buffer.ByteBuf;
import io.reactivex.netty.RxNetty;
import io.reactivex.netty.client.RxClient;
import io.reactivex.netty.protocol.http.client.HttpClientBuilder;

/**
 * @author olivier bourgain
 */
public class HttpClient {

    private static final Logger logger = LoggerFactory.getLogger(HttpClient.class);

    private static final int DEFAULT_MAX_CONNECTIONS = 10;
    private static final int DEFAULT_TIMEOUT_MILLIS = 30 * 1000 * 1000;

    private SnapshotableCopyOnWriteArray<io.reactivex.netty.protocol.http.client.HttpClient<ByteBuf, ByteBuf>> clients;
    private Supplier<io.reactivex.netty.protocol.http.client.HttpClient<ByteBuf, ByteBuf>> clientSupplier;

    private int maxConnections = DEFAULT_MAX_CONNECTIONS;
    private int timeOut = DEFAULT_TIMEOUT_MILLIS;
    private HttpAdminClient httpAdminClient;

    private IndexActionHandler indexActionHandler = new IndexActionHandler(this);
    private GetActionHandler getActionHandler = new GetActionHandler(this);
    private MultiGetActionHandler multiGetActionHandler = new MultiGetActionHandler(this);
    private DeleteActionHandler deleteActionHandler = new DeleteActionHandler(this);
    private UpdateActionHandler updateActionHandler = new UpdateActionHandler(this);
    private DeleteByQueryActionHandler deleteByQueryActionHandler = new DeleteByQueryActionHandler(this);
    private TermVectorsActionHandler termVectorActionHandler = new TermVectorsActionHandler(this);
    private SearchActionHandler searchActionHandler = new SearchActionHandler(this);
    private MultiSearchActionHandler multiSearchActionHandler = new MultiSearchActionHandler(this);
    private CountActionHandler countActionHandler = new CountActionHandler(this);
    private ExistsActionHandler existsActionHandler = new ExistsActionHandler(this);
    private ExplainActionHandler explainActionHandler = new ExplainActionHandler(this);
    private PercolateActionHandler percolateActionHandler = new PercolateActionHandler(this);
    private MultiPercolateActionHandler multiPercolateActionHandler = new MultiPercolateActionHandler(this);
    private MoreLikeThisActionHandler moreLikeThisActionHandler = new MoreLikeThisActionHandler(this);
    private ClearScrollActionHandler clearScrollActionHandler = new ClearScrollActionHandler(this);
    private SearchScrollActionHandler searchScrollActionHandler = new SearchScrollActionHandler(this);
    private BulkActionHandler bulkActionHandler = new BulkActionHandler(this);
    private SuggestActionHandler suggestActionHandler = new SuggestActionHandler(this);

    public HttpClient(String ... nodes) {
        this(Arrays.asList(nodes));
    }

    public HttpClient(Collection<String> nodes) {
        // searchShard
        // search template

        List<io.reactivex.netty.protocol.http.client.HttpClient<ByteBuf, ByteBuf>> clientsTemp = new ArrayList<>();
        // expect something like "http://%s:%d"
        for (String node : nodes) {
            String[] next = node.split(":");
            // indices admin
            String host = next[1].substring(2); // remove the // of http://
            int port = Integer.parseInt(next[2]);
            HttpClientBuilder<ByteBuf, ByteBuf> clientBuilder = RxNetty.newHttpClientBuilder(host, port);
            clientBuilder.config(new RxClient.ClientConfig.Builder().readTimeout(timeOut, MILLISECONDS).build());
            clientBuilder.withMaxConnections(maxConnections);
            clientsTemp.add(clientBuilder.build());
            logger.info("adding host {}:{}", host, port);
        }
        this.clients = new SnapshotableCopyOnWriteArray<>(clientsTemp);

        clientSupplier = new RoundRobinSupplier<>(clients);

        this.httpAdminClient = new HttpAdminClient(clientSupplier);
    }

    public void close() {
        // TODO prevent servers to be added while here
        for (io.reactivex.netty.protocol.http.client.HttpClient<ByteBuf, ByteBuf> client : clients.snapshot()) {
            client.shutdown();
        }
    }

    public HttpAdminClient admin() {
        return httpAdminClient;
    }

    public io.reactivex.netty.protocol.http.client.HttpClient<ByteBuf, ByteBuf> getHttpClient() {
        return clientSupplier.get();
    }

    public void index(IndexRequest request, ActionListener<IndexResponse> listener) {
        indexActionHandler.execute(request, listener);
    }

    public Future<IndexResponse> index(IndexRequest request) {
        PlainActionFuture<IndexResponse> future = PlainActionFuture.newFuture();
        index(request, future);
        return future;
    }

    public void get(GetRequest request, ActionListener<GetResponse> listener) {
        getActionHandler.execute(request, listener);
    }

    public Future<GetResponse> get(GetRequest request) {
        PlainActionFuture<GetResponse> future = PlainActionFuture.newFuture();
        get(request, future);
        return future;
    }

    public void multiGet(MultiGetRequest request, ActionListener<MultiGetResponse> listener) {
        multiGetActionHandler.execute(request, listener);
    }

    public Future<MultiGetResponse> multiGet(MultiGetRequest request) {
        PlainActionFuture<MultiGetResponse> future = PlainActionFuture.newFuture();
        multiGet(request, future);
        return future;
    }

    public void delete(DeleteRequest request, ActionListener<DeleteResponse> listener) {
        deleteActionHandler.execute(request, listener);
    }

    public Future<DeleteResponse> delete(DeleteRequest request) {
        PlainActionFuture<DeleteResponse> future = PlainActionFuture.newFuture();
        delete(request, future);
        return future;
    }

    public void update(UpdateRequest request, ActionListener<UpdateResponse> listener) {
        updateActionHandler.execute(request, listener);
    }

    public Future<UpdateResponse> update(UpdateRequest request) {
        PlainActionFuture<UpdateResponse> future = PlainActionFuture.newFuture();
        update(request, future);
        return future;
    }

    public void deleteByQuery(DeleteByQueryRequest request, ActionListener<DeleteByQueryResponse> listener) {
        deleteByQueryActionHandler.execute(request, listener);
    }

    public Future<DeleteByQueryResponse> deleteByQuery(DeleteByQueryRequest request) {
        PlainActionFuture<DeleteByQueryResponse> future = PlainActionFuture.newFuture();
        deleteByQuery(request, future);
        return future;
    }

    public void termVectors(TermVectorRequest request, ActionListener<TermVectorResponse> listener) {
        termVectorActionHandler.execute(request, listener);
    }

    public Future<TermVectorResponse> termVectors(TermVectorRequest request) {
        PlainActionFuture<TermVectorResponse> future = PlainActionFuture.newFuture();
        termVectors(request, future);
        return future;
    }

    public void search(SearchRequest request, ActionListener<SearchResponse> listener) {
        searchActionHandler.execute(request, listener);
    }

    public Future<SearchResponse> search(SearchRequest request) {
        PlainActionFuture<SearchResponse> future = PlainActionFuture.newFuture();
        search(request, future);
        return future;
    }

    public void multiSearch(MultiSearchRequest request, ActionListener<MultiSearchResponse> listener) {
        multiSearchActionHandler.execute(request, listener);
    }

    public Future<MultiSearchResponse> multiSearch(MultiSearchRequest request) {
        PlainActionFuture<MultiSearchResponse> future = PlainActionFuture.newFuture();
        multiSearch(request, future);
        return future;
    }

    public void count(CountRequest request, ActionListener<CountResponse> listener) {
        countActionHandler.execute(request, listener);
    }

    public Future<CountResponse> count(CountRequest request) {
        PlainActionFuture<CountResponse> future = PlainActionFuture.newFuture();
        count(request, future);
        return future;
    }

    public void exists(ExistsRequest request, ActionListener<ExistsResponse> listener) {
        existsActionHandler.execute(request, listener);
    }

    public Future<ExistsResponse> exists(ExistsRequest request) {
        PlainActionFuture<ExistsResponse> future = PlainActionFuture.newFuture();
        exists(request, future);
        return future;
    }

    public void explain(ExplainRequest request, ActionListener<ExplainResponse> listener) {
        explainActionHandler.execute(request, listener);
    }

    public Future<ExplainResponse> explain(ExplainRequest request) {
        PlainActionFuture<ExplainResponse> future = PlainActionFuture.newFuture();
        explain(request, future);
        return future;
    }

    public void percolate(PercolateRequest request, ActionListener<PercolateResponse> listener) {
        percolateActionHandler.execute(request, listener);
    }

    public Future<PercolateResponse> percolate(PercolateRequest request) {
        PlainActionFuture<PercolateResponse> future = PlainActionFuture.newFuture();
        percolate(request, future);
        return future;
    }

    public void multiPercolate(MultiPercolateRequest request, ActionListener<MultiPercolateResponse> listener) {
        multiPercolateActionHandler.execute(request, listener);
    }

    public Future<MultiPercolateResponse> multiPercolate(MultiPercolateRequest request) {
        PlainActionFuture<MultiPercolateResponse> future = PlainActionFuture.newFuture();
        multiPercolate(request, future);
        return future;
    }

    public void moreLikeThis(MoreLikeThisRequest request, ActionListener<SearchResponse> listener) {
        moreLikeThisActionHandler.execute(request, listener);
    }

    public Future<SearchResponse> moreLikeThis(MoreLikeThisRequest request) {
        PlainActionFuture<SearchResponse> future = PlainActionFuture.newFuture();
        moreLikeThis(request, future);
        return future;
    }

    public void searchScroll(SearchScrollRequest request, ActionListener<SearchResponse> listener) {
        searchScrollActionHandler.execute(request, listener);
    }

    public Future<SearchResponse> searchScroll(SearchScrollRequest request) {
        PlainActionFuture<SearchResponse> future = PlainActionFuture.newFuture();
        searchScroll(request, future);
        return future;
    }

    public void clearScroll(ClearScrollRequest request, ActionListener<ClearScrollResponse> listener) {
        clearScrollActionHandler.execute(request, listener);
    }

    public Future<ClearScrollResponse> clearScroll(ClearScrollRequest request) {
        PlainActionFuture<ClearScrollResponse> future = PlainActionFuture.newFuture();
        clearScroll(request, future);
        return future;
    }

    public void bulk(BulkRequest request, ActionListener<BulkResponse> listener) {
        bulkActionHandler.execute(request, listener);
    }

    public Future<BulkResponse> bulk(BulkRequest request) {
        PlainActionFuture<BulkResponse> future = PlainActionFuture.newFuture();
        bulk(request, future);
        return future;
    }

    public void suggest(SuggestRequest request, ActionListener<SuggestResponse> listener) {
        suggestActionHandler.execute(request, listener);
    }

    public Future<SuggestResponse> suggest(SuggestRequest request) {
        PlainActionFuture<SuggestResponse> future = PlainActionFuture.newFuture();
        suggest(request, future);
        return future;
    }

}
