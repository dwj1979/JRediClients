/**
 * Copyright 2016 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package redis.clients.redisson.reactive;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.reactivestreams.Publisher;

import redis.clients.redisson.api.BatchResult;
import redis.clients.redisson.api.RAtomicLongReactive;
import redis.clients.redisson.api.RBatchReactive;
import redis.clients.redisson.api.RBitSetReactive;
import redis.clients.redisson.api.RBlockingQueueReactive;
import redis.clients.redisson.api.RBucketReactive;
import redis.clients.redisson.api.RDequeReactive;
import redis.clients.redisson.api.RFuture;
import redis.clients.redisson.api.RHyperLogLogReactive;
import redis.clients.redisson.api.RKeysReactive;
import redis.clients.redisson.api.RLexSortedSetReactive;
import redis.clients.redisson.api.RListReactive;
import redis.clients.redisson.api.RMapCacheReactive;
import redis.clients.redisson.api.RMapReactive;
import redis.clients.redisson.api.RQueueReactive;
import redis.clients.redisson.api.RScoredSortedSetReactive;
import redis.clients.redisson.api.RScriptReactive;
import redis.clients.redisson.api.RSetCacheReactive;
import redis.clients.redisson.api.RSetReactive;
import redis.clients.redisson.api.RTopicReactive;
import redis.clients.redisson.api.RedissonReactiveClient;
import redis.clients.redisson.client.codec.Codec;
import redis.clients.redisson.command.CommandReactiveBatchService;
import redis.clients.redisson.connection.ConnectionManager;
import redis.clients.redisson.eviction.EvictionScheduler;

import reactor.fn.Supplier;

/**
 * 
 * @author Nikita Koksharov
 *
 */
public class RedissonBatchReactive implements RBatchReactive {

    private final EvictionScheduler evictionScheduler;
    private final CommandReactiveBatchService executorService;

    private long timeout;
    private int retryAttempts;
    private long retryInterval;

    private int syncSlaves;
    private long syncTimeout;
    private boolean skipResult;
    
    public RedissonBatchReactive(EvictionScheduler evictionScheduler, ConnectionManager connectionManager) {
        this.evictionScheduler = evictionScheduler;
        this.executorService = new CommandReactiveBatchService(connectionManager);
    }

    @Override
    public <V> RBucketReactive<V> getBucket(String name) {
        return new RedissonBucketReactive<V>(executorService, name);
    }

    @Override
    public <V> RBucketReactive<V> getBucket(String name, Codec codec) {
        return new RedissonBucketReactive<V>(codec, executorService, name);
    }

    @Override
    public <V> RHyperLogLogReactive<V> getHyperLogLog(String name) {
        return new RedissonHyperLogLogReactive<V>(executorService, name);
    }

    @Override
    public <V> RHyperLogLogReactive<V> getHyperLogLog(String name, Codec codec) {
        return new RedissonHyperLogLogReactive<V>(codec, executorService, name);
    }

    @Override
    public <V> RListReactive<V> getList(String name) {
        return new RedissonListReactive<V>(executorService, name);
    }

    @Override
    public <V> RListReactive<V> getList(String name, Codec codec) {
        return new RedissonListReactive<V>(codec, executorService, name);
    }

    @Override
    public <K, V> RMapReactive<K, V> getMap(String name) {
        return new RedissonMapReactive<K, V>(executorService, name, null);
    }

    @Override
    public <K, V> RMapReactive<K, V> getMap(String name, Codec codec) {
        return new RedissonMapReactive<K, V>(codec, executorService, name, null);
    }

    @Override
    public <K, V> RMapCacheReactive<K, V> getMapCache(String name, Codec codec) {
        return new RedissonMapCacheReactive<K, V>(evictionScheduler, codec, executorService, name, null);
    }

    @Override
    public <K, V> RMapCacheReactive<K, V> getMapCache(String name) {
        return new RedissonMapCacheReactive<K, V>(evictionScheduler, executorService, name, null);
    }

    @Override
    public <V> RSetReactive<V> getSet(String name) {
        return new RedissonSetReactive<V>(executorService, name);
    }

    @Override
    public <V> RSetReactive<V> getSet(String name, Codec codec) {
        return new RedissonSetReactive<V>(codec, executorService, name);
    }

    @Override
    public <M> RTopicReactive<M> getTopic(String name) {
        return new RedissonTopicReactive<M>(executorService, name);
    }

    @Override
    public <M> RTopicReactive<M> getTopic(String name, Codec codec) {
        return new RedissonTopicReactive<M>(codec, executorService, name);
    }

    @Override
    public <V> RQueueReactive<V> getQueue(String name) {
        return new RedissonQueueReactive<V>(executorService, name);
    }

    @Override
    public <V> RQueueReactive<V> getQueue(String name, Codec codec) {
        return new RedissonQueueReactive<V>(codec, executorService, name);
    }

    @Override
    public <V> RBlockingQueueReactive<V> getBlockingQueue(String name) {
        return new RedissonBlockingQueueReactive<V>(executorService, name);
    }

    @Override
    public <V> RBlockingQueueReactive<V> getBlockingQueue(String name, Codec codec) {
        return new RedissonBlockingQueueReactive<V>(codec, executorService, name);
    }

    @Override
    public <V> RDequeReactive<V> getDequeReactive(String name) {
        return new RedissonDequeReactive<V>(executorService, name);
    }

    @Override
    public <V> RDequeReactive<V> getDequeReactive(String name, Codec codec) {
        return new RedissonDequeReactive<V>(codec, executorService, name);
    }

    @Override
    public RAtomicLongReactive getAtomicLongReactive(String name) {
        return new RedissonAtomicLongReactive(executorService, name);
    }

    @Override
    public <V> RSetCacheReactive<V> getSetCache(String name) {
        return new RedissonSetCacheReactive<V>(evictionScheduler, executorService, name);
    }

    @Override
    public <V> RSetCacheReactive<V> getSetCache(String name, Codec codec) {
        return new RedissonSetCacheReactive<V>(codec, evictionScheduler, executorService, name);
    }

    @Override
    public <V> RScoredSortedSetReactive<V> getScoredSortedSet(String name) {
        return new RedissonScoredSortedSetReactive<V>(executorService, name);
    }

    @Override
    public <V> RScoredSortedSetReactive<V> getScoredSortedSet(String name, Codec codec) {
        return new RedissonScoredSortedSetReactive<V>(codec, executorService, name);
    }

    @Override
    public RLexSortedSetReactive getLexSortedSet(String name) {
        return new RedissonLexSortedSetReactive(executorService, name);
    }

    @Override
    public RBitSetReactive getBitSet(String name) {
        return new RedissonBitSetReactive(executorService, name);
    }

    @Override
    public RScriptReactive getScript() {
        return new RedissonScriptReactive(executorService);
    }

    @Override
    public RKeysReactive getKeys() {
        return new RedissonKeysReactive(executorService);
    }

    @Override
    public Publisher<BatchResult<?>> execute() {
        return new NettyFuturePublisher<BatchResult<?>>(new Supplier<RFuture<BatchResult<?>>>() {
            @Override
            public RFuture<BatchResult<?>> get() {
                return executorService.executeAsync(syncSlaves, syncTimeout, skipResult, timeout, retryAttempts, retryInterval);
            }
        });
    }
    
    @Override
    public RBatchReactive syncSlaves(int slaves, long timeout, TimeUnit unit) {
        this.syncSlaves = slaves;
        this.syncTimeout = unit.toMillis(timeout);
        return this;
    }
    
    @Override
    public RBatchReactive skipResult() {
        this.skipResult = true;
        return this;
    }
    
    @Override
    public RBatchReactive retryAttempts(int retryAttempts) {
        this.retryAttempts = retryAttempts;
        return this;
    }
    
    @Override
    public RBatchReactive retryInterval(long retryInterval, TimeUnit unit) {
        this.retryInterval = unit.toMillis(retryInterval);
        return this;
    }
    
    @Override
    public RBatchReactive timeout(long timeout, TimeUnit unit) {
        this.timeout = unit.toMillis(timeout);
        return this;
    }

    public void enableRedissonReferenceSupport(RedissonReactiveClient redissonReactive) {
        this.executorService.enableRedissonReferenceSupport(redissonReactive);
    }

}
