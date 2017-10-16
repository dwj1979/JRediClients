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
package redis.clients.redisson;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import redis.clients.redisson.api.RFuture;
import redis.clients.redisson.api.RSetCache;
import redis.clients.redisson.api.RedissonClient;
import redis.clients.redisson.api.mapreduce.RCollectionMapReduce;
import redis.clients.redisson.client.codec.Codec;
import redis.clients.redisson.client.codec.ScanCodec;
import redis.clients.redisson.client.protocol.RedisCommand;
import redis.clients.redisson.client.protocol.RedisCommand.ValueType;
import redis.clients.redisson.client.protocol.RedisCommands;
import redis.clients.redisson.client.protocol.RedisStrictCommand;
import redis.clients.redisson.client.protocol.convertor.BooleanReplayConvertor;
import redis.clients.redisson.client.protocol.decoder.ListScanResult;
import redis.clients.redisson.client.protocol.decoder.ScanObjectEntry;
import redis.clients.redisson.command.CommandAsyncExecutor;
import redis.clients.redisson.eviction.EvictionScheduler;
import redis.clients.redisson.mapreduce.RedissonCollectionMapReduce;

import io.netty.buffer.ByteBuf;

/**
 * <p>Set-based cache with ability to set TTL for each entry via
 * {@link RSetCache#add(Object, long, TimeUnit)} method.
 * </p>
 *
 * <p>Current Redis implementation doesn't have set entry eviction functionality.
 * Thus values are checked for TTL expiration during any value read operation.
 * If entry expired then it doesn't returns and clean task runs asynchronous.
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
public class RedissonSetCache<V> extends RedissonExpirable implements RSetCache<V>, ScanIterator {

    RedissonClient redisson;
    
    public RedissonSetCache(EvictionScheduler evictionScheduler, CommandAsyncExecutor commandExecutor, String name, RedissonClient redisson) {
        super(commandExecutor, name);
        if (evictionScheduler != null) {
            evictionScheduler.schedule(getName(), 0);
        }
        this.redisson = redisson;
    }

    public RedissonSetCache(Codec codec, EvictionScheduler evictionScheduler, CommandAsyncExecutor commandExecutor, String name, RedissonClient redisson) {
        super(codec, commandExecutor, name);
        if (evictionScheduler != null) {
            evictionScheduler.schedule(getName(), 0);
        }
        this.redisson = redisson;
    }
    
    @Override
    public <KOut, VOut> RCollectionMapReduce<V, KOut, VOut> mapReduce() {
        return new RedissonCollectionMapReduce<V, KOut, VOut>(this, redisson, commandExecutor.getConnectionManager());
    }

    @Override
    public int size() {
        return get(sizeAsync());
    }

    @Override
    public RFuture<Integer> sizeAsync() {
        return commandExecutor.readAsync(getName(), codec, RedisCommands.ZCARD_INT, getName());
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public boolean contains(Object o) {
        return get(containsAsync(o));
    }

    @Override
    public RFuture<Boolean> containsAsync(Object o) {
        return commandExecutor.evalReadAsync(getName(o), codec, RedisCommands.EVAL_BOOLEAN,
                    "local expireDateScore = redis.call('zscore', KEYS[1], ARGV[2]); " + 
                     "if expireDateScore ~= false then " +
                         "if tonumber(expireDateScore) <= tonumber(ARGV[1]) then " +
                             "return 0;" + 
                         "else " +
                             "return 1;" +
                         "end;" +
                     "else " +
                         "return 0;" +
                     "end; ",
               Arrays.<Object>asList(getName(o)), System.currentTimeMillis(), encode(o));
    }

    public ListScanResult<ScanObjectEntry> scanIterator(String name, InetSocketAddress client, long startPos, String pattern) {
        RFuture<ListScanResult<ScanObjectEntry>> f = scanIteratorAsync(name, client, startPos, pattern);
        return get(f);
    }

    public RFuture<ListScanResult<ScanObjectEntry>> scanIteratorAsync(String name, InetSocketAddress client, long startPos, String pattern) {
        List<Object> params = new ArrayList<Object>();
        params.add(startPos);
        params.add(System.currentTimeMillis());
        if (pattern != null) {
            params.add(pattern);
        }
        
        return commandExecutor.evalReadAsync(client, name, new ScanCodec(codec), RedisCommands.EVAL_ZSCAN,
                  "local result = {}; "
                + "local res; "
                + "if (#ARGV == 3) then "
                  + " res = redis.call('zscan', KEYS[1], ARGV[1], 'match', ARGV[3]); "
                + "else "
                  + " res = redis.call('zscan', KEYS[1], ARGV[1]); "
                + "end;"
                + "for i, value in ipairs(res[2]) do "
                    + "if i % 2 == 0 then "
                        + "local expireDate = value; "
                        + "if tonumber(expireDate) > tonumber(ARGV[2]) then "
                            + "table.insert(result, res[2][i-1]); "
                        + "end; "
                    + "end;"
                + "end;"
                + "return {res[1], result};", Arrays.<Object>asList(name), params.toArray());
    }

    @Override
    public Iterator<V> iterator(final String pattern) {
        return new RedissonBaseIterator<V>() {

            @Override
            ListScanResult<ScanObjectEntry> iterator(InetSocketAddress client, long nextIterPos) {
                return scanIterator(getName(), client, nextIterPos, pattern);
            }

            @Override
            void remove(V value) {
                RedissonSetCache.this.remove(value);
            }
            
        };
    }
    
    @Override
    public Iterator<V> iterator() {
        return iterator(null);
    }

    @Override
    public Set<V> readAll() {
        return get(readAllAsync());
    }

    @Override
    public RFuture<Set<V>> readAllAsync() {
        return commandExecutor.readAsync(getName(), codec, RedisCommands.ZRANGEBYSCORE, getName(), System.currentTimeMillis(), 92233720368547758L);
    }

    @Override
    public Object[] toArray() {
        Set<V> res = get(readAllAsync());
        return res.toArray();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        Set<V> res = get(readAllAsync());
        return res.toArray(a);
    }

    @Override
    public boolean add(V e) {
        return get(addAsync(e));
    }

    @Override
    public boolean add(V value, long ttl, TimeUnit unit) {
        return get(addAsync(value, ttl, unit));
    }

    @Override
    public RFuture<Boolean> addAsync(V value, long ttl, TimeUnit unit) {
        if (ttl < 0) {
            throw new IllegalArgumentException("TTL can't be negative");
        }
        if (ttl == 0) {
            return addAsync(value);
        }

        if (unit == null) {
            throw new NullPointerException("TimeUnit param can't be null");
        }

        ByteBuf objectState = encode(value);

        long timeoutDate = System.currentTimeMillis() + unit.toMillis(ttl);
        return commandExecutor.evalWriteAsync(getName(value), codec, RedisCommands.EVAL_BOOLEAN,
                "local expireDateScore = redis.call('zscore', KEYS[1], ARGV[3]); " +
                "redis.call('zadd', KEYS[1], ARGV[2], ARGV[3]); " +
                "if expireDateScore ~= false and tonumber(expireDateScore) > tonumber(ARGV[1]) then " +
                    "return 0;" +
                "end; " +
                "return 1; ",
                Arrays.<Object>asList(getName(value)), System.currentTimeMillis(), timeoutDate, objectState);
    }

    @Override
    public RFuture<Boolean> addAsync(V value) {
        return addAsync(value, 92233720368547758L - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public RFuture<Boolean> removeAsync(Object o) {
        return commandExecutor.writeAsync(getName(o), codec, RedisCommands.ZREM, getName(o), encode(o));
    }

    @Override
    public boolean remove(Object value) {
        return get(removeAsync((V)value));
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return get(containsAllAsync(c));
    }

    @Override
    public RFuture<Boolean> containsAllAsync(Collection<?> c) {
        if (c.isEmpty()) {
            return newSucceededFuture(true);
        }
        
        List<Object> params = new ArrayList<Object>(c.size() + 1);
        params.add(System.currentTimeMillis());
        encode(params, c);
        
        return commandExecutor.evalReadAsync(getName(), codec, RedisCommands.EVAL_BOOLEAN,
                            "for j = 2, #ARGV, 1 do "
                            + "local expireDateScore = redis.call('zscore', KEYS[1], ARGV[j]) "
                            + "if expireDateScore ~= false then "
                                + "if tonumber(expireDateScore) <= tonumber(ARGV[1]) then "
                                    + "return 0;"
                                + "end; "
                            + "else "
                                + "return 0;"
                            + "end; "
                        + "end; "
                       + "return 1; ",
                Collections.<Object>singletonList(getName()), params.toArray());
    }

    @Override
    public boolean addAll(Collection<? extends V> c) {
        return get(addAllAsync(c));
    }

    @Override
    public RFuture<Boolean> addAllAsync(Collection<? extends V> c) {
        if (c.isEmpty()) {
            return newSucceededFuture(false);
        }

        long score = 92233720368547758L - System.currentTimeMillis();
        List<Object> params = new ArrayList<Object>(c.size()*2 + 1);
        params.add(getName());
        for (V value : c) {
            ByteBuf objectState = encode(value);
            params.add(score);
            params.add(objectState);
        }

        return commandExecutor.writeAsync(getName(), codec, RedisCommands.ZADD_BOOL_RAW, params.toArray());
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        return get(retainAllAsync(c));
    }

    @Override
    public RFuture<Boolean> retainAllAsync(Collection<?> c) {
        if (c.isEmpty()) {
            return deleteAsync();
        }
        
        long score = 92233720368547758L - System.currentTimeMillis();
        List<Object> params = new ArrayList<Object>(c.size()*2);
        for (Object object : c) {
            params.add(score);
            params.add(encode((V)object));
        }
        
        return commandExecutor.evalWriteAsync(getName(), codec, RedisCommands.EVAL_BOOLEAN,
                "redis.call('zadd', KEYS[2], unpack(ARGV)); "
                 + "local prevSize = redis.call('zcard', KEYS[1]); "
                 + "local size = redis.call('zinterstore', KEYS[1], #ARGV/2, KEYS[1], KEYS[2], 'aggregate', 'min');"
                 + "redis.call('del', KEYS[2]); "
                 + "return size ~= prevSize and 1 or 0; ",
             Arrays.<Object>asList(getName(), "redisson_temp__{" + getName() + "}"), params.toArray());
    }

    @Override
    public RFuture<Boolean> removeAllAsync(Collection<?> c) {
        if (c.isEmpty()) {
            return newSucceededFuture(false);
        }
        
        List<Object> params = new ArrayList<Object>(c.size()+1);
        params.add(getName());
        encode(params, c);

        return commandExecutor.writeAsync(getName(), codec, RedisCommands.ZREM, params.toArray());
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        return get(removeAllAsync(c));
    }

    @Override
    public void clear() {
        delete();
    }

}