/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.io.aws2.kinesis;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions.checkArgument;
import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions.checkState;
import static org.apache.commons.lang3.ArrayUtils.EMPTY_BYTE_ARRAY;
import static org.apache.commons.lang3.StringUtils.isEmpty;

import com.google.auto.value.AutoValue;
import java.io.Serializable;
import java.math.BigInteger;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.annotations.Experimental.Kind;
import org.apache.beam.sdk.io.Read.Unbounded;
import org.apache.beam.sdk.io.aws2.common.ClientBuilderFactory;
import org.apache.beam.sdk.io.aws2.common.ClientConfiguration;
import org.apache.beam.sdk.io.aws2.common.ClientPool;
import org.apache.beam.sdk.io.aws2.common.RetryConfiguration;
import org.apache.beam.sdk.io.aws2.options.AwsOptions;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Distribution;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.transforms.Combine.BinaryCombineLongFn;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.Max;
import org.apache.beam.sdk.transforms.Min;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.Sum;
import org.apache.beam.sdk.util.FluentBackoff;
import org.apache.beam.sdk.util.MovingFunction;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PInput;
import org.apache.beam.sdk.values.POutput;
import org.apache.beam.sdk.values.PValue;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableMap;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;
import org.joda.time.DateTimeUtils;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient;
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequestEntry;
import software.amazon.kinesis.common.InitialPositionInStream;

/**
 * IO to read from <a href="https://aws.amazon.com/kinesis/">Kinesis</a> streams.
 *
 * <h3>Reading from Kinesis</h3>
 *
 * <p>Example usages:
 *
 * <pre>{@code
 * p.apply(KinesisIO.read()
 *     .withStreamName("streamName")
 *     .withInitialPositionInStream(InitialPositionInStream.LATEST)
 *  .apply( ... ) // other transformations
 * }</pre>
 *
 * <p>At a minimum you have to provide:
 *
 * <ul>
 *   <li>the name of the stream to read
 *   <li>the position in the stream where to start reading, e.g. {@link
 *       InitialPositionInStream#LATEST}, {@link InitialPositionInStream#TRIM_HORIZON}, or
 *       alternatively, using an arbitrary point in time with {@link
 *       Read#withInitialTimestampInStream(Instant)}.
 * </ul>
 *
 * <h4>Watermarks</h4>
 *
 * <p>Kinesis IO uses arrival time for watermarks by default. To use processing time instead, use
 * {@link Read#withProcessingTimeWatermarkPolicy()}:
 *
 * <pre>{@code
 * p.apply(KinesisIO.read()
 *    .withStreamName("streamName")
 *    .withInitialPositionInStream(InitialPositionInStream.LATEST)
 *    .withProcessingTimeWatermarkPolicy())
 * }</pre>
 *
 * <p>It is also possible to specify a custom watermark policy to control watermark computation
 * using {@link Read#withCustomWatermarkPolicy(WatermarkPolicyFactory)}. This requires implementing
 * {@link WatermarkPolicy} with a corresponding {@link WatermarkPolicyFactory}.
 *
 * <h4>Throttling</h4>
 *
 * <p>By default Kinesis IO will poll the Kinesis {@code getRecords()} API as fast as possible as
 * long as records are returned. The {@link RateLimitPolicyFactory.DefaultRateLimiter} will start
 * throttling once {@code getRecords()} returns an empty response or if API calls get throttled by
 * AWS.
 *
 * <p>A {@link RateLimitPolicy} is always applied to each shard individually.
 *
 * <p>You may provide a custom rate limit policy using {@link
 * Read#withCustomRateLimitPolicy(RateLimitPolicyFactory)}. This requires implementing {@link
 * RateLimitPolicy} with a corresponding {@link RateLimitPolicyFactory}.
 *
 * <h3>Writing to Kinesis</h3>
 *
 * <p>Example usages:
 *
 * <pre>{@code PCollection<KV<String, byte[]>> data = ...;
 *
 * data.apply(KinesisIO.write()
 *     .withStreamName("streamName")
 *     .withPartitionKey(KV::getKey)
 *     .withSerializer(KV::getValue);
 * }</pre>
 *
 * <p>Note: Usage of {@link org.apache.beam.sdk.values.KV} is just for illustration purposes here.
 *
 * <p>At a minimum you have to provide:
 *
 * <ul>
 *   <li>the name of the Kinesis stream to write to,
 *   <li>a {@link KinesisPartitioner} to distribute records across shards of the stream
 *   <li>and a function to serialize your data to bytes on the stream
 * </ul>
 *
 * Though, generally, it's recommended to configure client retries using {@link
 * ClientConfiguration}, see below.
 *
 * <h4>Partitioning of writes</h4>
 *
 * Choosing the right partitioning strategy by means of a {@link KinesisPartitioner} is one of the
 * key considerations when writing to Kinesis. Typically, you should aime to evenly distribute data
 * across all shards of the stream.
 *
 * <p>Partition keys are used as input to a hash function that maps the partition key and associated
 * data to a specific shard. If the cardinality of your partition keys is of the same order of
 * magnitude as the number of shards in the stream, the hash function will likely not distribute
 * your keys evenly among shards. This may result in heavily skewed shards with some shards not
 * utilized at all.
 *
 * <p>If you require finer control over the distribution of records, override {@link
 * KinesisPartitioner#getExplicitHashKey(Object)} according to your needs.
 *
 * <h4>Aggregation of records</h4>
 *
 * To better leverage Kinesis API limits and to improve producer throughput, the writer aggregates
 * multiple users records into an <a
 * href="https://docs.aws.amazon.com/streams/latest/dev/kinesis-kpl-concepts.html#kinesis-kpl-concepts-aggretation">aggregated
 * KPL record</a>.
 *
 * <p>However, only records with the same effective hash key are aggregated, in which the effective
 * hash key is either the explicit hash key if defined, or otherwise the hashed partition key.
 *
 * <p>Record aggregation can be explicitly disabled using {@link
 * Write#withRecordAggregationDisabled()}.
 *
 * <h3>Configuration of AWS clients</h3>
 *
 * <p>AWS clients for all AWS IOs can be configured using {@link AwsOptions}, e.g. {@code
 * --awsRegion=us-west-1}. {@link AwsOptions} contain reasonable defaults based on default providers
 * for {@link Region} and {@link AwsCredentialsProvider}.
 *
 * <p>If you require more advanced configuration, you may change the {@link ClientBuilderFactory}
 * using {@link AwsOptions#setClientBuilderFactory(Class)}.
 *
 * <p>Configuration for a specific IO can be overwritten using {@code withClientConfiguration()},
 * which also allows to configure the retry behavior for the respective IO.
 *
 * <h4>Retries</h4>
 *
 * <p>Retries for failed requests can be configured using {@link
 * ClientConfiguration.Builder#retry(Consumer)} and are handled by the AWS SDK unless there's a
 * partial success (batch requests). The SDK uses a backoff strategy with equal jitter for computing
 * the delay before the next retry.
 *
 * <p><b>Note:</b> Once retries are exhausted the error is surfaced to the runner which <em>may</em>
 * then opt to retry the current partition in entirety or abort if the max number of retries of the
 * runner is reached.
 */
@Experimental(Kind.SOURCE_SINK)
public final class KinesisIO {

  /** Returns a new {@link Read} transform for reading from Kinesis. */
  public static Read read() {
    return new AutoValue_KinesisIO_Read.Builder()
        .setClientConfiguration(ClientConfiguration.builder().build())
        .setMaxNumRecords(Long.MAX_VALUE)
        .setUpToDateThreshold(Duration.ZERO)
        .setWatermarkPolicyFactory(WatermarkPolicyFactory.withArrivalTimePolicy())
        .setRateLimitPolicyFactory(RateLimitPolicyFactory.withDefaultRateLimiter())
        .setMaxCapacityPerShard(ShardReadersPool.DEFAULT_CAPACITY_PER_SHARD)
        .build();
  }

  /** Returns a new {@link Write} transform for writing to Kinesis. */
  public static <T> Write<T> write() {
    return new AutoValue_KinesisIO_Write.Builder<T>()
        .streamName("") // dummy value
        .serializer((SerializableFunction<T, byte[]>) Write.DUMMY_SERIALIZER)
        .partitioner((KinesisPartitioner<T>) Write.DUMMY_PARTITIONER)
        .clientConfiguration(ClientConfiguration.builder().build())
        .batchMaxRecords(Write.MAX_RECORDS_PER_REQUEST)
        .batchMaxBytes((int) (Write.MAX_BYTES_PER_REQUEST * 0.9)) // allow some error margin
        .concurrentRequests(Write.DEFAULT_CONCURRENCY)
        .recordAggregation(RecordAggregation.builder().build())
        .build();
  }

  /** Implementation of {@link #read()}. */
  @AutoValue
  @SuppressWarnings({
    "nullness" // TODO(https://issues.apache.org/jira/browse/BEAM-10402)
  })
  public abstract static class Read extends PTransform<PBegin, PCollection<KinesisRecord>> {

    abstract @Nullable String getStreamName();

    abstract @Nullable StartingPoint getInitialPosition();

    abstract @Nullable ClientConfiguration getClientConfiguration();

    abstract @Nullable AWSClientsProvider getAWSClientsProvider();

    abstract long getMaxNumRecords();

    abstract @Nullable Duration getMaxReadTime();

    abstract Duration getUpToDateThreshold();

    abstract @Nullable Integer getRequestRecordsLimit();

    abstract WatermarkPolicyFactory getWatermarkPolicyFactory();

    abstract RateLimitPolicyFactory getRateLimitPolicyFactory();

    abstract Integer getMaxCapacityPerShard();

    abstract Builder toBuilder();

    @AutoValue.Builder
    abstract static class Builder {

      abstract Builder setStreamName(String streamName);

      abstract Builder setInitialPosition(StartingPoint startingPoint);

      abstract Builder setClientConfiguration(ClientConfiguration config);

      abstract Builder setAWSClientsProvider(AWSClientsProvider clientProvider);

      abstract Builder setMaxNumRecords(long maxNumRecords);

      abstract Builder setMaxReadTime(Duration maxReadTime);

      abstract Builder setUpToDateThreshold(Duration upToDateThreshold);

      abstract Builder setRequestRecordsLimit(Integer limit);

      abstract Builder setWatermarkPolicyFactory(WatermarkPolicyFactory watermarkPolicyFactory);

      abstract Builder setRateLimitPolicyFactory(RateLimitPolicyFactory rateLimitPolicyFactory);

      abstract Builder setMaxCapacityPerShard(Integer maxCapacity);

      abstract Read build();
    }

    /** Specify reading from streamName. */
    public Read withStreamName(String streamName) {
      return toBuilder().setStreamName(streamName).build();
    }

    /** Specify reading from some initial position in stream. */
    public Read withInitialPositionInStream(InitialPositionInStream initialPosition) {
      return toBuilder().setInitialPosition(new StartingPoint(initialPosition)).build();
    }

    /**
     * Specify reading beginning at given {@link Instant}. This {@link Instant} must be in the past,
     * i.e. before {@link Instant#now()}.
     */
    public Read withInitialTimestampInStream(Instant initialTimestamp) {
      return toBuilder().setInitialPosition(new StartingPoint(initialTimestamp)).build();
    }

    /**
     * @deprecated Use {@link #withClientConfiguration(ClientConfiguration)} instead. Alternatively
     *     you can configure a custom {@link ClientBuilderFactory} in {@link AwsOptions}.
     */
    @Deprecated
    public Read withAWSClientsProvider(AWSClientsProvider clientProvider) {
      checkArgument(clientProvider != null, "AWSClientsProvider cannot be null");
      return toBuilder().setClientConfiguration(null).setAWSClientsProvider(clientProvider).build();
    }

    /** @deprecated Use {@link #withClientConfiguration(ClientConfiguration)} instead. */
    @Deprecated
    public Read withAWSClientsProvider(String awsAccessKey, String awsSecretKey, Region region) {
      return withAWSClientsProvider(awsAccessKey, awsSecretKey, region, null);
    }

    /** @deprecated Use {@link #withClientConfiguration(ClientConfiguration)} instead. */
    @Deprecated
    public Read withAWSClientsProvider(
        String awsAccessKey, String awsSecretKey, Region region, String serviceEndpoint) {
      AwsCredentialsProvider awsCredentialsProvider =
          StaticCredentialsProvider.create(AwsBasicCredentials.create(awsAccessKey, awsSecretKey));
      return withAWSClientsProvider(awsCredentialsProvider, region, serviceEndpoint);
    }

    /** @deprecated Use {@link #withClientConfiguration(ClientConfiguration)} instead. */
    @Deprecated
    public Read withAWSClientsProvider(
        AwsCredentialsProvider awsCredentialsProvider, Region region) {
      return withAWSClientsProvider(awsCredentialsProvider, region, null);
    }

    /** @deprecated Use {@link #withClientConfiguration(ClientConfiguration)} instead. */
    @Deprecated
    public Read withAWSClientsProvider(
        AwsCredentialsProvider awsCredentialsProvider, Region region, String serviceEndpoint) {
      URI endpoint = serviceEndpoint != null ? URI.create(serviceEndpoint) : null;
      return updateClientConfig(
          b ->
              b.credentialsProvider(awsCredentialsProvider)
                  .region(region)
                  .endpoint(endpoint)
                  .build());
    }

    /** Configuration of Kinesis & Cloudwatch clients. */
    public Read withClientConfiguration(ClientConfiguration config) {
      return updateClientConfig(ignore -> config);
    }

    private Read updateClientConfig(Function<ClientConfiguration.Builder, ClientConfiguration> fn) {
      checkState(
          getAWSClientsProvider() == null,
          "Legacy AWSClientsProvider is set, but incompatible with ClientConfiguration.");
      ClientConfiguration config = fn.apply(getClientConfiguration().toBuilder());
      checkArgument(config != null, "ClientConfiguration cannot be null");
      return toBuilder().setClientConfiguration(config).build();
    }

    /** Specifies to read at most a given number of records. */
    public Read withMaxNumRecords(long maxNumRecords) {
      checkArgument(
          maxNumRecords > 0, "maxNumRecords must be positive, but was: %s", maxNumRecords);
      return toBuilder().setMaxNumRecords(maxNumRecords).build();
    }

    /** Specifies to read records during {@code maxReadTime}. */
    public Read withMaxReadTime(Duration maxReadTime) {
      checkArgument(maxReadTime != null, "maxReadTime can not be null");
      return toBuilder().setMaxReadTime(maxReadTime).build();
    }

    /**
     * Specifies how late records consumed by this source can be to still be considered on time.
     * When this limit is exceeded the actual backlog size will be evaluated and the runner might
     * decide to scale the amount of resources allocated to the pipeline in order to speed up
     * ingestion.
     */
    public Read withUpToDateThreshold(Duration upToDateThreshold) {
      checkArgument(upToDateThreshold != null, "upToDateThreshold can not be null");
      return toBuilder().setUpToDateThreshold(upToDateThreshold).build();
    }

    /**
     * Specifies the maximum number of records in GetRecordsResult returned by GetRecords call which
     * is limited by 10K records. If should be adjusted according to average size of data record to
     * prevent shard overloading. More details can be found here: <a
     * href="https://docs.aws.amazon.com/kinesis/latest/APIReference/API_GetRecords.html">API_GetRecords</a>
     */
    public Read withRequestRecordsLimit(int limit) {
      checkArgument(limit > 0, "limit must be positive, but was: %s", limit);
      checkArgument(limit <= 10_000, "limit must be up to 10,000, but was: %s", limit);
      return toBuilder().setRequestRecordsLimit(limit).build();
    }

    /** Specifies the {@code WatermarkPolicyFactory} as ArrivalTimeWatermarkPolicyFactory. */
    public Read withArrivalTimeWatermarkPolicy() {
      return toBuilder()
          .setWatermarkPolicyFactory(WatermarkPolicyFactory.withArrivalTimePolicy())
          .build();
    }

    /**
     * Specifies the {@code WatermarkPolicyFactory} as ArrivalTimeWatermarkPolicyFactory.
     *
     * <p>{@param watermarkIdleDurationThreshold} Denotes the duration for which the watermark can
     * be idle.
     */
    public Read withArrivalTimeWatermarkPolicy(Duration watermarkIdleDurationThreshold) {
      return toBuilder()
          .setWatermarkPolicyFactory(
              WatermarkPolicyFactory.withArrivalTimePolicy(watermarkIdleDurationThreshold))
          .build();
    }

    /** Specifies the {@code WatermarkPolicyFactory} as ProcessingTimeWatermarkPolicyFactory. */
    public Read withProcessingTimeWatermarkPolicy() {
      return toBuilder()
          .setWatermarkPolicyFactory(WatermarkPolicyFactory.withProcessingTimePolicy())
          .build();
    }

    /**
     * Specifies the {@code WatermarkPolicyFactory} as a custom watermarkPolicyFactory.
     *
     * @param watermarkPolicyFactory Custom Watermark policy factory.
     */
    public Read withCustomWatermarkPolicy(WatermarkPolicyFactory watermarkPolicyFactory) {
      checkArgument(watermarkPolicyFactory != null, "watermarkPolicyFactory cannot be null");
      return toBuilder().setWatermarkPolicyFactory(watermarkPolicyFactory).build();
    }

    /** Specifies a fixed delay rate limit policy with the default delay of 1 second. */
    public Read withFixedDelayRateLimitPolicy() {
      return toBuilder().setRateLimitPolicyFactory(RateLimitPolicyFactory.withFixedDelay()).build();
    }

    /**
     * Specifies a fixed delay rate limit policy with the given delay.
     *
     * @param delay Denotes the fixed delay duration.
     */
    public Read withFixedDelayRateLimitPolicy(Duration delay) {
      checkArgument(delay != null, "delay cannot be null");
      return toBuilder()
          .setRateLimitPolicyFactory(RateLimitPolicyFactory.withFixedDelay(delay))
          .build();
    }

    /**
     * Specifies a dynamic delay rate limit policy with the given function being called at each
     * polling interval to get the next delay value. This can be used to change the polling interval
     * of a running pipeline based on some external configuration source, for example.
     *
     * @param delay The function to invoke to get the next delay duration.
     */
    public Read withDynamicDelayRateLimitPolicy(Supplier<Duration> delay) {
      checkArgument(delay != null, "delay cannot be null");
      return toBuilder().setRateLimitPolicyFactory(RateLimitPolicyFactory.withDelay(delay)).build();
    }

    /**
     * Specifies the {@code RateLimitPolicyFactory} for a custom rate limiter.
     *
     * @param rateLimitPolicyFactory Custom rate limit policy factory.
     */
    public Read withCustomRateLimitPolicy(RateLimitPolicyFactory rateLimitPolicyFactory) {
      checkArgument(rateLimitPolicyFactory != null, "rateLimitPolicyFactory cannot be null");
      return toBuilder().setRateLimitPolicyFactory(rateLimitPolicyFactory).build();
    }

    /** Specifies the maximum number of messages per one shard. */
    public Read withMaxCapacityPerShard(Integer maxCapacity) {
      checkArgument(maxCapacity > 0, "maxCapacity must be positive, but was: %s", maxCapacity);
      return toBuilder().setMaxCapacityPerShard(maxCapacity).build();
    }

    @Override
    public PCollection<KinesisRecord> expand(PBegin input) {
      checkArgument(getWatermarkPolicyFactory() != null, "WatermarkPolicyFactory is required");
      checkArgument(getRateLimitPolicyFactory() != null, "RateLimitPolicyFactory is required");
      if (getAWSClientsProvider() == null) {
        checkArgument(getClientConfiguration() != null, "ClientConfiguration is required");
        AwsOptions awsOptions = input.getPipeline().getOptions().as(AwsOptions.class);
        ClientBuilderFactory.validate(awsOptions, getClientConfiguration());
      }

      Unbounded<KinesisRecord> unbounded =
          org.apache.beam.sdk.io.Read.from(new KinesisSource(this));

      PTransform<PBegin, PCollection<KinesisRecord>> transform = unbounded;

      if (getMaxNumRecords() < Long.MAX_VALUE || getMaxReadTime() != null) {
        transform =
            unbounded.withMaxReadTime(getMaxReadTime()).withMaxNumRecords(getMaxNumRecords());
      }

      return input.apply(transform);
    }
  }

  /**
   * Configuration of Kinesis record aggregation.
   *
   * <p>Record aggregation is compatible with KPL/KCL and helps to better max out API limits.
   *
   * @see <a
   *     href="https://docs.aws.amazon.com/streams/latest/dev/kinesis-kpl-concepts.html#kinesis-kpl-concepts-aggretation">KPL
   *     Concepts: aggregation</a>
   */
  @AutoValue
  public abstract static class RecordAggregation implements Serializable {

    abstract int maxBytes();

    abstract Duration maxBufferedTime();

    abstract double maxBufferedTimeJitter();

    public static Builder builder() {
      return new AutoValue_KinesisIO_RecordAggregation.Builder()
          .maxBytes(Write.MAX_BYTES_PER_RECORD)
          .maxBufferedTimeJitter(0.7) // 70% jitter
          .maxBufferedTime(Duration.standardSeconds(1));
    }

    @AutoValue.Builder
    public abstract static class Builder {
      /** Max bytes per aggregated record. */
      public abstract Builder maxBytes(int bytes);

      /**
       * Buffer timeout for user records.
       *
       * <p>Note: This is only attempted on a best effort basis. In case request latency is too
       * high, timeouts can be delayed.
       */
      public abstract Builder maxBufferedTime(Duration interval);

      abstract Builder maxBufferedTimeJitter(double jitter);

      abstract RecordAggregation autoBuild();

      public RecordAggregation build() {
        RecordAggregation agg = autoBuild();
        checkArgument(agg.maxBufferedTimeJitter() >= 0 && agg.maxBufferedTimeJitter() <= 1);
        checkArgument(
            agg.maxBytes() > 0 && agg.maxBytes() <= Write.MAX_BYTES_PER_RECORD,
            "maxBytes must be positive and <= %s",
            Write.MAX_BYTES_PER_RECORD);
        return agg;
      }
    }
  }

  /** Implementation of {@link #write()}. */
  @AutoValue
  public abstract static class Write<T> extends PTransform<PCollection<T>, Write.Result> {
    static final int MAX_RECORDS_PER_REQUEST = 500;
    static final int MAX_BYTES_PER_RECORD = 1024 * 1024;
    static final int MAX_BYTES_PER_REQUEST = 5 * 1024 * 1024;

    private static final int DEFAULT_CONCURRENCY = 3;

    private static final KinesisPartitioner<? extends Object> DUMMY_PARTITIONER = obj -> "";
    private static final SerializableFunction<? extends Object, byte[]> DUMMY_SERIALIZER =
        obj -> EMPTY_BYTE_ARRAY;

    abstract @Pure String streamName();

    abstract @Pure int batchMaxRecords();

    abstract @Pure int batchMaxBytes();

    abstract @Pure int concurrentRequests();

    abstract @Pure KinesisPartitioner<T> partitioner();

    abstract @Pure SerializableFunction<T, byte[]> serializer();

    abstract @Pure ClientConfiguration clientConfiguration();

    abstract @Pure @Nullable RecordAggregation recordAggregation();

    abstract Builder<T> builder();

    @AutoValue.Builder
    abstract static class Builder<T> {
      abstract Builder<T> streamName(String streamName);

      abstract Builder<T> batchMaxRecords(int records);

      abstract Builder<T> batchMaxBytes(int bytes);

      abstract Builder<T> concurrentRequests(int concurrentRequests);

      abstract Builder<T> partitioner(KinesisPartitioner<T> partitioner);

      abstract Builder<T> serializer(SerializableFunction<T, byte[]> serializer);

      abstract Builder<T> clientConfiguration(ClientConfiguration config);

      abstract Builder<T> recordAggregation(@Nullable RecordAggregation aggregation);

      abstract Write<T> build();
    }

    /** Kinesis stream name which will be used for writing (required). */
    public Write<T> withStreamName(String streamName) {
      checkArgument(!isEmpty(streamName), "streamName cannot be empty");
      return builder().streamName(streamName).build();
    }

    /** Max. number of records to send per batch write request. */
    public Write<T> withBatchMaxRecords(int records) {
      checkArgument(
          records > 0 && records <= MAX_RECORDS_PER_REQUEST,
          "batchMaxRecords must be in [1,%s]",
          MAX_RECORDS_PER_REQUEST);
      return builder().batchMaxRecords(records).build();
    }

    /**
     * Max. number of bytes to send per batch write request.
     *
     * <p>Single records that exceed this limit are sent individually. Though, be careful to not
     * violate the AWS API limit of 1MB per request.
     *
     * <p>This includes both partition keys and data.
     */
    public Write<T> withBatchMaxBytes(int bytes) {
      checkArgument(
          bytes > 0 && bytes <= MAX_BYTES_PER_REQUEST,
          "batchMaxBytes must be in [1,%s]",
          MAX_BYTES_PER_REQUEST);
      return builder().batchMaxBytes(bytes).build();
    }

    /**
     * Max number of concurrent batch write requests per bundle.
     *
     * <p>Note: Concurrency settings above the default have caused a bug in the AWS SDK v2.
     * Therefore, this configuration is currently not exposed to users.
     */
    public Write<T> withConcurrentRequests(int concurrentRequests) {
      checkArgument(concurrentRequests > 0, "concurrentRequests must be > 0");
      return builder().concurrentRequests(concurrentRequests).build();
    }

    /**
     * Enable record aggregation that is compatible with the KPL / KCL.
     *
     * <p>https://docs.aws.amazon.com/streams/latest/dev/kinesis-kpl-concepts.html#kinesis-kpl-concepts-aggretation
     *
     * <p>Note: The aggregation is a lot simpler than the one offered by KPL. It only aggregates
     * records with the same partition key as it's not aware of explicit hash key ranges per shard.
     */
    public Write<T> withRecordAggregation(RecordAggregation aggregation) {
      return builder().recordAggregation(aggregation).build();
    }

    /**
     * Enable record aggregation that is compatible with the KPL / KCL.
     *
     * <p>https://docs.aws.amazon.com/streams/latest/dev/kinesis-kpl-concepts.html#kinesis-kpl-concepts-aggretation
     *
     * <p>Note: The aggregation is a lot simpler than the one offered by KPL. It only aggregates
     * records with the same partition key as it's not aware of explicit hash key ranges per shard.
     */
    public Write<T> withRecordAggregation(Consumer<RecordAggregation.Builder> aggregation) {
      RecordAggregation.Builder builder = RecordAggregation.builder();
      aggregation.accept(builder);
      return withRecordAggregation(builder.build());
    }

    /** Disable KPL / KCL like record aggregation. */
    public Write<T> withRecordAggregationDisabled() {
      return builder().recordAggregation(null).build();
    }

    /**
     * Specify how to partition records among all stream shards (required).
     *
     * <p>The partitioner is critical to distribute new records among all stream shards.
     */
    public Write<T> withPartitioner(KinesisPartitioner<T> partitioner) {
      checkArgument(partitioner() != null, "partitioner cannot be null");
      return builder().partitioner(partitioner).build();
    }

    /** Specify how to serialize records to bytes on the stream (required). */
    public Write<T> withSerializer(SerializableFunction<T, byte[]> serializer) {
      checkArgument(serializer() != null, "serializer cannot be null");
      return builder().serializer(serializer).build();
    }

    /** Configuration of Kinesis client. */
    public Write<T> withClientConfiguration(ClientConfiguration config) {
      checkArgument(config != null, "clientConfiguration cannot be null");
      return builder().clientConfiguration(config).build();
    }

    @Override
    public Result expand(PCollection<T> input) {
      checkArgument(!isEmpty(streamName()), "streamName is required");
      checkArgument(partitioner() != DUMMY_PARTITIONER, "partitioner is required");
      checkArgument(serializer() != DUMMY_SERIALIZER, "serializer is required");

      AwsOptions awsOptions = input.getPipeline().getOptions().as(AwsOptions.class);
      ClientBuilderFactory.validate(awsOptions, clientConfiguration());
      input.apply(
          ParDo.of(
              new DoFn<T, Void>() {
                private transient @Nullable Writer<T> writer;

                @Setup
                public void setup(PipelineOptions options) {
                  writer =
                      recordAggregation() != null
                          ? new AggregatedWriter<>(options, Write.this, recordAggregation())
                          : new Writer<>(options, Write.this);
                }

                @StartBundle
                public void startBundle() {
                  writer().startBundle();
                }

                @ProcessElement
                public void processElement(@Element T record) throws Throwable {
                  writer().write(record);
                }

                @FinishBundle
                public void finishBundle() throws Throwable {
                  writer().finishBundle();
                }

                @Teardown
                public void teardown() throws Exception {
                  if (writer != null) {
                    writer.close();
                    writer = null;
                  }
                }

                private Writer<T> writer() {
                  if (writer == null) {
                    throw new IllegalStateException("RecordWriter is null");
                  }
                  return writer;
                }
              }));
      return new Result(input.getPipeline());
    }

    /** Result of {@link KinesisIO#write()}. */
    public static class Result implements POutput {
      private final Pipeline pipeline;

      private Result(Pipeline pipeline) {
        this.pipeline = pipeline;
      }

      @Override
      public Pipeline getPipeline() {
        return pipeline;
      }

      @Override
      public Map<TupleTag<?>, PValue> expand() {
        return ImmutableMap.of();
      }

      @Override
      public void finishSpecifyingOutput(
          String transformName, PInput input, PTransform<?, ?> transform) {}
    }

    /** Base Kinesis batch record writer, but not using record aggregation. */
    private static class Writer<T> implements AutoCloseable {
      private static final int PARTITION_KEY_MAX_LENGTH = 256;
      private static final int PARTITION_KEY_MIN_LENGTH = 1;

      private static final int PARTIAL_RETRIES = 10; // Retries for partial success (throttling)

      private static final ClientPool<AwsOptions, ClientConfiguration, KinesisAsyncClient> CLIENTS =
          ClientPool.pooledClientFactory(KinesisAsyncClient.builder());

      protected final Write<T> spec;
      protected final Stats stats;
      protected final AsyncPutRecordsHandler handler;

      private final KinesisAsyncClient kinesis;
      private List<PutRecordsRequestEntry> requestEntries;
      private int requestBytes = 0;

      Writer(PipelineOptions options, Write<T> spec) {
        ClientConfiguration clientConfig = spec.clientConfiguration();
        RetryConfiguration retryConfig = clientConfig.retry();
        FluentBackoff backoff = FluentBackoff.DEFAULT.withMaxRetries(PARTIAL_RETRIES);
        if (retryConfig != null) {
          if (retryConfig.throttledBaseBackoff() != null) {
            backoff = backoff.withInitialBackoff(retryConfig.throttledBaseBackoff());
          }
          if (retryConfig.maxBackoff() != null) {
            backoff = backoff.withMaxBackoff(retryConfig.maxBackoff());
          }
        }
        this.spec = spec;
        this.stats = new Stats();
        this.kinesis = CLIENTS.retain(options.as(AwsOptions.class), clientConfig);
        this.handler =
            new AsyncPutRecordsHandler(kinesis, spec.concurrentRequests(), backoff, stats);
        this.requestEntries = new ArrayList<>();
      }

      public void startBundle() {
        handler.reset();
        requestEntries.clear();
        requestBytes = 0;
      }

      public final void write(T record) throws Throwable {
        handler.checkForAsyncFailure();
        stats.addUserRecord();
        byte[] data = spec.serializer().apply(record);
        String partitionKey = spec.partitioner().getPartitionKey(record);
        String hashKey = spec.partitioner().getExplicitHashKey(record);

        validatePartitionKey(partitionKey);
        if (hashKey != null) {
          validateExplicitHashKey(hashKey);
        }
        write(partitionKey, hashKey, data);
        stats.logPeriodically();
      }

      protected void write(String partitionKey, @Nullable String explicitHashKey, byte[] data)
          throws Throwable {
        PutRecordsRequestEntry.Builder entry =
            PutRecordsRequestEntry.builder()
                .data(SdkBytes.fromByteArrayUnsafe(data))
                .partitionKey(partitionKey);
        if (explicitHashKey != null) {
          entry.explicitHashKey(explicitHashKey);
        }
        addRequestEntry(entry.build());
        // flush once batch size limit is reached
        if (!hasCapacityForEntry(0)) {
          asyncFlushEntries();
        }
      }

      private int entrySizeBytes(PutRecordsRequestEntry e) {
        int bytes = e.partitionKey().getBytes(UTF_8).length + e.data().asByteArrayUnsafe().length;
        if (e.explicitHashKey() != null) {
          bytes += e.explicitHashKey().getBytes(UTF_8).length;
        }
        return bytes;
      }

      protected boolean hasCapacityForEntry(int entryBytes) {
        return requestEntries.size() < spec.batchMaxRecords()
            && requestBytes + entryBytes <= spec.batchMaxBytes();
      }

      protected int getRequestEntriesCount() {
        return requestEntries.size();
      }

      protected final void addRequestEntry(PutRecordsRequestEntry entry) throws Throwable {
        int entryBytes = entrySizeBytes(entry);
        // check first if new record can still be added to batch, flush otherwise
        if (!hasCapacityForEntry(entryBytes)) {
          asyncFlushEntries();
        }
        stats.addClientRecord(entryBytes);
        requestEntries.add(entry);
        requestBytes += entryBytes;
      }

      protected final void asyncFlushEntries() throws Throwable {
        if (!handler.hasErrored() && !requestEntries.isEmpty()) {
          // Swap lists, luckily no need to synchronize
          List<PutRecordsRequestEntry> recordsToWrite = requestEntries;
          requestEntries = new ArrayList<>();
          requestBytes = 0;
          handler.putRecords(spec.streamName(), recordsToWrite);
        }
      }

      public void finishBundle() throws Throwable {
        asyncFlushEntries();
        handler.waitForCompletion();
        stats.logPeriodically();
      }

      @Override
      public void close() throws Exception {
        CLIENTS.release(kinesis);
      }

      private void validatePartitionKey(String partitionKey) {
        int size = partitionKey != null ? partitionKey.length() : 0;
        checkState(
            PARTITION_KEY_MIN_LENGTH <= size && size <= PARTITION_KEY_MAX_LENGTH,
            "Invalid partition key of length {}",
            size);
      }

      private void validateExplicitHashKey(String hashKey) {
        BigInteger key = new BigInteger(hashKey);
        checkState(
            key.compareTo(KinesisPartitioner.MIN_HASH_KEY) >= 0
                && key.compareTo(KinesisPartitioner.MAX_HASH_KEY) <= 0,
            "Explicit hash key must be 128-bit number.");
      }
    }

    /**
     * Advanced Kinesis batch record writer that additionally aggregates user records of the same
     * effective hash key in a KPL/KCL compatible way.
     *
     * <p>https://docs.aws.amazon.com/streams/latest/dev/kinesis-kpl-concepts.html#kinesis-kpl-concepts-aggretation
     *
     * <p>The {@link RecordsAggregator} underneath relies on generated Protobuf classes distributed
     * with KCL to correctly implement the binary protocol, specifically {@link
     * software.amazon.kinesis.retrieval.kpl.Messages.AggregatedRecord}.
     *
     * <p>Note: The aggregation is a lot simpler than the one offered by KPL. While the KPL is aware
     * of effective hash key ranges assigned to each shard, we're not and don't want to be to keep
     * complexity manageable and avoid the risk of silently loosing records in the KCL:
     *
     * <p>{@link software.amazon.kinesis.retrieval.AggregatorUtil#deaggregate(List, BigInteger,
     * BigInteger)} drops records not matching the expected hash key range.
     */
    static class AggregatedWriter<T> extends Writer<T> {
      private static final Logger LOG = LoggerFactory.getLogger(AggregatedWriter.class);

      private final RecordAggregation aggSpec;
      private final Map<BigInteger, RecordsAggregator> aggregators;
      private final MessageDigest md5Digest;

      AggregatedWriter(PipelineOptions options, Write<T> spec, RecordAggregation aggSpec) {
        super(options, spec);
        this.aggSpec = aggSpec;
        this.aggregators = new LinkedHashMap<>();
        this.md5Digest = md5Digest();
      }

      @Override
      public void startBundle() {
        super.startBundle();
        aggregators.clear();
      }

      @Override
      protected void write(String partitionKey, @Nullable String explicitHashKey, byte[] data)
          throws Throwable {
        BigInteger hashKey = effectiveHashKey(partitionKey, explicitHashKey);
        RecordsAggregator agg = aggregators.computeIfAbsent(hashKey, k -> newRecordsAggregator());
        if (!agg.addRecord(partitionKey, explicitHashKey, data)) {
          // aggregated record too full, add a request entry and reset aggregator
          addRequestEntry(agg.getAndReset(aggregationTimeoutWithJitter()));
          aggregators.remove(hashKey);
          if (agg.addRecord(partitionKey, explicitHashKey, data)) {
            aggregators.put(hashKey, agg); // new aggregation started
          } else {
            super.write(partitionKey, explicitHashKey, data); // skip aggregation
          }
        } else if (!agg.hasCapacity()) {
          addRequestEntry(agg.get());
          aggregators.remove(hashKey);
        }

        // only check timeouts sporadically if concurrency is already maxed out
        if (handler.pendingRequests() < spec.concurrentRequests() || Math.random() < 0.05) {
          checkAggregationTimeouts();
        }
      }

      private RecordsAggregator newRecordsAggregator() {
        return new RecordsAggregator(
            Math.min(aggSpec.maxBytes(), spec.batchMaxBytes()), aggregationTimeoutWithJitter());
      }

      private Instant aggregationTimeoutWithJitter() {
        double millis =
            (1 - aggSpec.maxBufferedTimeJitter() + aggSpec.maxBufferedTimeJitter() * Math.random())
                * aggSpec.maxBufferedTime().getMillis();
        return Instant.ofEpochMilli(DateTimeUtils.currentTimeMillis() + (long) millis);
      }

      private void checkAggregationTimeouts() throws Throwable {
        Instant now = Instant.now();
        List<BigInteger> removals = new ArrayList<>();
        for (Map.Entry<BigInteger, RecordsAggregator> e : aggregators.entrySet()) {
          RecordsAggregator agg = e.getValue();
          // Timeouts with jitter are not in order, nevertheless this respects maxBufferedTime as
          // the map maintains insertion order
          if (agg.timeout().isAfter(now)) {
            break;
          }
          LOG.debug(
              "Adding aggregated entry after timeout [delay = {} ms]",
              now.getMillis() - agg.timeout().getMillis());
          addRequestEntry(agg.get());
          removals.add(e.getKey());
        }
        if (!removals.isEmpty()) {
          aggregators.keySet().removeAll(removals);
          asyncFlushEntries();
        }
      }

      @Override
      public void finishBundle() throws Throwable {
        for (RecordsAggregator aggregator : aggregators.values()) {
          addRequestEntry(aggregator.get());
        }
        super.finishBundle();
      }

      private BigInteger effectiveHashKey(String partitionKey, @Nullable String explicitHashKey) {
        return explicitHashKey == null
            ? new BigInteger(1, md5(partitionKey.getBytes(UTF_8)))
            : new BigInteger(explicitHashKey);
      }

      private byte[] md5(byte[] data) {
        byte[] hash = md5Digest.digest(data);
        md5Digest.reset();
        return hash;
      }

      private static MessageDigest md5Digest() {
        try {
          return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
          throw new RuntimeException(e);
        }
      }
    }

    private static class Stats implements AsyncPutRecordsHandler.Stats {
      private static final Logger LOG = LoggerFactory.getLogger(Stats.class);
      private static final Duration LOG_STATS_PERIOD = Duration.standardSeconds(10);

      private static final BinaryCombineLongFn MIN = Min.ofLongs();
      private static final BinaryCombineLongFn MAX = Max.ofLongs();
      private static final BinaryCombineLongFn SUM = Sum.ofLongs();

      private static final Duration MOVING_WINDOW = Duration.standardMinutes(3);
      private static final Duration UPDATE_PERIOD = Duration.standardSeconds(30);

      private static final String METRICS_PREFIX = "kinesis_io/write_";
      private static final Counter USER_RECORDS_COUNT =
          Metrics.counter(KinesisIO.Write.class, METRICS_PREFIX + "user_records_count");
      private static final Counter CLIENT_RECORDS_COUNT =
          Metrics.counter(KinesisIO.Write.class, METRICS_PREFIX + "client_records_count");
      private static final Distribution WRITE_LATENCY_MS =
          Metrics.distribution(KinesisIO.Write.class, METRICS_PREFIX + "latency_ms");

      private final MovingFunction numUserRecords = newFun(SUM);
      private final MovingFunction numClientRecords = newFun(SUM);
      private final MovingFunction minClientRecordBytes = newFun(MIN);
      private final MovingFunction maxClientRecordBytes = newFun(MAX);
      private final MovingFunction sumClientRecordBytes = newFun(SUM);

      private final MovingFunction numPutPartialRetries = newFun(SUM);

      private final MovingFunction numPutRequests = newFun(SUM);
      private final MovingFunction minPutRequestLatency = newFun(MIN);
      private final MovingFunction maxPutRequestLatency = newFun(MAX);
      private final MovingFunction sumPutRequestLatency = newFun(SUM);

      private long nextLogTime = DateTimeUtils.currentTimeMillis() + LOG_STATS_PERIOD.getMillis();

      private static MovingFunction newFun(BinaryCombineLongFn fn) {
        return new MovingFunction(MOVING_WINDOW.getMillis(), UPDATE_PERIOD.getMillis(), 1, 1, fn);
      }

      void addUserRecord() {
        USER_RECORDS_COUNT.inc();
        numUserRecords.add(DateTimeUtils.currentTimeMillis(), 1);
      }

      void addClientRecord(int recordBytes) {
        long timeMillis = DateTimeUtils.currentTimeMillis();
        CLIENT_RECORDS_COUNT.inc();
        numClientRecords.add(timeMillis, 1);
        minClientRecordBytes.add(timeMillis, recordBytes);
        maxClientRecordBytes.add(timeMillis, recordBytes);
        sumClientRecordBytes.add(timeMillis, recordBytes);
      }

      @Override
      public void addPutRecordsRequest(long latencyMillis, boolean isPartialRetry) {
        long timeMillis = DateTimeUtils.currentTimeMillis();
        numPutRequests.add(timeMillis, 1);
        if (isPartialRetry) {
          numPutPartialRetries.add(timeMillis, 1);
        }
        minPutRequestLatency.add(timeMillis, latencyMillis);
        maxPutRequestLatency.add(timeMillis, latencyMillis);
        sumPutRequestLatency.add(timeMillis, latencyMillis);
      }

      private void logPeriodically() {
        long now = DateTimeUtils.currentTimeMillis();
        // can't be updated from the async callback
        WRITE_LATENCY_MS.update(
            sumPutRequestLatency.get(now),
            numPutRequests.get(now),
            minPutRequestLatency.get(now),
            maxPutRequestLatency.get(now));
        if (now > nextLogTime && LOG.isInfoEnabled()) {
          nextLogTime = now + LOG_STATS_PERIOD.getMillis();
          long clientRecords = numClientRecords.get(now);
          long putRequests = numPutRequests.get(now);
          long putPartialRetries = numPutPartialRetries.get(now);

          LOG.info(
              "Kinesis put records stats [ batches={}, requests={}, partialRetryRatio={}\n"
                  + "  userRecords={}, clientRecords={}, avgClientRecordSize={} bytes, minClientRecordSize={} bytes, maxClientRecordSize={} bytes\n"
                  + "  avgRequestLatency={} ms, minRequestLatency={} ms, maxRequestLatency={}]",
              putRequests - putPartialRetries,
              putRequests,
              putRequests > 0 ? 1.0 * putPartialRetries / putRequests : 0,
              numUserRecords.get(now),
              clientRecords,
              clientRecords > 0 ? sumClientRecordBytes.get(now) / clientRecords : 0,
              minClientRecordBytes.get(now),
              maxClientRecordBytes.get(now),
              putRequests > 0 ? sumPutRequestLatency.get(now) / putRequests : 0,
              minPutRequestLatency.get(now),
              maxPutRequestLatency.get(now));
        }
      }
    }
  }
}
