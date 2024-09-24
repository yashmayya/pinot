/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.query.service.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.Deadline;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.apache.calcite.runtime.PairList;
import org.apache.pinot.common.datablock.DataBlock;
import org.apache.pinot.common.proto.Plan;
import org.apache.pinot.common.proto.Worker;
import org.apache.pinot.common.response.PinotBrokerTimeSeriesResponse;
import org.apache.pinot.common.response.broker.ResultTable;
import org.apache.pinot.common.utils.DataSchema;
import org.apache.pinot.common.utils.DataSchema.ColumnDataType;
import org.apache.pinot.core.util.DataBlockExtractUtils;
import org.apache.pinot.core.util.trace.TracedThreadFactory;
import org.apache.pinot.query.mailbox.MailboxService;
import org.apache.pinot.query.planner.PlanFragment;
import org.apache.pinot.query.planner.physical.DispatchablePlanFragment;
import org.apache.pinot.query.planner.physical.DispatchableSubPlan;
import org.apache.pinot.query.planner.plannode.MailboxReceiveNode;
import org.apache.pinot.query.planner.plannode.PlanNode;
import org.apache.pinot.query.planner.serde.PlanNodeDeserializer;
import org.apache.pinot.query.planner.serde.PlanNodeSerializer;
import org.apache.pinot.query.routing.QueryPlanSerDeUtils;
import org.apache.pinot.query.routing.QueryServerInstance;
import org.apache.pinot.query.routing.StageMetadata;
import org.apache.pinot.query.routing.WorkerMetadata;
import org.apache.pinot.query.runtime.blocks.TransferableBlock;
import org.apache.pinot.query.runtime.blocks.TransferableBlockUtils;
import org.apache.pinot.query.runtime.operator.MailboxReceiveOperator;
import org.apache.pinot.query.runtime.plan.MultiStageQueryStats;
import org.apache.pinot.query.runtime.plan.OpChainExecutionContext;
import org.apache.pinot.query.service.dispatch.timeseries.AsyncQueryTimeSeriesDispatchResponse;
import org.apache.pinot.query.service.dispatch.timeseries.TimeSeriesDispatchClient;
import org.apache.pinot.spi.accounting.ThreadExecutionContext;
import org.apache.pinot.spi.trace.RequestContext;
import org.apache.pinot.spi.trace.Tracing;
import org.apache.pinot.spi.utils.CommonConstants;
import org.apache.pinot.tsdb.planner.TimeSeriesPlanConstants.WorkerRequestMetadataKeys;
import org.apache.pinot.tsdb.planner.TimeSeriesPlanConstants.WorkerResponseMetadataKeys;
import org.apache.pinot.tsdb.planner.physical.TimeSeriesDispatchablePlan;
import org.apache.pinot.tsdb.planner.physical.TimeSeriesQueryServerInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * {@code QueryDispatcher} dispatch a query to different workers.
 */
public class QueryDispatcher {
  private static final Logger LOGGER = LoggerFactory.getLogger(QueryDispatcher.class);
  private static final String PINOT_BROKER_QUERY_DISPATCHER_FORMAT = "multistage-query-dispatch-%d";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final MailboxService _mailboxService;
  private final ExecutorService _executorService;
  private final Map<String, DispatchClient> _dispatchClientMap = new ConcurrentHashMap<>();
  private final Map<String, TimeSeriesDispatchClient> _timeSeriesDispatchClientMap = new ConcurrentHashMap<>();

  public QueryDispatcher(MailboxService mailboxService) {
    _mailboxService = mailboxService;
    _executorService = Executors.newFixedThreadPool(2 * Runtime.getRuntime().availableProcessors(),
        new TracedThreadFactory(Thread.NORM_PRIORITY, false, PINOT_BROKER_QUERY_DISPATCHER_FORMAT));
  }

  public void start() {
    _mailboxService.start();
  }

  public QueryResult submitAndReduce(RequestContext context, DispatchableSubPlan dispatchableSubPlan, long timeoutMs,
      Map<String, String> queryOptions)
      throws Exception {
    long requestId = context.getRequestId();
    List<DispatchablePlanFragment> plans = dispatchableSubPlan.getQueryStageList();
    try {
      submit(requestId, dispatchableSubPlan, timeoutMs, queryOptions);
      return runReducer(requestId, dispatchableSubPlan, timeoutMs, queryOptions, _mailboxService);
    } catch (Throwable e) {
      // TODO: Consider always cancel when it returns (early terminate)
      cancel(requestId, plans);
      throw e;
    }
  }

  public List<PlanNode> explain(RequestContext context, DispatchablePlanFragment fragment, long timeoutMs,
      Map<String, String> queryOptions)
      throws TimeoutException, InterruptedException, ExecutionException {
    long requestId = context.getRequestId();
    List<PlanNode> planNodes = new ArrayList<>();

    List<DispatchablePlanFragment> plans = Collections.singletonList(fragment);
    try {
      SendRequest<List<Worker.ExplainResponse>> requestSender = DispatchClient::explain;
      execute(requestId, plans, timeoutMs, queryOptions, requestSender, (responses, serverInstance) -> {
        for (Worker.ExplainResponse response : responses) {
          if (response.containsMetadata(CommonConstants.Query.Response.ServerResponseStatus.STATUS_ERROR)) {
            throw new RuntimeException(
                String.format("Unable to explain query plan for request: %d on server: %s, ERROR: %s", requestId,
                    serverInstance,
                    response.getMetadataOrDefault(CommonConstants.Query.Response.ServerResponseStatus.STATUS_ERROR,
                        "null")));
          }
          for (Worker.StagePlan stagePlan : response.getStagePlanList()) {
            try {
              ByteString rootNode = stagePlan.getRootNode();
              Plan.PlanNode planNode = Plan.PlanNode.parseFrom(rootNode);
              planNodes.add(PlanNodeDeserializer.process(planNode));
            } catch (InvalidProtocolBufferException e) {
              throw new RuntimeException("Failed to parse explain plan node for request " + requestId + " from server "
                  + serverInstance, e);
            }
          }
        }
      });
    } catch (Throwable e) {
      // TODO: Consider always cancel when it returns (early terminate)
      cancel(requestId, plans);
      throw e;
    }
    return planNodes;
  }

  public PinotBrokerTimeSeriesResponse submitAndGet(RequestContext context, TimeSeriesDispatchablePlan plan,
      long timeoutMs, Map<String, String> queryOptions) {
    long requestId = context.getRequestId();
    BlockingQueue<AsyncQueryTimeSeriesDispatchResponse> receiver = new ArrayBlockingQueue<>(10);
    try {
      submit(requestId, plan, timeoutMs, queryOptions, receiver::offer);
      AsyncQueryTimeSeriesDispatchResponse received = receiver.poll(timeoutMs, TimeUnit.MILLISECONDS);
      if (received == null) {
        return PinotBrokerTimeSeriesResponse.newErrorResponse(
            "TimeoutException", "Timed out waiting for response");
      }
      if (received.getThrowable() != null) {
        Throwable t = received.getThrowable();
        return PinotBrokerTimeSeriesResponse.newErrorResponse(t.getClass().getSimpleName(), t.getMessage());
      }
      if (received.getQueryResponse() == null) {
        return PinotBrokerTimeSeriesResponse.newErrorResponse("NullResponse", "Received null response from server");
      }
      if (received.getQueryResponse().containsMetadata(
          WorkerResponseMetadataKeys.ERROR_MESSAGE)) {
        return PinotBrokerTimeSeriesResponse.newErrorResponse(
            received.getQueryResponse().getMetadataOrDefault(
                WorkerResponseMetadataKeys.ERROR_TYPE, "unknown error-type"),
            received.getQueryResponse().getMetadataOrDefault(
                WorkerResponseMetadataKeys.ERROR_MESSAGE, "unknown error"));
      }
      Worker.TimeSeriesResponse timeSeriesResponse = received.getQueryResponse();
      Preconditions.checkNotNull(timeSeriesResponse, "time series response is null");
      return OBJECT_MAPPER.readValue(
          timeSeriesResponse.getPayload().toStringUtf8(), PinotBrokerTimeSeriesResponse.class);
    } catch (Throwable t) {
      return PinotBrokerTimeSeriesResponse.newErrorResponse(t.getClass().getSimpleName(), t.getMessage());
    }
  }

  @VisibleForTesting
  void submit(long requestId, DispatchableSubPlan dispatchableSubPlan, long timeoutMs, Map<String, String> queryOptions)
      throws Exception {
    SendRequest<Worker.QueryResponse> requestSender = DispatchClient::submit;
    List<DispatchablePlanFragment> stagePlans = dispatchableSubPlan.getQueryStageList();
    List<DispatchablePlanFragment> plansWithoutRoot = stagePlans.subList(1, stagePlans.size());
    execute(requestId, plansWithoutRoot, timeoutMs, queryOptions, requestSender, (response, serverInstance) -> {
      if (response.containsMetadata(CommonConstants.Query.Response.ServerResponseStatus.STATUS_ERROR)) {
        throw new RuntimeException(
            String.format("Unable to execute query plan for request: %d on server: %s, ERROR: %s", requestId,
                serverInstance,
                response.getMetadataOrDefault(CommonConstants.Query.Response.ServerResponseStatus.STATUS_ERROR,
                    "null")));
      }
    });
  }

  private <E> void execute(long requestId, List<DispatchablePlanFragment> stagePlans, long timeoutMs,
      Map<String, String> queryOptions, SendRequest<E> sendRequest, BiConsumer<E, QueryServerInstance> resultConsumer)
      throws ExecutionException, InterruptedException, TimeoutException {

    Deadline deadline = Deadline.after(timeoutMs, TimeUnit.MILLISECONDS);

    Set<QueryServerInstance> serverInstances = new HashSet<>();

    List<StageInfo> stageInfos = serializePlanFragments(stagePlans, serverInstances, deadline);

    Map<String, String> requestMetadata = prepareRequestMetadata(requestId, queryOptions, deadline);
    ByteString protoRequestMetadata = QueryPlanSerDeUtils.toProtoProperties(requestMetadata);

    // Submit the query plan to all servers in parallel
    int numServers = serverInstances.size();
    BlockingQueue<AsyncResponse<E>> dispatchCallbacks = new ArrayBlockingQueue<>(numServers);

    for (QueryServerInstance serverInstance : serverInstances) {
      Consumer<AsyncResponse<E>> callbackConsumer = response -> {
        if (!dispatchCallbacks.offer(response)) {
          LOGGER.warn("Failed to offer response to dispatchCallbacks queue for query: {} on server: {}", requestId,
              serverInstance);
        }
      };
      try {
        Worker.QueryRequest requestBuilder =
            createRequest(serverInstance, stagePlans, stageInfos, protoRequestMetadata);
        DispatchClient dispatchClient = getOrCreateDispatchClient(serverInstance);
        sendRequest.send(dispatchClient, requestBuilder, serverInstance, deadline, callbackConsumer);
      } catch (Throwable t) {
        LOGGER.warn("Caught exception while dispatching query: {} to server: {}", requestId, serverInstance, t);
        callbackConsumer.accept(new AsyncResponse<>(serverInstance, null, t));
      }
    }

    int numSuccessCalls = 0;
    // TODO: Cancel all dispatched requests if one of the dispatch errors out or deadline is breached.
    while (!deadline.isExpired() && numSuccessCalls < numServers) {
      AsyncResponse<E> resp =
          dispatchCallbacks.poll(deadline.timeRemaining(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS);
      if (resp != null) {
        if (resp.getThrowable() != null) {
          throw new RuntimeException(
              String.format("Error dispatching query: %d to server: %s", requestId, resp.getServerInstance()),
              resp.getThrowable());
        } else {
          E response = resp.getResponse();
          assert response != null;
          resultConsumer.accept(response, resp.getServerInstance());
          numSuccessCalls++;
        }
      }
    }
    if (deadline.isExpired()) {
      throw new TimeoutException("Timed out waiting for response of async query-dispatch");
    }
  }

  void submit(long requestId, TimeSeriesDispatchablePlan plan, long timeoutMs, Map<String, String> queryOptions,
      Consumer<AsyncQueryTimeSeriesDispatchResponse> receiver)
      throws Exception {
    Deadline deadline = Deadline.after(timeoutMs, TimeUnit.MILLISECONDS);
    String serializedPlan = plan.getSerializedPlan();
    Worker.TimeSeriesQueryRequest request = Worker.TimeSeriesQueryRequest.newBuilder()
        .setDispatchPlan(ByteString.copyFrom(serializedPlan, StandardCharsets.UTF_8))
        .putAllMetadata(initializeTimeSeriesMetadataMap(plan))
        .putMetadata(CommonConstants.Query.Request.MetadataKeys.REQUEST_ID, Long.toString(requestId))
        .build();
    getOrCreateTimeSeriesDispatchClient(plan.getQueryServerInstance()).submit(request,
        new QueryServerInstance(plan.getQueryServerInstance().getHostname(),
            plan.getQueryServerInstance().getQueryServicePort(), plan.getQueryServerInstance().getQueryMailboxPort()),
        deadline, receiver::accept);
  };

  Map<String, String> initializeTimeSeriesMetadataMap(TimeSeriesDispatchablePlan dispatchablePlan) {
    Map<String, String> result = new HashMap<>();
    result.put(WorkerRequestMetadataKeys.LANGUAGE, dispatchablePlan.getLanguage());
    result.put(WorkerRequestMetadataKeys.START_TIME_SECONDS,
        Long.toString(dispatchablePlan.getTimeBuckets().getStartTime()));
    result.put(WorkerRequestMetadataKeys.WINDOW_SECONDS,
        Long.toString(dispatchablePlan.getTimeBuckets().getBucketSize().getSeconds()));
    result.put(WorkerRequestMetadataKeys.NUM_ELEMENTS,
        Long.toString(dispatchablePlan.getTimeBuckets().getTimeBuckets().length));
    for (Map.Entry<String, List<String>> entry : dispatchablePlan.getPlanIdToSegments().entrySet()) {
      result.put(WorkerRequestMetadataKeys.encodeSegmentListKey(entry.getKey()), String.join(",", entry.getValue()));
    }
    return result;
  }

  private static Worker.QueryRequest createRequest(QueryServerInstance serverInstance,
      List<DispatchablePlanFragment> stagePlans, List<StageInfo> stageInfos, ByteString protoRequestMetadata) {
    Worker.QueryRequest.Builder requestBuilder = Worker.QueryRequest.newBuilder();
    requestBuilder.setVersion(CommonConstants.MultiStageQueryRunner.PlanVersions.V1);
    for (int i = 0; i < stagePlans.size(); i++) {
      DispatchablePlanFragment stagePlan = stagePlans.get(i);
      List<Integer> workerIds = stagePlan.getServerInstanceToWorkerIdMap().get(serverInstance);
      if (workerIds != null) { // otherwise this server doesn't need to execute this stage
        List<WorkerMetadata> stageWorkerMetadataList = stagePlan.getWorkerMetadataList();
        List<WorkerMetadata> workerMetadataList = new ArrayList<>(workerIds.size());
        for (int workerId : workerIds) {
          workerMetadataList.add(stageWorkerMetadataList.get(workerId));
        }
        List<Worker.WorkerMetadata> protoWorkerMetadataList =
            QueryPlanSerDeUtils.toProtoWorkerMetadataList(workerMetadataList);
        StageInfo stageInfo = stageInfos.get(i);

        //@formatter:off
        Worker.StagePlan requestStagePlan = Worker.StagePlan.newBuilder()
            .setRootNode(stageInfo._rootNode)
            .setStageMetadata(
                Worker.StageMetadata.newBuilder()
                    // this is a leak from submitAndReduce (id may be different in explain), but it's fine for now
                    .setStageId(i + 1)
                    .addAllWorkerMetadata(protoWorkerMetadataList)
                    .setCustomProperty(stageInfo._customProperty)
                    .build()
            )
            .build();
        //@formatter:on
        requestBuilder.addStagePlan(requestStagePlan);
      }
    }
    requestBuilder.setMetadata(protoRequestMetadata);
    return requestBuilder.build();
  }

  private static Map<String, String> prepareRequestMetadata(long requestId, Map<String, String> queryOptions,
      Deadline deadline) {
    Map<String, String> requestMetadata = new HashMap<>();
    requestMetadata.put(CommonConstants.Query.Request.MetadataKeys.REQUEST_ID, Long.toString(requestId));
    requestMetadata.put(CommonConstants.Broker.Request.QueryOptionKey.TIMEOUT_MS,
        Long.toString(deadline.timeRemaining(TimeUnit.MILLISECONDS)));
    requestMetadata.putAll(queryOptions);
    return requestMetadata;
  }

  private List<StageInfo> serializePlanFragments(List<DispatchablePlanFragment> stagePlans,
      Set<QueryServerInstance> serverInstances, Deadline deadline)
      throws InterruptedException, ExecutionException, TimeoutException {
    List<CompletableFuture<StageInfo>> stageInfoFutures = new ArrayList<>(stagePlans.size());
    for (DispatchablePlanFragment stagePlan : stagePlans) {
      serverInstances.addAll(stagePlan.getServerInstanceToWorkerIdMap().keySet());
      stageInfoFutures.add(CompletableFuture.supplyAsync(() -> serializePlanFragment(stagePlan), _executorService));
    }
    List<StageInfo> stageInfos = new ArrayList<>(stagePlans.size());
    try {
      for (CompletableFuture<StageInfo> future : stageInfoFutures) {
        stageInfos.add(future.get(deadline.timeRemaining(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS));
      }
    } finally {
      for (CompletableFuture<?> future : stageInfoFutures) {
        if (!future.isDone()) {
          future.cancel(true);
        }
      }
    }
    return stageInfos;
  }

  private static StageInfo serializePlanFragment(DispatchablePlanFragment stagePlan) {
    ByteString rootNode = PlanNodeSerializer.process(stagePlan.getPlanFragment().getFragmentRoot()).toByteString();
    ByteString customProperty = QueryPlanSerDeUtils.toProtoProperties(stagePlan.getCustomProperties());
    return new StageInfo(rootNode, customProperty);
  }

  private static class StageInfo {
    final ByteString _rootNode;
    final ByteString _customProperty;

    private StageInfo(ByteString rootNode, ByteString customProperty) {
      _rootNode = rootNode;
      _customProperty = customProperty;
    }
  }

  private void cancel(long requestId, List<DispatchablePlanFragment> stagePlans) {
    int numStages = stagePlans.size();
    // Skip the reduce stage (stage 0)
    Set<QueryServerInstance> serversToCancel = new HashSet<>();
    for (int stageId = 1; stageId < numStages; stageId++) {
      serversToCancel.addAll(stagePlans.get(stageId).getServerInstanceToWorkerIdMap().keySet());
    }
    for (QueryServerInstance queryServerInstance : serversToCancel) {
      try {
        getOrCreateDispatchClient(queryServerInstance).cancel(requestId);
      } catch (Throwable t) {
        LOGGER.warn("Caught exception while cancelling query: {} on server: {}", requestId, queryServerInstance, t);
      }
    }
  }

  private DispatchClient getOrCreateDispatchClient(QueryServerInstance queryServerInstance) {
    String hostname = queryServerInstance.getHostname();
    int port = queryServerInstance.getQueryServicePort();
    String key = String.format("%s_%d", hostname, port);
    return _dispatchClientMap.computeIfAbsent(key, k -> new DispatchClient(hostname, port));
  }

  private TimeSeriesDispatchClient getOrCreateTimeSeriesDispatchClient(
      TimeSeriesQueryServerInstance queryServerInstance) {
    String hostname = queryServerInstance.getHostname();
    int port = queryServerInstance.getQueryServicePort();
    String key = String.format("%s_%d", hostname, port);
    return _timeSeriesDispatchClientMap.computeIfAbsent(key, k -> new TimeSeriesDispatchClient(hostname, port));
  }

  @VisibleForTesting
  public static QueryResult runReducer(long requestId, DispatchableSubPlan dispatchableSubPlan, long timeoutMs,
      Map<String, String> queryOptions, MailboxService mailboxService) {
    long startTimeMs = System.currentTimeMillis();
    long deadlineMs = startTimeMs + timeoutMs;

    // NOTE: Reduce stage is always stage 0
    DispatchablePlanFragment dispatchableStagePlan = dispatchableSubPlan.getQueryStageList().get(0);
    PlanFragment planFragment = dispatchableStagePlan.getPlanFragment();
    PlanNode rootNode = planFragment.getFragmentRoot();
    Preconditions.checkState(rootNode instanceof MailboxReceiveNode,
        "Expecting mailbox receive node as root of reduce stage, got: %s", rootNode.getClass().getSimpleName());
    MailboxReceiveNode receiveNode = (MailboxReceiveNode) rootNode;
    List<WorkerMetadata> workerMetadataList = dispatchableStagePlan.getWorkerMetadataList();
    Preconditions.checkState(workerMetadataList.size() == 1, "Expecting single worker for reduce stage, got: %s",
        workerMetadataList.size());
    StageMetadata stageMetadata = new StageMetadata(0, workerMetadataList, dispatchableStagePlan.getCustomProperties());
    ThreadExecutionContext parentContext = Tracing.getThreadAccountant().getThreadExecutionContext();
    OpChainExecutionContext opChainExecutionContext =
        new OpChainExecutionContext(mailboxService, requestId, deadlineMs, queryOptions, stageMetadata,
            workerMetadataList.get(0), null, parentContext);

    PairList<Integer, String> resultFields = dispatchableSubPlan.getQueryResultFields();
    DataSchema sourceDataSchema = receiveNode.getDataSchema();
    int numColumns = resultFields.size();
    String[] columnNames = new String[numColumns];
    ColumnDataType[] columnTypes = new ColumnDataType[numColumns];
    for (int i = 0; i < numColumns; i++) {
      Map.Entry<Integer, String> field = resultFields.get(i);
      columnNames[i] = field.getValue();
      columnTypes[i] = sourceDataSchema.getColumnDataType(field.getKey());
    }
    DataSchema resultDataSchema = new DataSchema(columnNames, columnTypes);

    ArrayList<Object[]> resultRows = new ArrayList<>();
    TransferableBlock block;
    try (MailboxReceiveOperator receiveOperator = new MailboxReceiveOperator(opChainExecutionContext, receiveNode)) {
      block = receiveOperator.nextBlock();
      while (!TransferableBlockUtils.isEndOfStream(block)) {
        DataBlock dataBlock = block.getDataBlock();
        int numRows = dataBlock.getNumberOfRows();
        if (numRows > 0) {
          resultRows.ensureCapacity(resultRows.size() + numRows);
          List<Object[]> rawRows = DataBlockExtractUtils.extractRows(dataBlock);
          for (Object[] rawRow : rawRows) {
            Object[] row = new Object[numColumns];
            for (int i = 0; i < numColumns; i++) {
              Object rawValue = rawRow[resultFields.get(i).getKey()];
              if (rawValue != null) {
                ColumnDataType dataType = columnTypes[i];
                row[i] = dataType.format(dataType.toExternal(rawValue));
              }
            }
            resultRows.add(row);
          }
        }
        block = receiveOperator.nextBlock();
      }
    }
    // TODO: Improve the error handling, e.g. return partial response
    if (block.isErrorBlock()) {
      throw new RuntimeException("Received error query execution result block: " + block.getExceptions());
    }
    assert block.isSuccessfulEndOfStreamBlock();
    MultiStageQueryStats queryStats = block.getQueryStats();
    assert queryStats != null;
    return new QueryResult(new ResultTable(resultDataSchema, resultRows), queryStats,
        System.currentTimeMillis() - startTimeMs);
  }

  public void shutdown() {
    for (DispatchClient dispatchClient : _dispatchClientMap.values()) {
      dispatchClient.getChannel().shutdown();
    }
    _dispatchClientMap.clear();
    _mailboxService.shutdown();
    _executorService.shutdown();
  }

  public static class QueryResult {
    private final ResultTable _resultTable;
    private final List<MultiStageQueryStats.StageStats.Closed> _queryStats;
    private final long _brokerReduceTimeMs;

    public QueryResult(ResultTable resultTable, MultiStageQueryStats queryStats, long brokerReduceTimeMs) {
      _resultTable = resultTable;
      Preconditions.checkArgument(queryStats.getCurrentStageId() == 0, "Expecting query stats for stage 0, got: %s",
          queryStats.getCurrentStageId());
      int numStages = queryStats.getMaxStageId() + 1;
      _queryStats = new ArrayList<>(numStages);
      _queryStats.add(queryStats.getCurrentStats().close());
      for (int i = 1; i < numStages; i++) {
        _queryStats.add(queryStats.getUpstreamStageStats(i));
      }
      _brokerReduceTimeMs = brokerReduceTimeMs;
    }

    public ResultTable getResultTable() {
      return _resultTable;
    }

    public List<MultiStageQueryStats.StageStats.Closed> getQueryStats() {
      return _queryStats;
    }

    public long getBrokerReduceTimeMs() {
      return _brokerReduceTimeMs;
    }
  }

  private interface SendRequest<E> {
    void send(DispatchClient dispatchClient, Worker.QueryRequest request, QueryServerInstance serverInstance,
        Deadline deadline, Consumer<AsyncResponse<E>> callbackConsumer);
  }
}
