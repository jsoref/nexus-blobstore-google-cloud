/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2017-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.blobstore.gcloud.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import org.sonatype.nexus.blobstore.BlobIdLocationResolver;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;
import org.sonatype.nexus.scheduling.PeriodicJobService;
import org.sonatype.nexus.scheduling.PeriodicJobService.PeriodicJob;

import com.google.cloud.datastore.Datastore;
import com.google.cloud.datastore.Entity;
import com.google.cloud.datastore.FullEntity;
import com.google.cloud.datastore.Key;
import com.google.cloud.datastore.KeyFactory;
import com.google.cloud.datastore.LongValue;
import com.google.cloud.datastore.PathElement;
import com.google.cloud.datastore.ProjectionEntity;
import com.google.cloud.datastore.Query;
import com.google.cloud.datastore.QueryResults;
import com.google.cloud.datastore.StructuredQuery.PropertyFilter;
import com.google.cloud.datastore.Transaction;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.util.concurrent.RateLimiter;
import com.google.datastore.v1.TransactionOptions;
import com.google.datastore.v1.TransactionOptions.ReadOnly;
import org.apache.commons.lang.StringUtils;

import static java.lang.String.format;
import static org.sonatype.nexus.blobstore.gcloud.internal.DatastoreKeyHierarchy.NXRM_ROOT;

/**
 * This duck type of NXRM blobstore MetricsStores intends to achieve cost efficient eventual consistency
 * for {@link #getMetrics()}.
 *
 * This implementation is backed by Google Cloud {@link Datastore} and is inspired by their Sharded Counter
 * Best Practice (insert link here).
 *
 * The counts and sizes are sharded along the following key hierarchy:
 *
 *  <pre>
 *  [namespace: /BlobStoreConfiguration.getName()/]
 *  kind=Sonatype,name=Nexus Repository Manager
 *  --> kind=MetricsStore
 *  ------> kind=Shard,name=vol-01 [size=2048,count=2]
 *  ------> kind=Shard,name=vol-02 [size=0,count=0]
 *  ------> kind=Shard,name=vol-03 [size=123456,count=11]
 *  ...
 *  </pre>
 *
 * Writing to these Shards synchronously for each {@link #recordAddition(BlobId, long)} and
 * {@link #recordDeletion(BlobId, long)} would cause contention, even exceeding Google Cloud Datastore's recommendation
 * for concurrent writes.
 *
 * Internally, this class maintains a queue of deltas to apply, and only writes them out no higher than once per second,
 * in batches if there is more than one delta queued.
 *
 * This falls within Google Cloud's best practices for use, and results in a cheap and efficient way to store
 * blobstore metrics with high accuracy.
 */
public class ShardedCounterMetricsStore
    extends StateGuardLifecycleSupport
{
  private static final int FLUSH_FREQUENCY_IN_SECONDS = 5;

  private static final String METRICS_STORE = "MetricsStore";

  static final String SHARD = "MetricsStoreShard";

  private static final String COUNT = "count";

  private static final String SIZE = "size";

  private final BlobIdLocationResolver locationResolver;

  private final GoogleCloudDatastoreFactory datastoreFactory;

  private final PeriodicJobService periodicJobService;

  private Datastore datastore;

  private String blobStoreInstanceName;

  private Key shardRoot;

  private Queue<Mutation> pending = new ConcurrentLinkedDeque<>();

  private RateLimiter rateLimiter = RateLimiter.create(1);

  private PeriodicJob flushJob;

  /**
   * @param locationResolver
   * @param datastoreFactory
   * @param periodicJobService
   */
  public ShardedCounterMetricsStore(final BlobIdLocationResolver locationResolver,
                                    final GoogleCloudDatastoreFactory datastoreFactory,
                                    final PeriodicJobService periodicJobService) {
    this.locationResolver = locationResolver;
    this.datastoreFactory = datastoreFactory;
    this.periodicJobService = periodicJobService;
  }

  public void init(final BlobStoreConfiguration configuration) throws Exception {
    this.datastore = datastoreFactory.create(configuration);
    this.blobStoreInstanceName = configuration.getName();

    this.shardRoot = datastore.newKeyFactory()
        .addAncestors(NXRM_ROOT)
        .setNamespace(blobStoreInstanceName)
        .setKind(METRICS_STORE)
        .newKey(1L);

    this.flushJob = periodicJobService.schedule(() -> flush(), FLUSH_FREQUENCY_IN_SECONDS);
  }

  @Override
  public void doStop() {
    flushJob.cancel();
    // flush the pending queue
    if (rateLimiter.tryAcquire(2L, TimeUnit.SECONDS)) {
      flush();
    } else if (!pending.isEmpty()){
      log.error("unable to flush pending metrics data, queue contents will not be written to datastore: {}", pending);
    }
  }

  void removeData() {
    log.warn("removing all Blobstore metrics data from datastore...");
    StreamSupport.stream(Spliterators.spliteratorUnknownSize(getShards(), Spliterator.ORDERED), false)
        .map(entity -> entity.getKey())
        .forEach( shard -> datastore.delete(shard));
    log.warn("Blobstore metrics data removed");
  }
  public void recordDeletion(final BlobId blobId, final long size) {
    String shard = getShardLocation(blobId);
    pending.add(new Mutation(shard, -size, -1L));
  }

  public void recordAddition(final BlobId blobId, final long size) {
    String shard = getShardLocation(blobId);
    pending.add(new Mutation(shard, size, 1L));
  }

  /**
   * @return a {@link BlobStoreMetrics} containing the sums of size and count across all shards
   */
  public BlobStoreMetrics getMetrics() {
    Long count = getCount(COUNT);
    Long size = getCount(SIZE);

    // TODO consider merge with values in pending queue
    return new GoogleBlobStoreMetrics(count, size);
  }

  private Long getCount(String fieldName) {
    Transaction txn = datastore.newTransaction(
        TransactionOptions.newBuilder()
            .setReadOnly(ReadOnly.newBuilder().build())
            .build()
    );

    QueryResults<ProjectionEntity> results;
    try {
      Query<ProjectionEntity> countQuery = Query.newProjectionEntityQueryBuilder()
          .setKind(SHARD)
          .setNamespace(blobStoreInstanceName)
          .setProjection(fieldName)
          .build();

      results = datastore.run(countQuery);
      return StreamSupport.stream(Spliterators.spliteratorUnknownSize(results, Spliterator.NONNULL), false)
          .map(entity -> Long.valueOf(entity.getLong(fieldName)))
          .reduce(0L, (valueA, valueB) -> valueA + valueB);
    } finally {
      if (txn.isActive()) {
        txn.rollback();
      }
    }
  }

  Entity getShardCounter(final String location) {
    KeyFactory keyFactory = datastore.newKeyFactory().addAncestors(
        NXRM_ROOT,
        PathElement.of(METRICS_STORE, 1L)
    );
    Key key = keyFactory.setNamespace(blobStoreInstanceName)
        .setKind(SHARD).newKey(location);
    Entity exists = datastore.get(key);
    if (exists != null) {
      log.debug("counter for {} already present", location);
      return exists;
    }

    log.debug("creating counter for {}", location);
    // otherwise make it
    Entity entity = Entity.newBuilder(key)
        .set(SIZE, LongValue.newBuilder(0L).build())
        .set(COUNT, LongValue.newBuilder(0L).build())
        .build();

    return datastore.put(entity);
  }

  String getShardLocation(final BlobId blobId) {
    String location = locationResolver.getLocation(blobId);
    if (!location.contains("/")) {
      throw new IllegalArgumentException(
          format("unexpected BlobId LocationStrategy; %s does not contain a '/'", blobId));
    }
    return StringUtils.split(location, "/")[0];
  }

  QueryResults<Entity> getShards() {
    Query<Entity> shardQuery = Query.newEntityQueryBuilder()
        .setFilter(PropertyFilter.hasAncestor(shardRoot))
        .setNamespace(blobStoreInstanceName)
        .setKind(SHARD)
        .build();

    return datastore.run(shardQuery);
  }

  void flush() {
    if (!pending.isEmpty()) {
      log.debug("flush started, attempting to acquire permit");
      double wait = rateLimiter.acquire();
      log.debug("permit acquired after {} seconds", wait);
      Multimap<String, Mutation> toWrite = ArrayListMultimap.create();

      // drain the queue into a Map<shard, list<mutations_for_the_shard>>
      Mutation queued = pending.poll();
      while(queued != null) {
        toWrite.put(queued.getShard(), queued);
        queued = pending.poll();
      }

      // merge multimap of mutations into a list of single entities per shard to write as a batch
      List<FullEntity> list = new ArrayList<>();
      for (String shard: toWrite.keySet()) {
        Collection<Mutation> deltas = toWrite.get(shard);
        deltas.stream().reduce((deltaA, deltaB) ->
            new Mutation(shard,
                deltaA.getSizeDelta() + deltaB.getSizeDelta(),
                deltaA.getCountDelta() + deltaB.getCountDelta())
        ).ifPresent(merged -> {
          Entity shardCounter = getShardCounter(merged.getShard());
          FullEntity<Key> entity = FullEntity.newBuilder(shardCounter.getKey())
              .set(SIZE, shardCounter.getLong(SIZE) + merged.getSizeDelta())
              .set(COUNT, shardCounter.getLong(COUNT) + merged.getCountDelta())
              .build();
          list.add(entity);
        });
      }
      log.debug("sending {} mutations to datastore", list.size());
      // write the batch off to datastore
      if (!list.isEmpty()) {
        Transaction txn = datastore.newTransaction();
        try {
          txn.put(list.toArray(new FullEntity[list.size()]));
          txn.commit();
        } finally {
          if (txn.isActive()) {
            txn.rollback();
          }
        }
        log.debug("drained {} mutations to datastore", list.size());
      }
    }
  }

  class GoogleBlobStoreMetrics
      implements BlobStoreMetrics
  {

    private final long blobCount;

    private final long totalSize;

    GoogleBlobStoreMetrics(final long blobCount, final long totalSize) {
      this.blobCount = blobCount;
      this.totalSize = totalSize;
    }

    @Override
    public long getBlobCount() {
      return this.blobCount;
    }

    @Override
    public long getTotalSize() {
      return this.totalSize;
    }

    @Override
    public final long getAvailableSpace() {
      return 0;
    }

    @Override
    public final boolean isUnlimited() {
      return true;
    }

    @Override
    public final Map<String, Long> getAvailableSpaceByFileStore() {
      return Collections.emptyMap();
    }

    @Override
    public String toString() {
      return "GoogleBlobStoreMetrics{" +
          "blobCount=" + blobCount +
          ", totalSize=" + totalSize +
          '}';
    }
  }

  class Mutation {
    private final String shard;
    private final long sizeDelta;
    private final long countDelta;

    Mutation(final String shard, final long sizeDelta, final long countDelta) {
      this.shard = shard;
      this.sizeDelta = sizeDelta;
      this.countDelta = countDelta;
    }

    public String getShard() {
      return shard;
    }

    public long getSizeDelta() {
      return sizeDelta;
    }

    public long getCountDelta() {
      return countDelta;
    }

    @Override
    public String toString() {
      return "Mutation{" +
          "shard='" + shard + '\'' +
          ", sizeDelta=" + sizeDelta +
          ", countDelta=" + countDelta +
          '}';
    }
  }
}
