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

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.reactivestreams.Publisher;
import redis.clients.redisson.RedissonSetCache;
import redis.clients.redisson.api.RFuture;
import redis.clients.redisson.api.RSetCacheReactive;
import redis.clients.redisson.client.codec.Codec;
import redis.clients.redisson.client.protocol.RedisCommands;
import redis.clients.redisson.client.protocol.decoder.ListScanResult;
import redis.clients.redisson.client.protocol.decoder.ScanObjectEntry;
import redis.clients.redisson.command.CommandReactiveExecutor;
import redis.clients.redisson.eviction.EvictionScheduler;

import io.netty.buffer.ByteBuf;
import reactor.fn.Supplier;

/**
 * <p>Set-based cache with ability to set TTL for each entry via
 * {@link #add(Object, long, TimeUnit)} method.
 * And therefore has an complex lua-scripts inside.
 * Uses map(value_hash, value) to tie with sorted set which contains expiration record for every value with TTL.
 * </p>
 *
 * <p>Current Redis implementation doesn't have set entry eviction functionality.
 * Thus values are checked for TTL expiration during any value read operation.
 * If entry expired then it doesn't returns and clean task runs hronous.
 * Clean task deletes removes 100 expired entries at once.
 * In addition there is {@link redis.clients.redisson.eviction.EvictionScheduler}. This scheduler
 * deletes expired entries in time interval between 5 seconds to 2 hours.</p>
 *
 * <p>If eviction is not required then it's better to use {@link redis.clients.redisson.api.RSet}.</p>
 *
 * @author Nikita Koksharov
 *
 * @param <V> value
 */
public class RedissonSetCacheReactive<V> extends RedissonExpirableReactive implements RSetCacheReactive<V> {

    private final RedissonSetCache<V> instance;
    
    public RedissonSetCacheReactive(EvictionScheduler evictionScheduler, CommandReactiveExecutor commandExecutor, String name) {
        super(commandExecutor, name);
        instance = new RedissonSetCache<V>(evictionScheduler, commandExecutor, name, null);
    }

    public RedissonSetCacheReactive(Codec codec, EvictionScheduler evictionScheduler, CommandReactiveExecutor commandExecutor, String name) {
        super(codec, commandExecutor, name);
        instance = new RedissonSetCache<V>(codec, evictionScheduler, commandExecutor, name, null);
    }

    @Override
    public Publisher<Integer> size() {
        return commandExecutor.readReactive(getName(), codec, RedisCommands.ZCARD_INT, getName());
    }

    @Override
    public Publisher<Boolean> contains(final Object o) {
        return reactive(new Supplier<RFuture<Boolean>>() {
            @Override
            public RFuture<Boolean> get() {
                return instance.containsAsync(o);
            }
        });
    }

    Publisher<ListScanResult<ScanObjectEntry>> scanIterator(final InetSocketAddress client, final long startPos) {
        return reactive(new Supplier<RFuture<ListScanResult<ScanObjectEntry>>>() {
            @Override
            public RFuture<ListScanResult<ScanObjectEntry>> get() {
                return instance.scanIteratorAsync(getName(), client, startPos, null);
            }
        });
    }

    @Override
    public Publisher<V> iterator() {
        return new SetReactiveIterator<V>() {
            @Override
            protected Publisher<ListScanResult<ScanObjectEntry>> scanIteratorReactive(InetSocketAddress client, long nextIterPos) {
                return RedissonSetCacheReactive.this.scanIterator(client, nextIterPos);
            }
        };
    }

    @Override
    public Publisher<Boolean> add(final V value, final long ttl, final TimeUnit unit) {
        return reactive(new Supplier<RFuture<Boolean>>() {
            @Override
            public RFuture<Boolean> get() {
                return instance.addAsync(value, ttl, unit);
            }
        });
    }

    @Override
    public Publisher<Integer> add(V value) {
        long timeoutDate = 92233720368547758L;
        return commandExecutor.evalWriteReactive(getName(), codec, RedisCommands.EVAL_INTEGER,
                "local expireDateScore = redis.call('zscore', KEYS[1], ARGV[3]); "
                + "if expireDateScore ~= false and tonumber(expireDateScore) > tonumber(ARGV[1]) then "
                    + "return 0;"
                + "end; " +
                "redis.call('zadd', KEYS[1], ARGV[2], ARGV[3]); " +
                "return 1; ",
                Arrays.<Object>asList(getName()), System.currentTimeMillis(), timeoutDate, encode(value));
    }

    @Override
    public Publisher<Boolean> remove(final Object o) {
        return reactive(new Supplier<RFuture<Boolean>>() {
            @Override
            public RFuture<Boolean> get() {
                return instance.removeAsync(o);
            }
        });
    }

    @Override
    public Publisher<Boolean> containsAll(final Collection<?> c) {
        return reactive(new Supplier<RFuture<Boolean>>() {
            @Override
            public RFuture<Boolean> get() {
                return instance.containsAllAsync(c);
            }
        });
    }

    @Override
    public Publisher<Integer> addAll(Collection<? extends V> c) {
        if (c.isEmpty()) {
            return newSucceeded(0);
        }

        long score = 92233720368547758L - System.currentTimeMillis();
        List<Object> params = new ArrayList<Object>(c.size()*2 + 1);
        params.add(getName());
        for (V value : c) {
            ByteBuf objectState = encode(value);
            params.add(score);
            params.add(objectState);
        }

        return commandExecutor.writeReactive(getName(), codec, RedisCommands.ZADD_RAW, params.toArray());
    }

    @Override
    public Publisher<Boolean> retainAll(final Collection<?> c) {
        return reactive(new Supplier<RFuture<Boolean>>() {
            @Override
            public RFuture<Boolean> get() {
                return instance.retainAllAsync(c);
            }
        });
    }

    @Override
    public Publisher<Boolean> removeAll(final Collection<?> c) {
        return reactive(new Supplier<RFuture<Boolean>>() {
            @Override
            public RFuture<Boolean> get() {
                return instance.removeAllAsync(c);
            }
        });
    }

    @Override
    public Publisher<Integer> addAll(Publisher<? extends V> c) {
        return new PublisherAdder<V>(this).addAll(c);
    }

}
