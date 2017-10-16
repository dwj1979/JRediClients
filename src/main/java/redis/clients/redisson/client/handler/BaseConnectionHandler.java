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
package redis.clients.redisson.client.handler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import redis.clients.redisson.api.RFuture;
import redis.clients.redisson.client.RedisClient;
import redis.clients.redisson.client.RedisClientConfig;
import redis.clients.redisson.client.RedisConnection;
import redis.clients.redisson.client.protocol.RedisCommands;
import redis.clients.redisson.misc.RPromise;
import redis.clients.redisson.misc.RedissonPromise;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;

/**
 * 
 * @author Nikita Koksharov
 *
 */
public abstract class BaseConnectionHandler<C extends RedisConnection> extends ChannelInboundHandlerAdapter {

    final RedisClient redisClient;
    final RPromise<C> connectionPromise = new RedissonPromise<C>();
    C connection;
    
    public BaseConnectionHandler(RedisClient redisClient) {
        super();
        this.redisClient = redisClient;
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        if (connection == null) {
            connection = createConnection(ctx);
        }
        super.channelRegistered(ctx);
    }

    abstract C createConnection(ChannelHandlerContext ctx);
    
    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
        final AtomicInteger commandsCounter = new AtomicInteger();
        List<RFuture<Object>> futures = new ArrayList<RFuture<Object>>();

        RedisClientConfig config = redisClient.getConfig();
        if (config.getPassword() != null) {
            RFuture<Object> future = connection.async(RedisCommands.AUTH, config.getPassword());
            futures.add(future);
        }
        if (config.getDatabase() != 0) {
            RFuture<Object> future = connection.async(RedisCommands.SELECT, config.getDatabase());
            futures.add(future);
        }
        if (config.getClientName() != null) {
            RFuture<Object> future = connection.async(RedisCommands.CLIENT_SETNAME, config.getClientName());
            futures.add(future);
        }
        if (config.isReadOnly()) {
            RFuture<Object> future = connection.async(RedisCommands.READONLY);
            futures.add(future);
        }
        if (config.isPingConnection()) {
            RFuture<Object> future = connection.async(RedisCommands.PING);
            futures.add(future);
        }
        
        if (futures.isEmpty()) {
            ctx.fireChannelActive();
            connectionPromise.trySuccess(connection);
            return;
        }
        
        commandsCounter.set(futures.size());
        for (RFuture<Object> future : futures) {
            future.addListener(new FutureListener<Object>() {
                @Override
                public void operationComplete(Future<Object> future) throws Exception {
                    if (!future.isSuccess()) {
                        connection.closeAsync();
                        connectionPromise.tryFailure(future.cause());
                        return;
                    }
                    if (commandsCounter.decrementAndGet() == 0) {
                        ctx.fireChannelActive();
                        connectionPromise.trySuccess(connection);
                    }
                }
            });
        }
    }
    
}
