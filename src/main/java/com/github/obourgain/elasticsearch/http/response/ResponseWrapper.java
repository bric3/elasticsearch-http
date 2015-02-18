package com.github.obourgain.elasticsearch.http.response;

import static com.google.common.base.Objects.firstNonNull;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchIllegalArgumentException;
import org.elasticsearch.ElasticsearchIllegalStateException;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.RoutingMissingException;
import org.elasticsearch.action.UnavailableShardsException;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponseAccessor;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthStatus;
import org.elasticsearch.action.admin.cluster.health.ClusterIndexHealth;
import org.elasticsearch.action.admin.cluster.health.ClusterShardHealth;
import org.elasticsearch.action.admin.cluster.health.ClusterShardHealthAccessor;
import org.elasticsearch.action.admin.cluster.node.hotthreads.NodesHotThreadsResponse;
import org.elasticsearch.action.admin.cluster.settings.ClusterUpdateSettingsResponse;
import org.elasticsearch.action.admin.cluster.settings.ClusterUpdateSettingsResponseAccessor;
import org.elasticsearch.action.admin.cluster.state.ClusterStateResponse;
import org.elasticsearch.action.admin.cluster.state.ClusterStateResponseAccessor;
import org.elasticsearch.action.admin.cluster.stats.ClusterStatsResponse;
import org.elasticsearch.action.admin.cluster.stats.ClusterStatsResponseAccessor;
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesResponse;
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsResponse;
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsResponseAccessor;
import org.elasticsearch.action.admin.indices.settings.get.GetSettingsResponse;
import org.elasticsearch.action.admin.indices.template.get.GetIndexTemplatesResponse;
import org.elasticsearch.action.admin.indices.template.get.GetIndexTemplatesResponseAccessor;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.get.GetResponseAccessor;
import org.elasticsearch.action.get.MultiGetItemResponse;
import org.elasticsearch.action.get.MultiGetResponse;
import org.elasticsearch.action.search.ClearScrollResponse;
import org.elasticsearch.action.search.ReduceSearchPhaseException;
import org.elasticsearch.action.search.SearchPhaseExecutionException;
import org.elasticsearch.action.search.ShardSearchFailure;
import org.elasticsearch.action.suggest.SuggestResponse;
import org.elasticsearch.action.suggest.SuggestResponseAccessor;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlock;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.block.ClusterBlocks;
import org.elasticsearch.cluster.metadata.AliasMetaData;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.IndexTemplateMetaData;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.ImmutableShardRouting;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.routing.IndexShardRoutingTable;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.ImmutableOpenIntMap;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.compress.CompressedString;
import org.elasticsearch.common.hppc.cursors.ObjectObjectCursor;
import org.elasticsearch.common.io.stream.DataOutputStreamOutput;
import org.elasticsearch.common.io.stream.InputStreamStreamInput;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.jackson.core.JsonFactory;
import org.elasticsearch.common.jackson.core.JsonLocation;
import org.elasticsearch.common.jackson.core.JsonParseException;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.text.StringText;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.transport.LocalTransportAddress;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.common.xcontent.json.JsonXContentParser;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.engine.DocumentAlreadyExistsException;
import org.elasticsearch.index.engine.DocumentMissingException;
import org.elasticsearch.index.engine.VersionConflictEngineException;
import org.elasticsearch.index.get.GetField;
import org.elasticsearch.index.get.GetResult;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.indices.IndexAlreadyExistsException;
import org.elasticsearch.indices.IndexClosedException;
import org.elasticsearch.indices.IndexMissingException;
import org.elasticsearch.indices.InvalidIndexNameException;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.rest.action.admin.indices.alias.delete.AliasesMissingException;
import org.elasticsearch.script.expression.ExpressionScriptCompilationException;
import org.elasticsearch.search.SearchHitField;
import org.elasticsearch.search.SearchShardTarget;
import org.elasticsearch.search.highlight.HighlightField;
import org.elasticsearch.search.internal.InternalSearchHit;
import org.elasticsearch.search.internal.InternalSearchHitField;
import org.elasticsearch.search.suggest.Suggest;
import org.elasticsearch.search.warmer.IndexWarmersMetaData;
import org.elasticsearch.transport.RemoteTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

/**
 * @author olivier bourgain
 */
public class ResponseWrapper<Req> {

    private static final Logger logger = LoggerFactory.getLogger(ResponseWrapper.class);

    private static ImmutableOpenIntMap<RestStatus> statusCodeToStatusName;

    static {
        ImmutableOpenIntMap.Builder<RestStatus> builder = ImmutableOpenIntMap.builder();
        for (RestStatus restStatus : RestStatus.values()) {
            builder.put(restStatus.getStatus(), restStatus);
        }
        statusCodeToStatusName = builder.build();
    }

    private EntityWrapper entityWrapper;
    //    private Response response;
    private static final Joiner settingsJoiner = Joiner.on(".");

    private Req request;

//    public ResponseWrapper(Response response) {
//        this(null, response);
//    }
//
//    public ResponseWrapper(Req request, Response response) {
//        logger.debug("create a response wrapper for {}", response);
//        this.request = request;
//        this.response = response;
//        try {
//            this.entityWrapper = new EntityWrapper(response.getResponseBody());
//        } catch (IOException e) {
//            throw new RuntimeException(e);
//        }
//    }

    protected String getIndex(Map<String, Object> map) {
        return getAsString(map, "_index");
    }

    protected String getType(Map<String, Object> map) {
        return getAsString(map, "_type");
    }

    protected String getId(Map<String, Object> map) {
        return getAsString(map, "_id");
    }

    protected Long getVersion(Map<String, Object> map) {
        // return as an Object because it seems from the org.elasticsearch.index.get.GetResult.toXContent source that the version may be absent
        // note : this is also required for the explain request as version is not present in the get part when source is in query params
        Number version = getAsNumber(map, "_version");
        return version != null ? version.longValue() : null;
    }

    protected int getTotalShards() {
        return (int) ((Map) entityWrapper.get("_shards")).get("total");
    }

    protected int getSuccessfulShards() {
        return (int) ((Map) entityWrapper.get("_shards")).get("successful");
    }

    protected int getFailedShards() {
        return (int) ((Map) entityWrapper.get("_shards")).get("failed");
    }

    protected boolean isFound(Map<String, Object> map) {
        return getAs(map, "found", boolean.class);
    }

    private Boolean getHasTerminatedEarly() {
        return getAs(entityWrapper, "terminated_early", Boolean.class);
    }

    protected BytesReference getSourceAsBytes(Map<String, Object> map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> source = getAsStringObjectMap(map, "_source");
        if (source != null) {
            try {
                return XContentFactory.contentBuilder(XContentType.JSON).map(source).bytes();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static <T> T getAs(Map<String, Object> map, String key, Class<T> type) {
        return (T) map.get(key);
    }

    @Nullable
    private static String getAsString(Map<String, Object> map, String key) {
        return getAs(map, key, String.class);
    }

    @Nullable
    private static Number getAsNumber(Map<String, Object> map, String key) {
        return getAs(map, key, Number.class);
    }

    @Nullable
    private static Map<String, Object> getAsStringObjectMap(Map map, String key) {
        return (Map<String, Object>) getAs(map, key, Map.class);
    }

    @Nullable
    private Map<String, Map<String, Object>> getAsNestedStringToMapMap(Map map, String key) {
        // hide the unchecked cast under the carpet
        return (Map<String, Map<String, Object>>) getAs(map, key, Map.class);
    }

    @Nullable
    private static Map<String, List<String>> getAsStringListOfStringsMap(Map map, String key) {
        return (Map<String, List<String>>) getAs(map, key, Map.class);
    }

    @Nullable
    private List<Map<String, Object>> getAsListOfStringObjectMap(Map map, String key) {
        return (List<Map<String, Object>>) getAs(map, key, List.class);
    }

    @Nullable
    private List<Map<String, Map<String, Object>>> getAsListOfNestedStringObjectMaps(Map map, String key) {
        return (List<Map<String, Map<String, Object>>>) getAs(map, key, List.class);
    }

    public Map<String, GetField> getFields(Map<String, Object> fieldsAsMap) {
        Map<String, Object> fields = getAsStringObjectMap(fieldsAsMap, "fields");
        return getResultToMapOfGetFields(fields);
    }

    @Nullable
    private Suggest processSuggestions(Map<String, Object> suggestAsMap) {
        if (suggestAsMap == null) {
            return null;
        }
        List<Suggest.Suggestion<? extends Suggest.Suggestion.Entry<? extends Suggest.Suggestion.Entry.Option>>> result = new ArrayList<>();
        for (Map.Entry<String, Object> entry : suggestAsMap.entrySet()) {
            List<Map<String, Object>> suggestions = (List<Map<String, Object>>) entry.getValue();
            Suggest.Suggestion<Suggest.Suggestion.Entry<Suggest.Suggestion.Entry.Option>> suggestion = new Suggest.Suggestion<>(entry.getKey(), suggestions.size());
            result.add(suggestion);

            for (Map<String, Object> suggestionAsMap : suggestions) {
                String text = getAsString(suggestionAsMap, "text");
                int offset = getAsNumber(suggestionAsMap, "offset").intValue();
                int length = getAsNumber(suggestionAsMap, "length").intValue();
                List<Map<String, Object>> options = getAsListOfStringObjectMap(suggestionAsMap, "options");
                Suggest.Suggestion.Entry<Suggest.Suggestion.Entry.Option> optionEntry = new Suggest.Suggestion.Entry<>(new StringText(text), offset, length);
                for (Map<String, Object> option : options) {
                    String optionText = getAsString(option, "text");
                    float score = getAsNumber(option, "score").floatValue();
                    // TODO handle all suggest types
//                    int freq = getAsNumber(option, "freq").intValue();
                    optionEntry.addOption(new Suggest.Suggestion.Entry.Option(new StringText(optionText), score));
                }
                suggestion.addTerm(optionEntry);
            }
        }
        return new Suggest(result);
    }

    public static InternalSearchHit[] processInternalSearchHits(List<Map<String, Object>> internalHitsAsMap) {
        InternalSearchHit[] internalSearchHits = new InternalSearchHit[internalHitsAsMap.size()];
        for (int i = 0; i < internalHitsAsMap.size(); i++) {
            Map<String, Object> internalHit = internalHitsAsMap.get(i);
            Map<String, SearchHitField> fields;
            if (internalHit.get("fields") != null) {
                fields = new HashMap<>();
                Map<String, Object> hitFields = getAsStringObjectMap(internalHit, "fields");
                for (Map.Entry<String, Object> entry : hitFields.entrySet()) {
                    // value may be a List<String> or a single String
                    Object value = entry.getValue();
                    fields.put(entry.getKey(), new InternalSearchHitField(entry.getKey(), value instanceof List ? (List<Object>) value : Collections.singletonList(value)));
                }
            } else {
                fields = ImmutableMap.of();
            }

            // first param is the docId, I can not set it because it isn't returned by the API, but I don't care because it is not exposed by the SearchHit API
            InternalSearchHit internalSearchHit = new InternalSearchHit(-1,
                    (String) internalHit.get("_id"),
                    new StringText((String) internalHit.get("_type")),
                    fields);

            Number version = getAsNumber(internalHit, "_version");
            if (version != null) {
                internalSearchHit.version(version.longValue());
            }

            List<String> matchedQueries = getAs(internalHit, "matched_queries", List.class);
            if (matchedQueries != null) {
                internalSearchHit.matchedQueries(Iterables.toArray(matchedQueries, String.class));
            }

            Map<String, Object> source = getAsStringObjectMap(internalHit, "_source");
            if (source != null) {
                try {
                    internalSearchHit.sourceRef(XContentFactory.contentBuilder(XContentType.JSON).map(source).bytes());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            Map<String, List<String>> highlight = getAsStringListOfStringsMap(internalHit, "highlight");
            if (highlight != null) {
                Map<String, HighlightField> highlightFieldMap = new HashMap<>();
                for (Map.Entry<String, List<String>> entry : highlight.entrySet()) {
                    List<String> value = entry.getValue();
                    Text[] texts = stringCollectionToTextArray(value);
                    HighlightField highlightField = new HighlightField(entry.getKey(), texts);
                    highlightFieldMap.put(entry.getKey(), highlightField);
                }
                internalSearchHit.highlightFields(ImmutableMap.copyOf(highlightFieldMap));
            } else {
                internalSearchHit.highlightFields(ImmutableMap.<String, HighlightField>of());
            }

            Map<String, Object> explanationAsMap = getAsStringObjectMap(internalHit, "_explanation");
            if (explanationAsMap != null) {
                Explanation explanation = toExplanation(explanationAsMap);
                internalSearchHit.explanation(explanation);
            }

            // I don't need this object as it is internal to the API, but internalSearchHit.index() will return shard.index()
            // maybe I should write my own implementation of SearchHit
            internalSearchHit.shard(new SearchShardTarget(null, (String) internalHit.get("_index"), -1));

            Number score = (Number) internalHit.get("_score");
            if (score != null) {
                // score may be null if the query uses a sort
                internalSearchHit.score(score.floatValue());
            } else {
                internalSearchHit.score(Float.NaN);
            }
            Object sort = internalHit.get("sort");
            if (sort != null) {
                // dependant on jackson impl here
                List sortAsList = (List) sort;
                Object[] sortAsArray = new Object[sortAsList.size()];
                for (int i1 = 0; i1 < sortAsList.size(); i1++) {
                    Object o = sortAsList.get(i1);
                    // TODO what other kind of data can be returned for sort ?
                    if (o == null) {
                        sortAsArray[i1] = null;
                    } else if (o instanceof Double) {
                        sortAsArray[i1] = o;
                    } else if (o instanceof Number) {
                        sortAsArray[i1] = ((Number) o).longValue();
                    } else {
                        sortAsArray[i1] = new BytesRef(String.valueOf(o));
                    }
                }
                internalSearchHit.sortValues(sortAsArray);
            }
            internalSearchHits[i] = internalSearchHit;
        }
        return internalSearchHits;
    }

    public ClearScrollResponse toClearScrollResponse() {
        String error = getAs(entityWrapper, "error", String.class);
        // if the action succeeded, the response is an empty json object, if it has failed, the response is an object with fields error and status
        // TODO numFreed is not returned, and org.elasticsearch.search.scroll.SearchScrollTests.testClearNonExistentScrollId() tests it as being 0
        return new ClearScrollResponse(error == null, 0);
    }

    private GetResult toExplainGetResult(boolean found, String index, String type, String id, Map<String, Object> getAsMap) {
        if (getAsMap != null) {
            BytesReference sourceAsBytes = getSourceAsBytes(getAsMap);
            Map<String, Object> fieldsAsMap = getAsStringObjectMap(getAsMap, "fields");
            Map<String, GetField> fields = getResultToMapOfGetFields(fieldsAsMap);

            return new GetResult(index, type, id,
                    // version is not returned in explain response
                    -1,
                    found,
                    sourceAsBytes,
                    fields
            );
        }
        return null;
    }

    private static Explanation toExplanation(Map<String, Object> explanationAsMap) {
        float value = ((Number) explanationAsMap.get("value")).floatValue();
        String description = (String) explanationAsMap.get("description");
        Explanation explanation = new Explanation(value, description);
        List<Map<String, Object>> details = getAs(explanationAsMap, "details", List.class);
        if (details != null) {
            for (Map<String, Object> detail : details) {
                Explanation subExplanation = toExplanation(detail);
                explanation.addDetail(subExplanation);
            }
        }
        return explanation;
    }

    public SuggestResponse toSuggestResponse() {
        List<Suggest.Suggestion> suggestions = new ArrayList<>();

        // TODO each type of suggester is different from the other ...

        for (Map.Entry<String, Object> entry : entityWrapper.entrySet()) {
            if (entry.getKey().equals("_shards")) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Map) {
                Map<String, Object> suggestAsMap = (Map<String, Object>) value;
                String text = getAsString(suggestAsMap, "text");
                int offset = getAsNumber(suggestAsMap, "offset").intValue();
                int length = getAsNumber(suggestAsMap, "length").intValue();
                List<Map<String, Object>> optionsAsMaps = getAsListOfStringObjectMap(suggestAsMap, "options");
                Suggest.Suggestion suggestion = new Suggest.Suggestion();
                for (Map<String, Object> optionAsMap : optionsAsMaps) {
                    String optionText = (String) optionAsMap.get("text");
                    float score = ((Number) suggestAsMap.get("score")).floatValue();
                    Suggest.Suggestion.Entry.Option option = new Suggest.Suggestion.Entry.Option(new StringText(text), score);
                    // TODO finish
                }
            }
        }

        Suggest suggest = new Suggest();
        // TODO failures
        return SuggestResponseAccessor.build(suggest, getTotalShards(), getSuccessfulShards(), getFailedShards(), null);
    }

    private GetResult toGetResult(Map<String, Object> metadataMap, Map<String, Object> fieldsMap) {
        // fields and doc metadata may be on a different nesting level, e.g. for update with get
        boolean found = isFound(fieldsMap);
        Long version = getVersion(fieldsMap);
        return new GetResult(getIndex(metadataMap), getType(metadataMap), getId(metadataMap),
                // version is a primitive long with value -1 when the doc is not found
                found && version != null ? version : -1,
                found,
                found ? getSourceAsBytes(fieldsMap) : null,
                found ? getFields(fieldsMap) : null
        );
    }

    private static Map<String, GetField> getResultToMapOfGetFields(@Nullable Map<String, Object> fieldsAsMap) {
        if (fieldsAsMap == null) {
            return ImmutableMap.of();
        }
        Map<String, GetField> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : fieldsAsMap.entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof List) {
                result.put(name, new GetField(name, (List<Object>) value));
            } else {
                result.put(name, new GetField(name, Collections.singletonList(value)));
            }
        }
        return result;
    }

    private static Text[] stringCollectionToTextArray(List<String> strings) {
        if (strings == null) {
            return null;
        }
        Text[] texts = new Text[strings.size()];
        for (int i = 0; i < strings.size(); i++) {
            texts[i] = new StringText(strings.get(i));
        }
        return texts;
    }

    public void error() {
        String error = getAs(entityWrapper, "error", String.class);
        error(error);
    }

    public void error(String error) {
        /* {
                "error": "NoShardAvailableActionException[[the_index][4] null]",
                "status": 503
            }

            "error": "ActionRequestValidationException[Validation Failed: 1: script or doc is missing;]",
            "error": "MapperParsingException[Malformed content, must start with an object]",
            ...
        */
        if (error != null) {
            logger.debug("received error {}", error);
            String unwrappedError;
            // TODO maybe use a substring to get the exception's name and a java 7 string switch
            if (error.startsWith(RemoteTransportException.class.getSimpleName())) {
                // unwrap before trying to rebuild the cause
                int startNested = error.indexOf("nested") + "nested: ".length();
                unwrappedError = error.substring(startNested);
            } else {
                unwrappedError = error;
            }
            if (unwrappedError.startsWith(SearchPhaseExecutionException.class.getSimpleName())) {
                // TODO heavily dependant on the output string format ...
                // exemple: SearchPhaseExecutionException[Failed to execute phase [query], all shards failed; shardFailures {[HeTJoSeLRl2ClSWPXZATfA][test][0]: EsRejectedExecutionException[rejected execution (queue capacity 1) on org.elasticsearch.search.action.SearchServiceTransportAction$23@51095b9]}{[Kj0piMkfRJ6cBGg6V028Eg][test][1]: RemoteTransportException[[node_1][inet[/10.0.2.58:9301]][search/phase/query]]; nested: EsRejectedExecutionException[rejected execution (queue capacity 1) on org.elasticsearch.transport.netty.MessageChannelHandler$RequestHandler@45e1d85d]; }{[Kj0piMkfRJ6cBGg6V028Eg][test][5]: RemoteTransportException[[node_1][inet[/10.0.2.58:9301]][search/phase/query]]; nested: EsRejectedExecutionException[rejected execution (queue capacity 1) on org.elasticsearch.transport.netty.MessageChannelHandler$RequestHandler@56d13e2f]; }{[HeTJoSeLRl2ClSWPXZATfA][test][4]: EsRejectedExecutionException[rejected execution (queue capacity 1) on org.elasticsearch.search.action.SearchServiceTransportAction$23@23c6488a]}{[Kj0piMkfRJ6cBGg6V028Eg][test][3]: RemoteTransportException[[node_1][inet[/10.0.2.58:9301]][search/phase/query]]; nested: EsRejectedExecutionException[rejected execution (queue capacity 1) on org.elasticsearch.transport.netty.MessageChannelHandler$RequestHandler@333cbf2f]; }{[HeTJoSeLRl2ClSWPXZATfA][test][2]: EsRejectedExecutionException[rejected execution (queue capacity 1) on org.elasticsearch.search.action.SearchServiceTransportAction$23@1c5b7dc3]}{[Kj0piMkfRJ6cBGg6V028Eg][test][9]: RemoteTransportException[[node_1][inet[/10.0.2.58:9301]][search/phase/query]]; nested: EsRejectedExecutionException[rejected execution (queue capacity 1) on org.elasticsearch.transport.netty.MessageChannelHandler$RequestHandler@a18d964]; }{[HeTJoSeLRl2ClSWPXZATfA][test][8]: EsRejectedExecutionException[rejected execution (queue capacity 1) on org.elasticsearch.search.action.SearchServiceTransportAction$23@1ddeedb6]}{[Kj0piMkfRJ6cBGg6V028Eg][test][7]: RemoteTransportException[[node_1][inet[/10.0.2.58:9301]][search/phase/query]]; nested: EsRejectedExecutionException[rejected execution (queue capacity 1) on org.elasticsearch.transport.netty.MessageChannelHandler$RequestHandler@7ea0788]; }{[HeTJoSeLRl2ClSWPXZATfA][test][6]: EsRejectedExecutionException[rejected execution (queue capacity 1) on org.elasticsearch.search.action.SearchServiceTransportAction$23@7d94d0e7]}]

                int startPhaseIndex = nthIndexOf(unwrappedError, '[', 2) + 1; // + 1 for inclusive index
                int endPhaseIndex = unwrappedError.indexOf(']');
                String phaseMessage = unwrappedError.substring(startPhaseIndex, endPhaseIndex);
                int startMessageIndex = unwrappedError.indexOf(',') + 2; // +2 for space and ',' inclusive
                int endMessageIndex = unwrappedError.indexOf(';');
                String message = unwrappedError.substring(startMessageIndex, endMessageIndex);

                ShardSearchFailure[] shardSearchFailures = buildShardSearchFailures(unwrappedError.substring(endMessageIndex, unwrappedError.length() - 2));  // -2 to remove trailing "]}"
                throw new SearchPhaseExecutionException(phaseMessage, message, shardSearchFailures);
            } else if (unwrappedError.startsWith(ActionRequestValidationException.class.getSimpleName())) {
                // ActionRequestValidationException[Validation Failed: 1: Alias action [add] requires an [alias] to be set;2: Alias action [add]: [index] may not be empty string;]
                // TODO errors like this
                // Validation Failed: {number}: {message1};{message2}
                String failures = unwrappedError.substring("ActionRequestValidationException[Validation Failed: ".length() + 1, unwrappedError.length() - 1); // remove the trailing ']'
                Iterable<String> splitted = Splitter.on(";").omitEmptyStrings().split(failures);
                ActionRequestValidationException exception = new ActionRequestValidationException();
                for (String failure : splitted) {
                    exception.addValidationError(failure.substring(failure.indexOf(":") + 2)); // remove '{index}: '
                }
                throw exception;
            } else if (unwrappedError.startsWith(IndexMissingException.class.getSimpleName())) {
                // IndexMissingException[[_all] missing]
                // Validation Failed: {number}: {message}
                int startIndexName = unwrappedError.indexOf('[') + 2;
                int endIndexName = unwrappedError.indexOf(']');
                throw new IndexMissingException(new Index(unwrappedError.substring(startIndexName, endIndexName)));
            } else if (unwrappedError.startsWith(IndexAlreadyExistsException.class.getSimpleName())) {
                // IndexAlreadyExistsException[[test] already exists]
                int startIndexName = unwrappedError.indexOf('[') + 2;
                int endIndexName = unwrappedError.indexOf(']');
                throw new IndexAlreadyExistsException(new Index(unwrappedError.substring(startIndexName, endIndexName)));
            } else if (unwrappedError.startsWith(VersionConflictEngineException.class.getSimpleName())) {
                // VersionConflictEngineException[[test][1] [type][1]: version conflict, current [-1], provided [17]]
                int startIndexName = nthIndexOf(unwrappedError, '[', 2) + 1; // have two '[' at the beginning
                int endIndexName = nthIndexOf(unwrappedError, ']', 1);

                int startShard = nthIndexOf(unwrappedError, '[', 3) + 1;
                int endShard = nthIndexOf(unwrappedError, ']', 2);

                int startType = nthIndexOf(unwrappedError, '[', 4) + 1;
                int endType = nthIndexOf(unwrappedError, ']', 3);

                int startId = nthIndexOf(unwrappedError, '[', 5) + 1;
                int endId = nthIndexOf(unwrappedError, ']', 4);

                int startCurrentVersion = nthIndexOf(unwrappedError, '[', 6) + 1;
                int endCurrentVersion = nthIndexOf(unwrappedError, ']', 5);

                int startProvidedVersion = nthIndexOf(unwrappedError, '[', 7) + 1;
                int endProvidedVersion = nthIndexOf(unwrappedError, ']', 6);

                int shardIdAsInt = Integer.parseInt(unwrappedError.substring(startShard, endShard));
                ShardId shardId = new ShardId(unwrappedError.substring(startIndexName, endIndexName), shardIdAsInt);
                String type = unwrappedError.substring(startType, endType);
                String id = unwrappedError.substring(startId, endId);
                long currentVersion = Long.parseLong(unwrappedError.substring(startCurrentVersion, endCurrentVersion));
                long providedVersion = Long.parseLong(unwrappedError.substring(startProvidedVersion, endProvidedVersion));

                throw new VersionConflictEngineException(shardId, type, id, currentVersion, providedVersion);
            } else if (unwrappedError.startsWith(DocumentAlreadyExistsException.class.getSimpleName())) {
                // DocumentAlreadyExistsException[[test][0] [type][1]: document already exists]
                int startIndexName = nthIndexOf(unwrappedError, '[', 2) + 1; // have two '[' at the beginning
                int endIndexName = nthIndexOf(unwrappedError, ']', 1);

                int startShard = nthIndexOf(unwrappedError, '[', 3) + 1;
                int endShard = nthIndexOf(unwrappedError, ']', 2);

                int startType = nthIndexOf(unwrappedError, '[', 4) + 1;
                int endType = nthIndexOf(unwrappedError, ']', 3);

                int startId = nthIndexOf(unwrappedError, '[', 5) + 1;
                int endId = nthIndexOf(unwrappedError, ']', 4);

                int shardIdAsInt = Integer.parseInt(unwrappedError.substring(startShard, endShard));
                ShardId shardId = new ShardId(unwrappedError.substring(startIndexName, endIndexName), shardIdAsInt);
                String type = unwrappedError.substring(startType, endType);
                String id = unwrappedError.substring(startId, endId);

                throw new DocumentAlreadyExistsException(shardId, type, id);
            } else if (unwrappedError.startsWith(ElasticsearchIllegalArgumentException.class.getSimpleName())) {
                // ElasticsearchIllegalArgumentException[Can't specify parent if no parent field has been configured]
                String message = unwrappedError.substring("ElasticsearchIllegalArgumentException".length() + 1, unwrappedError.length() - 1);
                throw new ElasticsearchIllegalArgumentException(message);
            } else if (unwrappedError.startsWith(UnavailableShardsException.class.getSimpleName())) {
                // UnavailableShardsException[[test][2] [2] shardIt, [0] active : Timeout waiting for [1s], request: index {[test][type1][1], source[{ type1 : { "id" : "1", "name" : "test" } }]}]
                int startIndexName = nthIndexOf(unwrappedError, '[', 2) + 1; // have two '[' at the beginning
                int endIndexName = nthIndexOf(unwrappedError, ']', 1);

                int startShard = nthIndexOf(unwrappedError, '[', 3) + 1;
                int endShard = nthIndexOf(unwrappedError, ']', 2);

                int shardIdAsInt = Integer.parseInt(unwrappedError.substring(startShard, endShard));
                ShardId shardId = new ShardId(unwrappedError.substring(startIndexName, endIndexName), shardIdAsInt);

                // TODO more format message possible ... org.elasticsearch.cluster.ClusterStateObserver.Listener.onTimeout()
//                if (shardIt == null) {
//                    listenFailure = new UnavailableShardsException(new ShardId(request.index(), -1), "Timeout waiting for [" + timeout + "], request: " + request.toString());
//                } else {
//                    listenFailure = new UnavailableShardsException(shardIt.shardId(), "[" + shardIt.size() + "] shardIt, [" + shardIt.sizeActive() + "] active : Timeout waiting for [" + timeout + "], request: " + request.toString());
//                }
                int startMessage = unwrappedError.indexOf("Timeout");
                throw new UnavailableShardsException(shardId, unwrappedError.substring(startMessage));
            } else if (unwrappedError.startsWith(ElasticsearchParseException.class.getSimpleName())) {
                throw new ElasticsearchParseException(unwrappedError.substring("ElasticsearchParseException".length() + 1, error.length() - 1));
            } else if (error.startsWith(ReduceSearchPhaseException.class.getSimpleName())) {
                // this exception is wrapping a cause, but weare also interrested in the wrapper, so also use "error"
                // ReduceSearchPhaseException[Failed to execute phase [query], [reduce] ]; nested: ElasticsearchIllegalStateException[Can't merge suggest result, this might be caused by suggest calls across multiple indices with different analysis chains. Suggest entries have different sizes actual [1] expected [2]];
                int startNested = error.indexOf("nested") + "nested: ".length();
                unwrappedError = error.substring(startNested);
                int startPhaseName = nthIndexOf(error, '[', 1) + 1;
                int endPhaseName = nthIndexOf(error, ']', 1);
                String phaseName = error.substring(startPhaseName, endPhaseName);
                if (unwrappedError.startsWith(ElasticsearchIllegalStateException.class.getSimpleName())) {
                    int startCause = nthIndexOf(unwrappedError, '[', 1) + 1;
                    int endCause = unwrappedError.length() - 3; // remove the trailing ']'
                    String cause = unwrappedError.substring(startCause, endCause);
                    // TODO shard failures ?
                    throw new ReduceSearchPhaseException(phaseName, "", new ElasticsearchIllegalStateException(cause), ShardSearchFailure.EMPTY_ARRAY);
                } else {
                    throw new IllegalStateException("unhandled exception type " + unwrappedError);
                }
            } else if (unwrappedError.startsWith(ClusterBlockException.class.getSimpleName())) {
                // ClusterBlockException[blocked by: [FORBIDDEN/8/index write (api)];]
                int startMessages = nthIndexOf(unwrappedError, '[', 2);
                int endMessages = nthIndexOf(unwrappedError, ']', 2);
                String messages = unwrappedError.substring(startMessages, endMessages);
                String[] splitted = messages.split(";");
                Set<ClusterBlock> clusterBlocks = new HashSet<>();
                for (String message : splitted) {
                    message = messages.substring(1, message.length() - 1); // remove heading and trailing '[' & ']'
                    String[] parts = message.split("/");
                    RestStatus status = RestStatus.valueOf(parts[0]); // status
                    Integer id = Integer.valueOf(parts[1]); // id
                    String description = parts[2];
                    // TODO don't have retryable, disableStatePersistence & levels from ClusterBlock
                    ClusterBlock clusterBlock = new ClusterBlock(id, description, false, false, status, EnumSet.noneOf(ClusterBlockLevel.class));
                    clusterBlocks.add(clusterBlock);
                }
                throw new ClusterBlockException(org.elasticsearch.common.collect.ImmutableSet.<ClusterBlock>builder().addAll(clusterBlocks).build());
            } else if (unwrappedError.startsWith(ExpressionScriptCompilationException.class.getSimpleName())) {
                throw new ExpressionScriptCompilationException(unwrappedError);
            } else if (unwrappedError.startsWith(AliasesMissingException.class.getSimpleName())) {
                // AliasesMissingException[aliases [[alias1]] missing];
                int startAliases = nthIndexOf(unwrappedError, '[', 3) + 1;
                int endAliases = nthIndexOf(unwrappedError, ']', 1);
                String aliases = unwrappedError.substring(startAliases, endAliases);
                throw new AliasesMissingException(Strings.splitStringByCommaToArray(aliases));
            } else if (unwrappedError.startsWith(JsonParseException.class.getSimpleName())) {
                // JsonParseException[Unexpected character ('{' (code 123)): was expecting comma to separate ARRAY entries
                // at [Source: [B@737ccd2f; line: 1, column: 19]]
                int startLoc = unwrappedError.lastIndexOf(" at ");
                String message = unwrappedError.substring(0, startLoc + 5); // +5 to not include the "\n at "

                String loc = unwrappedError.substring(startLoc);
                int startSourceObject = " at Source".length() + 1;
                int endSourceObject = loc.indexOf(';');
                String sourceObject = loc.substring(startSourceObject, endSourceObject);

                int startLine = loc.indexOf("; line: ") + "; line: ".length();
                int endLine = nextIndexOf(loc, ',', startLine);
                String line = loc.substring(startLine, endLine);

                int startColumn = loc.indexOf(", column: ") + ", column: ".length();
                int endColumn = nextIndexOf(loc, ']', startColumn);
                String column = loc.substring(startColumn, endColumn);

                JsonLocation jsonLocation = new JsonLocation(sourceObject, -1, Integer.valueOf(line), Integer.valueOf(column));
                try {
                    throw new JsonParseException(message.substring(0, startLoc), jsonLocation);
                } catch (JsonParseException e) {
                    throw new RuntimeException("Unable to rebuild exception for: " + unwrappedError, e);
                }
            } else if (error.startsWith(MapperParsingException.class.getSimpleName())) {
                // MapperParsingException[failed to parse]; nested: ElasticsearchIllegalArgumentException[Weight must be an integer, but was [2.5]];
                try {
                    error(unwrappedError);
                } catch (Exception cause) {
                    throw new MapperParsingException(error.substring(MapperParsingException.class.getSimpleName().length(), error.indexOf("; nested:".length())));
                }
            } else if (unwrappedError.startsWith(ElasticsearchException.class.getSimpleName())) {
                // ElasticsearchException[Cannot access field token_count from transaction log. You can only get this field after refresh() has been called.]
                String message = unwrappedError.substring("ElasticsearchException[".length(), unwrappedError.length() - 1); // remove trailing ']'
                throw new ElasticsearchException(message);
            } else if (unwrappedError.startsWith(DocumentMissingException.class.getSimpleName())) {
                // DocumentMissingException[[test][2] [type][1]: document missing]
                // TODO dedup this as it is used to map other exceptions
                int startIndexName = nthIndexOf(unwrappedError, '[', 2) + 1; // have two '[' at the beginning
                int endIndexName = nthIndexOf(unwrappedError, ']', 1);

                int startShard = nthIndexOf(unwrappedError, '[', 3) + 1;
                int endShard = nthIndexOf(unwrappedError, ']', 2);

                String shardIdSubString = unwrappedError.substring(startShard, endShard);
                ShardId shardId = null;
                if (!shardIdSubString.equals("_na")) {
                    int shardIdAsInt = Integer.parseInt(shardIdSubString);
                    shardId = new ShardId(unwrappedError.substring(startIndexName, endIndexName), shardIdAsInt);
                }

                int startType = nthIndexOf(unwrappedError, '[', 4) + 1;
                int endType = nthIndexOf(unwrappedError, ']', 3);

                int startId = nthIndexOf(unwrappedError, '[', 5) + 1;
                int endId = nthIndexOf(unwrappedError, ']', 4);

                throw new DocumentMissingException(shardId, unwrappedError.substring(startType, endType), unwrappedError.substring(startId, endId));
            } else if (unwrappedError.startsWith(IndexClosedException.class.getSimpleName())) {
                int startIndexName = nthIndexOf(unwrappedError, '[', 2) + 1; // have two '[' at the beginning
                int endIndexName = nthIndexOf(unwrappedError, ']', 1);
                String index = unwrappedError.substring(startIndexName, endIndexName);
                throw new IndexClosedException(new Index(index));
            } else if (unwrappedError.startsWith(InvalidIndexNameException.class.getSimpleName())) {
                int startIndexName = nthIndexOf(unwrappedError, '[', 1) + 1;
                int endIndexName = nthIndexOf(unwrappedError, ']', 1);
                String index = unwrappedError.substring(startIndexName, endIndexName);
                String msg = unwrappedError.substring(endIndexName + 3);// '] ,' => 3 chars
                throw new InvalidIndexNameException(new Index(index), index, msg);
            } else if (unwrappedError.startsWith(RoutingMissingException.class.getSimpleName())) {
                // RoutingMissingException[routing is required for [test]/[type1]/[1]]
                int startIndexName = nthIndexOf(unwrappedError, '[', 2) + 1;
                int endIndexName = nthIndexOf(unwrappedError, ']', 1);
                String index = unwrappedError.substring(startIndexName, endIndexName);

                int startType = nthIndexOf(unwrappedError, '[', 3) + 1;
                int endType = nthIndexOf(unwrappedError, ']', 2);
                String type = unwrappedError.substring(startType, endType);

                int startId = nthIndexOf(unwrappedError, '[', 4) + 1;
                int endId = nthIndexOf(unwrappedError, ']', 3);
                String id = unwrappedError.substring(startId, endId);
                throw new RoutingMissingException(index, type, id);
            }
            // TODO which exception should i rebuild ?
            throw new ElasticsearchException(unwrappedError);
        }
    }

    private ShardSearchFailure[] buildShardSearchFailures(String error) {
        // failure format : org.elasticsearch.action.search.SearchPhaseExecutionException.buildMessage()
        String[] splitted = error.split("\\{\\[");
        ShardSearchFailure[] result = new ShardSearchFailure[splitted.length - 1];
        // skip first occurence as it is something like "; shardFailures "
        for (int i = 1; i < splitted.length; i++) {
            String failure = splitted[i];
            SearchShardTarget searchShardTarget = buildSearchShardTarget(failure);
            int startException = nthIndexOf(failure, ']', 3) + 3; // + 3 => "]" after shard id, a ":" and a space
            ShardSearchFailure shardSearchFailure = new ShardSearchFailure(failure.substring(startException), searchShardTarget, stringToRestStatus());
            result[i - 1] = shardSearchFailure;
        }
        return result;
    }

    private RestStatus stringToRestStatus() {
//        return RestStatus.valueOf(response.getStatusText().toUpperCase().replaceAll(" ", "_"));
        return null;
    }

    private static SearchShardTarget buildSearchShardTarget(String error) {
        // here we receive something like: dUXMEAWNQLOJmfuu5y98fA][test][5]: SearchParseException[[test][5]: from[-1],size[-1]: Parse Failure [Failed to parse source [{"query":{"query_string":{"query":"future:[now/D TO now+2M/d]","lowercase_expanded_terms":false}}}]]]; nested: ElasticsearchParseException[unit [D] not supported for date math [/D]]; }
        int endNodeId = nthIndexOf(error, ']', 1);
        String nodeId = error.substring(0, endNodeId);
        int startIndex = nthIndexOf(error, '[', 1) + 1;
        int endIndex = nthIndexOf(error, ']', 2);
        String index = error.substring(startIndex, endIndex);
        int startShardId = nthIndexOf(error, '[', 2) + 1;
        int endShardId = nthIndexOf(error, ']', 3);
        int shardId = Integer.parseInt(error.substring(startShardId, endShardId));

        return new SearchShardTarget(nodeId, index, shardId);

    }

    public GetIndexTemplatesResponse toGetIndexTemplatesResponse() {
        List<IndexTemplateMetaData> indexTemplateMetaDatas = new ArrayList<>();
        for (Map.Entry<String, Object> entry : entityWrapper.entrySet()) {
            Map<String, Object> templateAsMap = (Map<String, Object>) entry.getValue();

            Integer order = getAs(templateAsMap, "order", Integer.class);

            String template = getAs(templateAsMap, "template", String.class);

            Map<String, Object> settingsAsMap = getAsStringObjectMap(templateAsMap, "settings");
            Settings settings = ImmutableSettings.builder().put(settingsAsMap).build();

            Map<String, Map<String, Object>> mappingsAsMapRaw = getAsNestedStringToMapMap(templateAsMap, "mappings");

            ImmutableOpenMap.Builder<String, CompressedString> mappingsBuilder = ImmutableOpenMap.<String, CompressedString>builder();
            for (Map.Entry<String, Map<String, Object>> mapEntry : mappingsAsMapRaw.entrySet()) {
                CompressedString mappingAsString = toCompressedString(mapToString(mapEntry.getValue()));
                mappingsBuilder.put(mapEntry.getKey(), mappingAsString);
            }

            Map<String, AliasMetaData> aliasesAsMap = Maps.transformEntries(
                    getAsNestedStringToMapMap(templateAsMap, "aliases"), new Maps.EntryTransformer<String, Map<String, Object>, AliasMetaData>() {
                        @Override
                        public AliasMetaData transformEntry(String key, Map<String, Object> aliasAsMap) {
                            Map<String, Object> filterAsMap = getAsStringObjectMap(aliasAsMap, "filter");
                            CompressedString filter = toCompressedString(mapToString(filterAsMap));
                            String index_routing = (String) aliasAsMap.get("index_routing");
                            String search_routing = (String) aliasAsMap.get("search_routing");
                            return new AliasMetaData.Builder(key)
                                    .filter(filter)
                                    .indexRouting(index_routing)
                                    .searchRouting(search_routing).build();
                        }
                    }
            );
            ImmutableOpenMap<String, AliasMetaData> aliases = ImmutableOpenMap.<String, AliasMetaData>builder().putAll(aliasesAsMap).build();

            IndexWarmersMetaData warmers = null;
            Map<String, Object> warmersAsMap = getAsStringObjectMap(templateAsMap, "warmers");
            if (warmersAsMap != null) {
                try {
                    warmers = IndexWarmersMetaData.FACTORY.fromMap(warmersAsMap);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            ImmutableOpenMap.Builder<String, IndexMetaData.Custom> customsBuilder = ImmutableOpenMap.builder();
            if (warmers != null) {
                customsBuilder.fPut("warmers", warmers);
            }
            ImmutableOpenMap<String, IndexMetaData.Custom> customs = customsBuilder.build();

            IndexTemplateMetaData indexTemplateMetaData = new IndexTemplateMetaData(entry.getKey(), order, template, settings, mappingsBuilder.build(), aliases, customs);
            indexTemplateMetaDatas.add(indexTemplateMetaData);
        }

        return GetIndexTemplatesResponseAccessor.create(indexTemplateMetaDatas);
    }

    private String mapToString(Map map) {
//        // TODO use streaming parser instead?
//        ObjectMapper objectMapper = new ObjectMapper();
//        try {
//            return objectMapper.writeValueAsString(map);
//        } catch (IOException e) {
//            throw new RuntimeException(e);
//        }
        throw new RuntimeException();
    }

    private CompressedString toCompressedString(String string) {
        try {
            return new CompressedString(string);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public ClusterStateResponse toClusterStateResponse() {
        String clusterNameAsString = getAs(entityWrapper, "cluster_name", String.class);
        ClusterName clusterName = new ClusterName(clusterNameAsString);

        Map<String, Object> metadataAsMap = getAsStringObjectMap(entityWrapper, "metadata");
        String metadataAsString = mapToString(metadataAsMap);
        MetaData metaData = metadataFromString(metadataAsString);

        Map<String, Map<String, Object>> blocksAsMap = getAsNestedStringToMapMap(entityWrapper, "blocks");
        Map<String, Map<String, Object>> globalAsMap = null;
        Map<String, Map<String, Object>> blockIndicesAsMap = null;
        if (blocksAsMap != null) {
            globalAsMap = getAsNestedStringToMapMap(blocksAsMap, "global");
            blockIndicesAsMap = getAsNestedStringToMapMap(blocksAsMap, "indices");
        }
        ClusterBlocks.Builder blocksBuilder = ClusterBlocks.builder();

        if (globalAsMap != null) {
            for (Map.Entry<String, Map<String, Object>> blockEntry : globalAsMap.entrySet()) {
                ClusterBlock block = parseClusterBlock(blockEntry);
                blocksBuilder.addGlobalBlock(block);
            }
        }

        if (blockIndicesAsMap != null) {
            for (Map.Entry<String, Map<String, Object>> indexEntry : blockIndicesAsMap.entrySet()) {
                // TODO find a way to test this stuff, it seems to be at the wrong level of nesting
                // TODO dirty cast
                Map<String, Map<String, Object>> value = (Map) indexEntry.getValue();
                for (Map.Entry<String, Map<String, Object>> entry : value.entrySet()) {
                    ClusterBlock block = parseClusterBlock(entry);
                    blocksBuilder.addIndexBlock(indexEntry.getKey(), block);
                }
            }
        }

        Map<String, Map<String, Object>> routingTableAsMap = getAsNestedStringToMapMap(entityWrapper, "routing_table");
        RoutingTable.Builder routingTableBuilder = RoutingTable.builder();
        if (routingTableAsMap != null) {
            Map<String, Map<String, Object>> indices = getAsNestedStringToMapMap(routingTableAsMap, "indices");
            if (indices != null) {
                for (Map.Entry<String, Map<String, Object>> entry : indices.entrySet()) {
                    String index = entry.getKey();
                    IndexRoutingTable.Builder builder = IndexRoutingTable.builder(index);
                    Map<String, Object> value = entry.getValue();
                    Map<String, Map<String, Object>> shards = getAsNestedStringToMapMap(value, "shards");
                    for (Map.Entry<String, Map<String, Object>> shardEntry : shards.entrySet()) {
                        int shardId = Integer.parseInt(shardEntry.getKey());
                        List<Map<String, Object>> shardsList = (List<Map<String, Object>>) shardEntry.getValue();
                        for (Map<String, Object> shardAsMap : shardsList) {
                            // TODO primary allocated post api ?
                            IndexShardRoutingTable.Builder shardBuilder = new IndexShardRoutingTable.Builder(new ShardId(index, shardId), true)
                                    // TODO version not returned
                                    .addShard(new ImmutableShardRouting(getAsString(shardAsMap, "index"), getAsNumber(shardAsMap, "shard").intValue(),
                                            getAsString(shardAsMap, "node"), getAsString(shardAsMap, "relocating_node"), getAs(shardAsMap, "primary", Boolean.class),
                                            ShardRoutingState.valueOf(getAsString(shardAsMap, "state")), -1));
                            builder.addIndexShard(shardBuilder.build());
                        }
                    }
                    // TODO version is not returned
                    routingTableBuilder.add(builder.build());
                }
            }
        }

        ImmutableOpenMap.Builder<String, DiscoveryNode> nodesMapBuilder = ImmutableOpenMap.builder();
        ImmutableOpenMap.Builder<String, DiscoveryNode> dataNodesMapBuilder = ImmutableOpenMap.builder();
        ImmutableOpenMap.Builder<String, DiscoveryNode> masterNodesMapBuilder = ImmutableOpenMap.builder();
        Map<String, Map<String, Object>> nodesAsMap = getAsNestedStringToMapMap(entityWrapper, "nodes");
        if (nodesAsMap != null) {
            for (Map.Entry<String, Map<String, Object>> entry : nodesAsMap.entrySet()) {
                String nodeId = entry.getKey();
                Map<String, Object> value = entry.getValue();
                String name = getAsString(value, "name");
                Map<String, String> attributes = getAs(value, "attributes", Map.class);
                String transportAddressAsString = getAsString(value, "transport_address");
                TransportAddress transportAddress = parseTransportAddress(transportAddressAsString);
                // TODO version
                DiscoveryNode node = new DiscoveryNode(name, nodeId, transportAddress, attributes, Version.CURRENT);
                nodesMapBuilder.put(nodeId, node);
            }
        }
        // TODO no filtering on node type (data/master) ...
        DiscoveryNodes.Builder discoveryNodesBuilder = DiscoveryNodes.builder();
        for (ObjectObjectCursor<String, DiscoveryNode> entry : nodesMapBuilder.build()) {
            discoveryNodesBuilder.put(entry.value);
        }
        String masterNode = getAsString(entityWrapper, "master_node");
        discoveryNodesBuilder.masterNodeId(masterNode);
        // TODO don't know the node id
//        discoveryNodesBuilder.localNodeId(masterNode);

        // TODO no impl of customs for now it seems

        // TODO
        long version = getAs(entityWrapper, "version", Number.class).longValue();
        ClusterState clusterState = new ClusterState(clusterName, version, metaData, routingTableBuilder.build(), discoveryNodesBuilder.build(), blocksBuilder.build(), ImmutableOpenMap.<String, ClusterState.Custom>builder().build());

        return ClusterStateResponseAccessor.create(clusterName, clusterState);
    }

    public static TransportAddress parseTransportAddress(String transportAddressAsString) {
        TransportAddress transportAddress;
        if (transportAddressAsString.startsWith("inet")) {
            String host = transportAddressAsString.substring(5, transportAddressAsString.length() - 2);
            String[] parts = transportAddressAsString.split(":");
            String portPart = parts[parts.length - 1]; // this contains the trailing "]"
            String portAsString = portPart.substring(0, portPart.length() - 1); // ugly !
            int port = Integer.parseInt(portAsString);
            new InetSocketTransportAddress(host, port);
            transportAddress = new InetSocketTransportAddress(host, port);
        } else if (transportAddressAsString.startsWith("local")) {
            String id = transportAddressAsString.substring("local".length());
            transportAddress = new LocalTransportAddress(id);
        } else {
            throw new IllegalArgumentException("unknown transport type " + transportAddressAsString);
        }
        return transportAddress;
    }

    public ClusterHealthResponse toClusterHealthResponse() {
        // TODO maybe use package-local class org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse.Fields here and for other ToXContent classes ?
        String clusterName = getAsString(entityWrapper, "cluster_name");

        String statusAsString = getAsString(entityWrapper, "status");
        ClusterHealthStatus status = ClusterHealthStatus.valueOf(statusAsString.toUpperCase());

        Boolean timedOut = getAs(entityWrapper, "timed_out", Boolean.class);
        int numberOfNodes = getAs(entityWrapper, "number_of_nodes", Number.class).intValue();
        int numberOfDataNodes = getAs(entityWrapper, "number_of_data_nodes", Number.class).intValue();
        int activePrimaryShards = getAs(entityWrapper, "active_primary_shards", Number.class).intValue();
        int activeShards = getAs(entityWrapper, "active_shards", Number.class).intValue();
        int relocatingShards = getAs(entityWrapper, "relocating_shards", Number.class).intValue();
        int initializingShards = getAs(entityWrapper, "initializing_shards", Number.class).intValue();
        int unassignedShards = getAs(entityWrapper, "unassigned_shards", Number.class).intValue();

        List<String> validationFailures = firstNonNull(getAs(entityWrapper, "validation_failures", List.class), ImmutableList.of());

        String[] askedIndicesFromRequest = ((ClusterHealthRequest) request).indices();
        Set<String> askedIndices = askedIndicesFromRequest != null ? Sets.newHashSet(askedIndicesFromRequest) : new HashSet<String>();

        Map<String, Map<String, Object>> indicesFromJson = getAsNestedStringToMapMap(entityWrapper, "indices");
        Map<String, ClusterIndexHealth> indices = Maps.newHashMap();
        if (indicesFromJson != null) {
            askedIndices.removeAll(indicesFromJson.keySet());
            for (Map.Entry<String, Map<String, Object>> indexEntry : indicesFromJson.entrySet()) {
                String indexName = indexEntry.getKey();
                Map<String, Object> indexHealth = indexEntry.getValue();
                ClusterIndexHealth clusterIndexHealth = buildIndexStatus(indexHealth, indexName);
                indices.put(indexName, clusterIndexHealth);
                askedIndices.remove(indexName);
            }
            if (askedIndices.size() != 0) {
                // if some asked indices does not exists, let status be RED
                status = ClusterHealthStatus.RED;
            }
        }

        return ClusterHealthResponseAccessor.create(clusterName, status, timedOut, numberOfNodes, numberOfDataNodes, activePrimaryShards, activeShards, relocatingShards, initializingShards, unassignedShards, validationFailures, indices);
    }

    private ClusterIndexHealth buildIndexStatus(Map<String, Object> shardEntryAsMap, String indexName) {
        String statusAsString = getAsString(shardEntryAsMap, "status");
        ClusterHealthStatus status = ClusterHealthStatus.valueOf(statusAsString.toUpperCase());
        int numberOfShards = getAs(shardEntryAsMap, "number_of_shards", Number.class).intValue();
        int numberOfReplicas = getAs(shardEntryAsMap, "number_of_replicas", Number.class).intValue();
        int activePrimaryShards = getAs(shardEntryAsMap, "active_primary_shards", Number.class).intValue();
        int activeShards = getAs(shardEntryAsMap, "active_shards", Number.class).intValue();
        int relocatingShards = getAs(shardEntryAsMap, "relocating_shards", Number.class).intValue();
        int initializingShards = getAs(shardEntryAsMap, "initializing_shards", Number.class).intValue();
        int unassignedShards = getAs(shardEntryAsMap, "unassigned_shards", Number.class).intValue();
        List<String> validationFailures = firstNonNull(getAs(shardEntryAsMap, "validation_failures", List.class), ImmutableList.of());

        Map<String, Map<String, Object>> shards = getAsNestedStringToMapMap(shardEntryAsMap, "shards");
        List<ClusterShardHealth> shardsHealth;
        if (shards != null) {
            shardsHealth = buildShardStatus(shards);
        } else {
            shardsHealth = ImmutableList.of();
        }

        // TODO can not rely on the constructor because it has a lot of logic in it and does not depends on the data I have, so let's hack it
        // in a byte array and use its unmarshalling
        ByteArrayDataOutput byteArrayDataOutput = ByteStreams.newDataOutput();
        DataOutputStreamOutput out = new DataOutputStreamOutput(byteArrayDataOutput);
        // copied from org.elasticsearch.action.admin.cluster.health.ClusterIndexHealth.writeTo()
        try {
            out.writeString(indexName);
            out.writeVInt(numberOfShards);
            out.writeVInt(numberOfReplicas);
            out.writeVInt(activePrimaryShards);
            out.writeVInt(activeShards);
            out.writeVInt(relocatingShards);
            out.writeVInt(initializingShards);
            out.writeVInt(unassignedShards);
            out.writeByte(status.value());

            out.writeVInt(shardsHealth.size());
            for (ClusterShardHealth shardHealth : shardsHealth) {
                shardHealth.writeTo(out);
            }

            out.writeVInt(validationFailures.size());
            for (String failure : validationFailures) {
                out.writeString(failure);
            }

            StreamInput in = new InputStreamStreamInput(new ByteArrayInputStream(byteArrayDataOutput.toByteArray()));

            ClusterIndexHealth clusterIndexHealth = ClusterIndexHealth.readClusterIndexHealth(in);
            return clusterIndexHealth;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private List<ClusterShardHealth> buildShardStatus(Map<String, Map<String, Object>> shards) {
        List<ClusterShardHealth> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> shardEntry : shards.entrySet()) {
            int shardId = Integer.valueOf(shardEntry.getKey());
            Map<String, Object> shardAsMap = shardEntry.getValue();
            String status = getAsString(shardAsMap, "status");
            boolean primaryActive = getAs(shardAsMap, "primary_active", Boolean.class);
            int activeShards = getAs(shardAsMap, "active_shards", Number.class).intValue();
            int relocatingShards = getAs(shardAsMap, "relocating_shards", Number.class).intValue();
            int initializingShards = getAs(shardAsMap, "initializing_shards", Number.class).intValue();
            int unassignedShards = getAs(shardAsMap, "unassigned_shards", Number.class).intValue();
            ClusterShardHealth shard = ClusterShardHealthAccessor.create(shardId, status, primaryActive, activeShards, relocatingShards, initializingShards, unassignedShards);
            result.add(shard);
        }
        return result;
    }

    private ClusterBlock parseClusterBlock(Map.Entry<String, Map<String, Object>> blockEntry) {
        int id = Integer.parseInt(blockEntry.getKey());
        Map<String, Object> value = blockEntry.getValue();
        Boolean disableStatePersistence = value.containsKey("disable_state_persistence") ? (Boolean) value.get("disable_state_persistence") : false;
        List<String> levelsAsStrings = (List<String>) value.get("levels");
        List<ClusterBlockLevel> levelsAsList = new ArrayList<>();
        for (String level : levelsAsStrings) {
            levelsAsList.add(ClusterBlockLevel.valueOf(level.toUpperCase()));
        }
        EnumSet<ClusterBlockLevel> levels = EnumSet.copyOf(levelsAsList);
        // TODO RestStatus ?
        return new ClusterBlock(id, (String) value.get("description"), (Boolean) value.get("retryable"), disableStatePersistence, RestStatus.OK, levels);
    }

    private MetaData metadataFromString(String metadataAsString) {
        // TODO this is not ok, it returns an almost empty Metadata
        MetaData metaData;
        // add the required root object ...
        metadataAsString = "{\"meta-data\":" + metadataAsString + "}";
        try {
            JsonXContentParser xContentParser = new JsonXContentParser(new JsonFactory().createParser(metadataAsString));
            metaData = MetaData.Builder.fromXContent(xContentParser);
        } catch (IOException e) {
            throw new RuntimeException();
        }
        return metaData;
    }

    /**
     * Returns the index of the nth occurrence of given char or -1
     * Not 0-indexed !
     */
    public static int nthIndexOf(String string, char needle, int n) {
        if (string == null || n < 1) {
            return -1;
        }
        int pos = string.indexOf(needle, 0);
        while (--n > 0 && pos != -1)
            pos = string.indexOf(needle, pos + 1);
        return pos;
    }

    /**
     * Returns the index of the next occurrence of given char after startIndex or -1
     */
    public static int nextIndexOf(String string, char needle, int startIndex) {
        if (string == null || startIndex < 1) {
            return -1;
        }
        for (int i = startIndex + 1; i < string.length(); i++) {
            if (string.charAt(i) == needle) {
                return i;
            }
        }
        return -1;
    }

    public GetMappingsResponse toGetMappingsResponse() {
        // TODO factorize with get template
        ImmutableOpenMap.Builder<String, ImmutableOpenMap<String, MappingMetaData>> builder = ImmutableOpenMap.builder();
        for (String index : entityWrapper.keySet()) {
            Map<String, Map<String, Object>> indexMapping = getAsNestedStringToMapMap(entityWrapper, index);
            Map<String, Map<String, Object>> mappingsAsMapRaw = getAsNestedStringToMapMap(indexMapping, "mappings");

            ImmutableOpenMap.Builder<String, MappingMetaData> mappingsBuilder = ImmutableOpenMap.builder();
            for (Map.Entry<String, Map<String, Object>> mapEntry : mappingsAsMapRaw.entrySet()) {
                CompressedString mappingAsString = toCompressedString("{\"" + mapEntry.getKey() + "\":" + mapToString(mapEntry.getValue()) + "}");
                try {
                    mappingsBuilder.put(mapEntry.getKey(), new MappingMetaData(mappingAsString));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            builder.put(index, mappingsBuilder.build());
        }
        return GetMappingsResponseAccessor.create(builder.build());
    }

    public GetSettingsResponse toGetSettingsResponse() {
        // TODO factorize with get template
        ImmutableOpenMap.Builder<String, Settings> builder = ImmutableOpenMap.builder();
        for (String index : entityWrapper.keySet()) {
            Map<String, Map<String, Object>> indexSettings = getAsNestedStringToMapMap(entityWrapper, index);
            Map<String, Map<String, Object>> settingsAsMap = getAsNestedStringToMapMap(indexSettings, "settings");
            Map<String, String> flattenedSettings = flattenSettings(settingsAsMap);
            Settings settings = ImmutableSettings.builder().put(flattenedSettings).build();
            builder.put(index, settings);
        }
        return new GetSettingsResponse(builder.build());
    }

    /**
     * We get settings as nested objects whereas the expected result is to have dot separated flat path
     */
    private Map<String, String> flattenSettings(Map<String, ?> settingsAsMap) {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, ?> entry : settingsAsMap.entrySet()) {
            if (entry.getValue() instanceof Map) {
                ArrayList<String> prefix = new ArrayList<>();
                prefix.add(entry.getKey());
                flattenSettings((Map<String, ?>) entry.getValue(), prefix, result);
            } else {
                result.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        return result;
    }

    private void flattenSettings(Map<String, ?> settingsAsMap, List<String> prefix, Map<String, String> output) {
        for (Map.Entry<String, ?> entry : settingsAsMap.entrySet()) {
            // copy to avoid sharing it
            List<String> copy = new ArrayList<>(prefix);
            copy.add(entry.getKey());
            if (entry.getValue() instanceof Map) {
                flattenSettings((Map<String, ?>) entry.getValue(), copy, output);
            } else {
                output.put(settingsJoiner.join(copy), String.valueOf(entry.getValue()));
            }
        }
    }

    public GetAliasesResponse toGetAliasesResponse() {
        if (entityWrapper.isEmpty()) {
            return new GetAliasesResponse(ImmutableOpenMap.<String, List<AliasMetaData>>of());
        }
        ImmutableOpenMap.Builder<String, List<AliasMetaData>> aliases = ImmutableOpenMap.builder();
        for (Map.Entry<String, Object> entry : entityWrapper.entrySet()) {
            String index = entry.getKey();
            Map<String, String> aliasInfos = getAs((Map) entry.getValue(), "aliases", Map.class);
            String alias = aliasInfos.get("alias");
            String filter = aliasInfos.get("filter");
            CompressedString filterAsCompressedString = null;
            if (filter != null) {
                filterAsCompressedString = toCompressedString(filter);
            }
            String indexRouting = aliasInfos.get("index_routing");
            String searchRouting = aliasInfos.get("search_routing");
            AliasMetaData aliasMetaData = AliasMetaData.builder(alias).filter(filterAsCompressedString).indexRouting(indexRouting).searchRouting(searchRouting).build();
            List<AliasMetaData> list = aliases.get(index);
            if (list == null) {
                list = new ArrayList<>();
                aliases.put(index, list);
            }
            list.add(aliasMetaData);
        }
        return new GetAliasesResponse(aliases.build());
    }

    public NodesHotThreadsResponse toNodesHotThreadsResponse() {
        // TODO
        return new NodesHotThreadsResponse(null, null);
    }

    public ClusterStatsResponse toClusterStatsResponse() {
        long timestamp = getAsNumber(entityWrapper, "timestamp").longValue();
        String clusterName = getAsString(entityWrapper, "cluster_name");
        String statusAsString = getAsString(entityWrapper, "status");
        ClusterHealthStatus status = statusAsString != null ? ClusterHealthStatus.valueOf(statusAsString.toUpperCase()) : null;
        String uuid = getAsString(entityWrapper, "uuid"); // may be absent from response

        ByteArrayDataOutput byteArrayDataOutput = ByteStreams.newDataOutput();
        DataOutputStreamOutput out = new DataOutputStreamOutput(byteArrayDataOutput);
        // copied from org.elasticsearch.action.admin.cluster.health.ClusterIndexHealth.writeTo()
        try {
            out.writeBoolean(false); // TransportResponse.readFrom()
            out.writeString(clusterName); // NodesOperationResponse.readFrom()
            out.writeVLong(timestamp);
            if (status != null) {
                out.writeBoolean(true);
                out.writeByte(status.value());
            } else {
                out.writeBoolean(false);
            }
            // TODO there is a 'output_uuid' param in ClusterStatsResponse.toXContent() but it doesn't seem to be set
//            out.writeString(uuid);
            out.writeString("");


            // node stats
            Map<String, Map<String, Object>> nodes = getAsNestedStringToMapMap(entityWrapper, "nodes");
            {
                Map<String, Object> count = getAsStringObjectMap(nodes, "count");
                out.writeVInt(getAsNumber(count, "total").intValue());
                out.writeVInt(getAsNumber(count, "master_only").intValue());
                out.writeVInt(getAsNumber(count, "data_only").intValue());
                out.writeVInt(getAsNumber(count, "master_data").intValue());
                out.writeVInt(getAsNumber(count, "client").intValue());
                Collection<String> versions = firstNonNull(getAs((Map) nodes, "versions", Collection.class), Collections.emptyList());
                out.writeVInt(versions.size());
                for (String v : versions) {
                    if (v.endsWith("-SNAPSHOT")) { // to be on dev version
                        Version.writeVersion(Version.fromString(v.substring(0, v.length() - "-SNAPSHOT".length())), out);
                    } else {
                        Version.writeVersion(Version.fromString(v), out);
                    }
                }
            }
            {
                // os={available_processors=8, mem={total_in_bytes=0}, cpu=[]},
                Map<String, Object> os = getAsStringObjectMap(nodes, "os");
                // is seems that it could be 0 or 1 entry in the list
                out.writeVInt(getAsNumber(os, "available_processors").intValue());
                Map<String, Object> mem = getAsStringObjectMap(os, "mem");
                out.writeLong(getAsNumber(mem, "total_in_bytes").longValue());
//            List cpus = getAs(os, "cpu", List.class);
                // TODO cpu field
                out.writeVInt(0);
            }
            {
                // process={cpu={percent=0}, open_file_descriptors={min=380, max=380, avg=380}},
                Map<String, Object> process = getAsStringObjectMap(nodes, "process");
                // TODO count is not returned but is > 0 if the data about fd are present. Maybe use total from the  "node count" field and use it to multiply the avg to get total fd ?
                Map<String, Object> openFileDescriptors = getAsStringObjectMap(process, "open_file_descriptors");
                Map<String, Object> cpu = getAsStringObjectMap(process, "cpu");
                if (openFileDescriptors != null) {
                    out.writeVInt(1); // count
                    out.writeVInt(getAsNumber(cpu, "percent").intValue());
                    out.writeVLong(getAsNumber(openFileDescriptors, "avg").longValue());
                    out.writeLong(getAsNumber(openFileDescriptors, "min").longValue());
                    out.writeLong(getAsNumber(openFileDescriptors, "max").longValue());
                } else {
                    out.writeVInt(0); // count
                    out.writeVInt(0); // cpuPercent
                    out.writeVLong(0); // total
                    out.writeLong(0); // min
                    out.writeLong(0); // max
                }
            }
            {
                // jvm={max_uptime_in_millis=6833, versions=[{version=1.7.0_55, vm_name=OpenJDK 64-Bit Server VM, vm_version=24.51-b03, vm_vendor=Oracle Corporation, count=1}],
                // mem={heap_used_in_bytes=119600200, heap_max_in_bytes=1908932608}, threads=107},
                Map<String, Object> jvm = getAsStringObjectMap(nodes, "jvm");
                List<Map<String, Object>> jvmVersions = getAsListOfStringObjectMap(jvm, "versions");
                out.writeVInt(jvmVersions.size());
                for (Map<String, Object> jvmVersion : jvmVersions) {
                    out.writeString(getAsString(jvmVersion, "version"));
                    out.writeString(getAsString(jvmVersion, "vm_name"));
                    out.writeString(getAsString(jvmVersion, "vm_version"));
                    out.writeString(getAsString(jvmVersion, "vm_vendor"));
                    out.writeVInt(getAsNumber(jvmVersion, "count").intValue());
                }
                out.writeVLong(getAsNumber(jvm, "threads").longValue());
                out.writeVLong(getAsNumber(jvm, "max_uptime_in_millis").longValue());
                Map<String, Object> mem = getAsStringObjectMap(jvm, "mem");
                out.writeVLong(getAsNumber(mem, "heap_used_in_bytes").longValue());
                out.writeVLong(getAsNumber(mem, "heap_max_in_bytes").longValue());
            }
            {
                // fs={total_in_bytes=2147483648, free_in_bytes=2146779136, available_in_bytes=2146779136},
                Map<String, Object> fs = getAsStringObjectMap(nodes, "fs");
                if (fs.isEmpty()) {
                    out.writeOptionalString(null); // path
                    out.writeOptionalString(null); // mount
                    out.writeOptionalString(null); // dev
                    out.writeLong(-1); // total
                    out.writeLong(-1); // free
                    out.writeLong(-1); // free
                    out.writeLong(-1); // diskReads
                    out.writeLong(-1); // diskWrites
                    out.writeLong(-1); // diskReadBytes
                    out.writeLong(-1); // diskWriteBytes
                    out.writeDouble(-1); // diskQueue
                    out.writeDouble(-1); // diskServiceTime
                } else {
                    out.writeOptionalString(null); // path
                    out.writeOptionalString(null); // mount
                    out.writeOptionalString(null); // dev
                    out.writeLong(getAsNumber(fs, "total_in_bytes").longValue()); // total
                    out.writeLong(getAsNumber(fs, "free_in_bytes").longValue()); // free
                    out.writeLong(getAsNumber(fs, "available_in_bytes").longValue()); // free
                    out.writeLong(-1); // diskReads
                    out.writeLong(-1); // diskWrites
                    out.writeLong(-1); // diskReadBytes
                    out.writeLong(-1); // diskWriteBytes
                    out.writeDouble(-1); // diskQueue
                    out.writeDouble(-1); // diskServiceTime
                }
            }
            {
                // TODO install some plugins :)
                // plugins=[]}
                out.writeVInt(0);
            }

            // indices
            Map<String, Object> indices = getAsStringObjectMap(entityWrapper, "indices");
            // {count=2,
            int indexCount = getAsNumber(indices, "count").intValue();
            out.writeVInt(indexCount);
            {
                //  shards
                Map<String, Object> shards = getAsStringObjectMap(indices, "shards");
                if (shards != null) {
                    // shards={total=11, primaries=11, replication=0.0, index={shards={min=5, max=6, avg=5.5}, primaries={min=5, max=6, avg=5.5}, replication={min=0.0, max=0.0, avg=0.0}}},
                    {
                        out.writeVInt(indexCount); // TODO seems to be a correct value
                        Number total = getAsNumber(shards, "total");
                        Number primaries = getAsNumber(shards, "primaries");
                        out.writeVInt(total != null ? total.intValue() : 0);
                        out.writeVInt(primaries != null ? primaries.intValue() : 0);
                    }
                    Map<String, Object> index = getAsStringObjectMap(shards, "index");
                    if (index != null) {
                        Map<String, Object> indicesShards = getAsStringObjectMap(index, "shards");
                        out.writeVInt(getAsNumber(indicesShards, "min").intValue());
                        out.writeVInt(getAsNumber(indicesShards, "max").intValue());

                        Map<String, Object> primaries = getAsStringObjectMap(index, "primaries");
                        out.writeVInt(getAsNumber(primaries, "min").intValue());
                        out.writeVInt(getAsNumber(primaries, "max").intValue());

                        Map<String, Object> replication = getAsStringObjectMap(index, "replication");
                        out.writeDouble(getAsNumber(replication, "min").intValue());
                        out.writeDouble(getAsNumber(replication, "avg").doubleValue() * indexCount); // TODO don't have total, only avg, maybe use count ?
                        out.writeDouble(getAsNumber(replication, "max").intValue());
                    } else {
                        out.writeVInt(-1);
                        out.writeVInt(-1);
                        out.writeVInt(-1);
                        out.writeVInt(-1);
                        out.writeDouble(-1);
                        out.writeDouble(-1);
                        out.writeDouble(-1);
                    }
                } else {
                    out.writeVInt(0);
                    out.writeVInt(0);
                    out.writeVInt(0);
                    out.writeVInt(-1);
                    out.writeVInt(-1);
                    out.writeVInt(-1);
                    out.writeVInt(-1);
                    out.writeDouble(-1);
                    out.writeDouble(-1);
                    out.writeDouble(-1);
                }

                // docs={count=14, deleted=11},
                Map<String, Object> docs = getAsStringObjectMap(indices, "docs");
                out.writeVLong(getAsNumber(docs, "count").longValue());
                out.writeVLong(getAsNumber(docs, "deleted").longValue());

                // store={size_in_bytes=28768, throttle_time_in_millis=0},
                Map<String, Object> store = getAsStringObjectMap(indices, "store");
                out.writeVLong(getAsNumber(store, "size_in_bytes").longValue());
                out.writeVLong(TimeUnit.NANOSECONDS.convert(getAsNumber(store, "throttle_time_in_millis").longValue(), TimeUnit.MILLISECONDS));

                // fielddata={memory_size_in_bytes=86269, evictions=0},
                Map<String, Object> fielddata = getAsStringObjectMap(indices, "fielddata");
                out.writeVLong(getAsNumber(fielddata, "memory_size_in_bytes").longValue());
                out.writeVLong(getAsNumber(fielddata, "evictions").longValue());
                // TODO fields ? org.elasticsearch.index.fielddata.FieldDataStats.writeTo()
                out.writeBoolean(false);

                // filter_cache={memory_size_in_bytes=0, evictions=0},
                Map<String, Object> filterCache = getAsStringObjectMap(indices, "filter_cache");
                out.writeVLong(getAsNumber(filterCache, "memory_size_in_bytes").longValue());
                out.writeVLong(getAsNumber(filterCache, "evictions").longValue());

                // id_cache={memory_size_in_bytes=0},
                Map<String, Object> idCache = getAsStringObjectMap(indices, "id_cache");
                out.writeVLong(getAsNumber(idCache, "memory_size_in_bytes").longValue());

                // completion={size_in_bytes=0},
                Map<String, Object> completion = getAsStringObjectMap(indices, "completion");
                out.writeVLong(getAsNumber(completion, "size_in_bytes").longValue());
                // TODO fields ? org.elasticsearch.search.suggest.completion.CompletionStats.writeTo()
                out.writeBoolean(false);

                // segments={count=11, memory_in_bytes=66152, index_writer_memory_in_bytes=0, version_map_memory_in_bytes=0},
                // TODO where do index_writer_memory_in_bytes & version_map_memory_in_bytes come from ? (1.3 maybe ?)
                Map<String, Object> segments = getAsStringObjectMap(indices, "segments");
                out.writeVLong(getAsNumber(segments, "count").longValue());
                out.writeLong(getAsNumber(segments, "memory_in_bytes").longValue());
                // from 1.3.0 and more
                out.writeLong(getAsNumber(segments, "index_writer_memory_in_bytes").longValue());
                out.writeLong(getAsNumber(segments, "version_map_memory_in_bytes").longValue());

                // percolate={total=0, time_in_millis=0, current=0, memory_size_in_bytes=-1, memory_size=-1b, queries=0}}
                Map<String, Object> percolate = getAsStringObjectMap(indices, "percolate");
                out.writeVLong(getAsNumber(percolate, "total").longValue());
                out.writeVLong(getAsNumber(percolate, "time_in_millis").longValue());
                out.writeVLong(getAsNumber(percolate, "current").longValue());
//                out.writeVLong(getAsNumber(percolate, "memory_size_in_bytes").longValue());
                out.writeLong(-1); // memory estimator is disabled
                out.writeVLong(getAsNumber(percolate, "queries").longValue());

            }
            StreamInput in = new InputStreamStreamInput(new ByteArrayInputStream(byteArrayDataOutput.toByteArray()));
            return ClusterStatsResponseAccessor.create(in);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public ClusterUpdateSettingsResponse toClusterUpdateSettingsResponse() {
        Map<String, Object> transientsAsMap = getAsStringObjectMap(entityWrapper, "transient");
        Map<String, Object> persistentsAsMap = getAsStringObjectMap(entityWrapper, "persistent");
        Settings transients = ImmutableSettings.builder().put(transientsAsMap).build();
        Settings persistents = ImmutableSettings.builder().put(persistentsAsMap).build();
        return ClusterUpdateSettingsResponseAccessor.create(true, transients, persistents);
    }

    public MultiGetResponse toMultiGetResponse() {
        List<Map> docs = getAs(entityWrapper, "docs", List.class);
        MultiGetItemResponse[] itemResponses = new MultiGetItemResponse[docs.size()];
        for (int i = 0; i < docs.size(); i++) {
            Map<String, Object> doc = docs.get(i);
            MultiGetResponse.Failure failure = null;
            GetResponse getResponse = null;
            if (doc.containsKey("error")) {
                String index = getAsString(doc, "_index");
                String type = getAsString(doc, "_type");
                String id = getAsString(doc, "_id");
                String error = getAsString(doc, "error");
                failure = new MultiGetResponse.Failure(index, type, id, error);
            } else {
                getResponse = GetResponseAccessor.build(toGetResult(doc, doc));
            }
            MultiGetItemResponse itemResponse = new MultiGetItemResponse(getResponse, failure);
            itemResponses[i] = itemResponse;
        }
        return new MultiGetResponse(itemResponses);
    }

}
