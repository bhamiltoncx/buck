/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.rules;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.ThrowableConsoleEvent;
import com.facebook.buck.util.FileHashCache;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.netflix.astyanax.AstyanaxContext;
import com.netflix.astyanax.Keyspace;
import com.netflix.astyanax.MutationBatch;
import com.netflix.astyanax.connectionpool.NodeDiscoveryType;
import com.netflix.astyanax.connectionpool.OperationResult;
import com.netflix.astyanax.connectionpool.exceptions.BadRequestException;
import com.netflix.astyanax.connectionpool.exceptions.ConnectionException;
import com.netflix.astyanax.connectionpool.impl.ConnectionPoolConfigurationImpl;
import com.netflix.astyanax.connectionpool.impl.CountingConnectionPoolMonitor;
import com.netflix.astyanax.impl.AstyanaxConfigurationImpl;
import com.netflix.astyanax.model.Column;
import com.netflix.astyanax.model.ColumnFamily;
import com.netflix.astyanax.model.ColumnList;
import com.netflix.astyanax.serializers.StringSerializer;
import com.netflix.astyanax.thrift.ThriftFamilyFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public class CassandraArtifactCache implements ArtifactCache {

  /**
   * If the user is offline, then we do not want to print every connection failure that occurs.
   * However, in practice, it appears that some connection failures can be intermittent, so we
   * should print enough to provide a signal of how flaky the connection is.
   */
  private static final int MAX_CONNECTION_FAILURE_REPORTS = 10;

  private static final String POOL_NAME = "ArtifactCachePool";
  private static final String CLUSTER_NAME = "BuckCacheCluster";
  private static final String KEYSPACE_NAME = "Buck";

  private static final String CONFIGURATION_COLUMN_FAMILY_NAME = "Configuration";
  private static final String CONFIGURATION_MAGIC_KEY = "magic";
  private static final String CONFIGURATION_MAGIC_VALUE = "Buck artifact cache";
  private static final String CONFIGURATION_TTL_KEY = "ttl";
  private static final String CONFIGURATION_COLUMN_NAME = "value";
  private static final ColumnFamily<String, String> CF_CONFIG = new ColumnFamily<String, String>(
      CONFIGURATION_COLUMN_FAMILY_NAME,
      StringSerializer.get(),
      StringSerializer.get());

  private static final String ARTIFACT_COLUMN_FAMILY_NAME = "Artifacts";
  private static final String ARTIFACT_COLUMN_NAME = "artifact";
  private static final ColumnFamily<String, String> CF_ARTIFACT = new ColumnFamily<String, String>(
      ARTIFACT_COLUMN_FAMILY_NAME,
      StringSerializer.get(),
      StringSerializer.get());
  private final AstyanaxContext<Keyspace> context;

  private static final class KeyspaceAndTtl {
    private final Keyspace keyspace;
    private final int ttl;

    private Keyspace getKeyspace() {
      return keyspace;
    }

    private int getTtl() {
      return ttl;
    }

    private KeyspaceAndTtl(Keyspace keyspace, int ttl) {
      this.keyspace = keyspace;
      this.ttl = ttl;
    }
  }

  private final int timeoutSeconds;
  private final Future<KeyspaceAndTtl> keyspaceAndTtlFuture;
  private final AtomicInteger numConnectionExceptionReports;
  private final String name;
  private final boolean doStore;
  private final BuckEventBus buckEventBus;
  private final FileHashCache fileHashCache;

  private final Set<ListenableFuture<OperationResult<Void>>> futures;
  private final AtomicBoolean isWaitingToClose;
  private final AtomicBoolean isKilled;

  public CassandraArtifactCache(
      String name,
      String hosts,
      int port,
      int timeoutSeconds,
      boolean doStore,
      BuckEventBus buckEventBus,
      FileHashCache fileHashCache)
      throws ConnectionException {
    this(
        name,
        timeoutSeconds,
        doStore,
        buckEventBus,
        fileHashCache,
        new AstyanaxContext.Builder()
            .forCluster(CLUSTER_NAME)
            .forKeyspace(KEYSPACE_NAME)
            .withAstyanaxConfiguration(new AstyanaxConfigurationImpl()
                    .setCqlVersion("3.0.0")
                    .setTargetCassandraVersion("1.2")
                    .setDiscoveryType(NodeDiscoveryType.RING_DESCRIBE))
            .withConnectionPoolConfiguration(new ConnectionPoolConfigurationImpl(POOL_NAME)
                    .setSeeds(hosts)
                    .setPort(port)
                    .setMaxConnsPerHost(1))
            .withConnectionPoolMonitor(new CountingConnectionPoolMonitor())
            .buildKeyspace(ThriftFamilyFactory.getInstance()));
  }

  @VisibleForTesting
  CassandraArtifactCache(
      String name,
      int timeoutSeconds,
      boolean doStore,
      BuckEventBus buckEventBus,
      FileHashCache fileHashCache,
      final AstyanaxContext<Keyspace> context) {
    this.name = name;
    this.doStore = doStore;
    this.buckEventBus = buckEventBus;
    this.fileHashCache = fileHashCache;
    this.numConnectionExceptionReports = new AtomicInteger(0);
    this.timeoutSeconds = timeoutSeconds;
    this.context = context;

    ExecutorService connectionService = MoreExecutors.getExitingExecutorService(
        (ThreadPoolExecutor) Executors.newFixedThreadPool(1), 0, TimeUnit.SECONDS);
    this.keyspaceAndTtlFuture = connectionService.submit(new Callable<KeyspaceAndTtl>() {
      @Override
      public KeyspaceAndTtl call() throws ConnectionException {
        context.start();
        Keyspace keyspace = context.getClient();
        try {
          verifyMagic(keyspace);
          int ttl = getTtl(keyspace);
          return new KeyspaceAndTtl(keyspace, ttl);
        } catch (ConnectionException e) {
          reportConnectionFailure("Attempting to get keyspace and ttl from server.", e);
          throw e;
        }
      }
    });

    this.futures = Sets.newSetFromMap(
        new ConcurrentHashMap<ListenableFuture<OperationResult<Void>>, Boolean>());
    this.isWaitingToClose = new AtomicBoolean(false);
    this.isKilled = new AtomicBoolean(false);
  }

  private static void verifyMagic(Keyspace keyspace) throws ConnectionException {
    OperationResult<ColumnList<String>> result;
    try {
      result = keyspace.prepareQuery(CF_CONFIG)
          .getKey(CONFIGURATION_MAGIC_KEY)
          .execute();
    } catch (BadRequestException e) {
      throw new HumanReadableException("Artifact cache error during schema verification: %s",
          e.getMessage());
    }
    Column<String> column = result.getResult().getColumnByName(CONFIGURATION_COLUMN_NAME);
    if (column == null || !column.getStringValue().equals(CONFIGURATION_MAGIC_VALUE)) {
      throw new HumanReadableException("Artifact cache schema mismatch");
    }
  }

  /**
   * @return The resulting keyspace and ttl of connecting to Cassandra if the connection succeeded,
   *    otherwise Optional.absent().  This method will block until connection finishes.
   */
  private Optional<KeyspaceAndTtl> getKeyspaceAndTtl()
      throws InterruptedException {
    if (isKilled.get()) {
      return Optional.absent();
    }
    try {
      return Optional.of(keyspaceAndTtlFuture.get(timeoutSeconds, TimeUnit.SECONDS));
    } catch (TimeoutException e) {
      keyspaceAndTtlFuture.cancel(true);
      isKilled.set(true);
    } catch (ExecutionException e) {
      if (!(e.getCause() instanceof ConnectionException)) {
        buckEventBus.post(
            ThrowableConsoleEvent.create(
                e,
                "Unexpected error when fetching keyspace and ttl: %s.",
                e.getMessage()));
      }
    } catch (CancellationException e) {
      return Optional.absent();
    }
    return Optional.absent();
  }

  private static int getTtl(Keyspace keyspace) throws ConnectionException {
    OperationResult<ColumnList<String>> result = keyspace.prepareQuery(CF_CONFIG)
        .getKey(CONFIGURATION_TTL_KEY)
        .execute();
    Column<String> column = result.getResult().getColumnByName(CONFIGURATION_COLUMN_NAME);
    if (column == null) {
      throw new HumanReadableException("Artifact cache schema malformation.");
    }
    try {
      return Integer.parseInt(column.getStringValue());
    } catch (NumberFormatException e) {
      throw new HumanReadableException("Artifact cache ttl malformation: \"%s\".",
          column.getStringValue());
    }
  }

  @Override
  public CacheResult fetch(RuleKey ruleKey, File output)
      throws InterruptedException {
    Optional<KeyspaceAndTtl> keyspaceAndTtl = getKeyspaceAndTtl();
    if (!keyspaceAndTtl.isPresent()) {
      // Connecting to Cassandra failed, return false
      return CacheResult.miss();
    }

    // Execute the query to Cassandra.
    OperationResult<ColumnList<String>> result;
    int ttl;
    try {
      Keyspace keyspace = keyspaceAndTtl.get().getKeyspace();
      ttl = keyspaceAndTtl.get().getTtl();

      result = keyspace.prepareQuery(CF_ARTIFACT)
          .getKey(ruleKey.toString())
          .execute();
    } catch (ConnectionException e) {
      reportConnectionFailure("Attempting to fetch " + ruleKey + ".", e);
      return CacheResult.miss();
    }

    CacheResult success = CacheResult.miss();
    try {
      Column<String> column = result.getResult().getColumnByName(ARTIFACT_COLUMN_NAME);
      if (column != null) {
        ByteArrayInputStream dataStream = new ByteArrayInputStream(column.getByteArrayValue());

        // Setup an object input stream to deserialize the hash code.
        try (ObjectInputStream objectStream = new ObjectInputStream(dataStream)) {

          // Deserialize the expected hash code object from the front of the artifact.
          HashCode expectedHashCode;
          try {
            expectedHashCode = (HashCode) objectStream.readObject();
          } catch (ClassNotFoundException | ClassCastException e) {
            buckEventBus.post(
                ThrowableConsoleEvent.create(
                    e,
                    "Could not deserialize artifact checksum from %s:%s.",
                    ruleKey,
                    output.getPath()));
            return CacheResult.miss();
          }

          // Write the contents to a temp file that sits next to the real destination.
          Path path = output.toPath();
          Files.createDirectories(path.getParent());
          Path temp = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
          Files.copy(dataStream, temp, StandardCopyOption.REPLACE_EXISTING);

          // Compare the embedded hash code with the one we calculated here.  If they don't match,
          // discard the output and report a mismatch event.
          HashCode actualHashCode = fileHashCache.get(temp);
          if (!expectedHashCode.equals(actualHashCode)) {
            buckEventBus.post(new CassandraChecksumMismatchEvent(expectedHashCode, actualHashCode));
            Files.delete(temp);
            return CacheResult.miss();
          }

          // Finally, move the temp file into it's final place.
          Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);

        }

        // Cassandra timestamps use microsecond resolution.
        if (System.currentTimeMillis() * 1000L - column.getTimestamp() > ttl * 1000000L / 2L) {
          // The cache entry has lived for more than half of its total TTL, so rewrite it in order
          // to reset the TTL.
          store(ruleKey, output);
        }
        success = CacheResult.hit(name);
      }
    } catch (IOException e) {
      buckEventBus.post(ThrowableConsoleEvent.create(e,
          "Artifact was fetched but could not be written: %s at %s.",
          ruleKey,
          output.getPath()));
    }

    buckEventBus.post(ConsoleEvent.fine("Artifact fetch(%s, %s) cache %s",
        ruleKey,
        output.getPath(),
        (success.getType().isSuccess() ? "hit" : "miss")));
    return success;
  }

  @Override
  public void store(RuleKey ruleKey, File output) throws InterruptedException {
    if (!isStoreSupported()) {
      return;
    }

    Optional<KeyspaceAndTtl> keyspaceAndTtl = getKeyspaceAndTtl();
    if (!keyspaceAndTtl.isPresent()) {
      return;
    }
    try {

      // Prepare a byte stream to stage the data we're storing.
      ByteArrayOutputStream dataStream = new ByteArrayOutputStream();

      // Setup an object output stream wrapper to serialize the hash code.
      try (ObjectOutputStream objectStream = new ObjectOutputStream(dataStream)) {

        // Store the hash code at the beginning of the data we're storing.
        HashCode hashCode = fileHashCache.get(output.toPath());
        try {
          objectStream.writeObject(hashCode);
          objectStream.flush();
        } catch (NotSerializableException e) {
          buckEventBus.post(
              ThrowableConsoleEvent.create(
                  e,
                  "Artifact store(%s, %s) error: %s",
                  ruleKey,
                  output.getPath()));
          return;
        }

        // The rest of the data is the contents of the artifact.
        Files.copy(output.toPath(), dataStream);

        Keyspace keyspace = keyspaceAndTtl.get().getKeyspace();
        int ttl = keyspaceAndTtl.get().getTtl();
        MutationBatch mutationBatch = keyspace.prepareMutationBatch();
        mutationBatch.withRow(CF_ARTIFACT, ruleKey.toString())
            .setDefaultTtl(ttl)
            .putColumn(ARTIFACT_COLUMN_NAME, dataStream.toByteArray());
        ListenableFuture<OperationResult<Void>> mutationFuture = mutationBatch.executeAsync();
        trackFuture(mutationFuture);

      }
    } catch (ConnectionException e) {
      reportConnectionFailure("Attempting to store " + ruleKey + ".", e);
    } catch (IOException | OutOfMemoryError e) {
      buckEventBus.post(ThrowableConsoleEvent.create(e,
          "Artifact store(%s, %s) error: %s",
          ruleKey,
          output.getPath()));
    }
  }

  private void trackFuture(final ListenableFuture<OperationResult<Void>> future) {
    futures.add(future);
    Futures.addCallback(future, new FutureCallback<OperationResult<Void>>() {
      @Override
      public void onSuccess(OperationResult<Void> result) {
        removeFuture();
      }

      @Override
      public void onFailure(Throwable t) {
        removeFuture();
      }

      private void removeFuture() {
        if (!isWaitingToClose.get()) {
          futures.remove(future);
        }
      }
    });
  }

  @Override
  @SuppressWarnings("PMD.EmptyCatchBlock")
  public void close() {
    isWaitingToClose.set(true);
    ListenableFuture<List<OperationResult<Void>>> future = Futures.allAsList(futures);
    try {
      future.get();
    } catch (ExecutionException e) {
      // Swallow exception and move on.
    } catch (InterruptedException e) {
      try {
        future.cancel(true);
      } catch (CancellationException ignored) {
        // ListenableFuture may throw when its future is cancelled.
      }
      Thread.currentThread().interrupt();
      return;
    } finally {
      context.shutdown();
    }
  }

  @Override
  public boolean isStoreSupported() {
    return doStore;
  }

  private void reportConnectionFailure(String context, ConnectionException exception) {
    if (numConnectionExceptionReports.incrementAndGet() < MAX_CONNECTION_FAILURE_REPORTS) {
      buckEventBus.post(new CassandraConnectionExceptionEvent(
              exception,
              String.format(
                  "%s Connecting to cassandra failed: %s.",
                  context,
                  exception.getMessage())));
    }
  }

  public static class CassandraConnectionExceptionEvent extends ThrowableConsoleEvent {

    public CassandraConnectionExceptionEvent(Throwable throwable, String message) {
      super(throwable, Level.WARNING, message);
    }
  }

  public static class CassandraChecksumMismatchEvent extends AbstractBuckEvent {

    private final HashCode expected;
    private final HashCode actual;

    public CassandraChecksumMismatchEvent(HashCode expected, HashCode actual) {
      this.expected = expected;
      this.actual = actual;
    }

    @Override
    protected String getValueString() {
      return String.format(
          "Checksum mismatch: %s (expected) != %s (actual)",
          expected,
          actual);
    }

    @Override
    public boolean isRelatedTo(BuckEvent event) {
      return false;
    }

    @Override
    public String getEventName() {
      return "CassandraChecksumMismatchEvent";
    }

    public HashCode getExpected() {
      return expected;
    }

    public HashCode getActual() {
      return actual;
    }

  }

}
