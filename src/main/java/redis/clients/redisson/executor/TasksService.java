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
package redis.clients.redisson.executor;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import redis.clients.redisson.BaseRemoteService;
import redis.clients.redisson.RedissonExecutorService;
import redis.clients.redisson.api.RBlockingQueue;
import redis.clients.redisson.api.RFuture;
import redis.clients.redisson.api.RedissonClient;
import redis.clients.redisson.client.codec.Codec;
import redis.clients.redisson.client.codec.LongCodec;
import redis.clients.redisson.client.protocol.RedisCommands;
import redis.clients.redisson.command.CommandAsyncExecutor;
import redis.clients.redisson.misc.RPromise;
import redis.clients.redisson.misc.RedissonPromise;
import redis.clients.redisson.remote.RemoteServiceCancelRequest;
import redis.clients.redisson.remote.RemoteServiceCancelResponse;
import redis.clients.redisson.remote.RemoteServiceRequest;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;

/**
 * 
 * @author Nikita Koksharov
 *
 */
public class TasksService extends BaseRemoteService {

    protected String terminationTopicName;
    protected String tasksCounterName;
    protected String statusName;
    protected String tasksName;
    
    public TasksService(Codec codec, RedissonClient redisson, String name, CommandAsyncExecutor commandExecutor) {
        super(codec, redisson, name, commandExecutor);
    }
    
    public void setTerminationTopicName(String terminationTopicName) {
        this.terminationTopicName = terminationTopicName;
    }
    
    public void setStatusName(String statusName) {
        this.statusName = statusName;
    }
    
    public void setTasksCounterName(String tasksCounterName) {
        this.tasksCounterName = tasksCounterName;
    }
    
    public void setTasksName(String tasksName) {
        this.tasksName = tasksName;
    }

    @Override
    protected final RFuture<Boolean> addAsync(RBlockingQueue<RemoteServiceRequest> requestQueue,
            RemoteServiceRequest request, RemotePromise<Object> result) {
        final RPromise<Boolean> promise = new RedissonPromise<Boolean>();
        RFuture<Boolean> future = addAsync(requestQueue, request);
        result.setAddFuture(future);
        
        future.addListener(new FutureListener<Boolean>() {
            @Override
            public void operationComplete(Future<Boolean> future) throws Exception {
                if (!future.isSuccess()) {
                    promise.tryFailure(future.cause());
                    return;
                }

                if (!future.getNow()) {
                    promise.cancel(true);
                    return;
                }
                
                promise.trySuccess(true);
            }
        });
        
        return promise;
    }

    protected CommandAsyncExecutor getAddCommandExecutor() {
        return commandExecutor;
    }
    
    protected RFuture<Boolean> addAsync(RBlockingQueue<RemoteServiceRequest> requestQueue, RemoteServiceRequest request) {
        return getAddCommandExecutor().evalWriteAsync(name, LongCodec.INSTANCE, RedisCommands.EVAL_BOOLEAN,
                "if redis.call('exists', KEYS[2]) == 0 then "
                    + "redis.call('rpush', KEYS[3], ARGV[2]); "
                    + "redis.call('hset', KEYS[4], ARGV[1], ARGV[2]);"
                    + "redis.call('incr', KEYS[1]);"
                    + "return 1;"
                + "end;"
                + "return 0;", 
                Arrays.<Object>asList(tasksCounterName, statusName, requestQueue.getName(), tasksName),
                request.getRequestId(), encode(request));
    }
    
    @Override
    protected RFuture<Boolean> removeAsync(RBlockingQueue<RemoteServiceRequest> requestQueue, RemoteServiceRequest request) {
        return commandExecutor.evalWriteAsync(name, LongCodec.INSTANCE, RedisCommands.EVAL_BOOLEAN,
                    "local task = redis.call('hget', KEYS[5], ARGV[1]); " + 
                    "if task ~= false and redis.call('lrem', KEYS[1], 1, task) > 0 then "
                      + "redis.call('hdel', KEYS[5], ARGV[1]); "
                      + "if redis.call('decr', KEYS[2]) == 0 then "
                          + "redis.call('del', KEYS[2]);"
                          + "if redis.call('get', KEYS[3]) == ARGV[2] then "
                           + "redis.call('set', KEYS[3], ARGV[3]);"
                           + "redis.call('publish', KEYS[4], ARGV[3]);"
                          + "end;"
                      + "end;"
                      + "return 1;"
                  + "end;"
                  + "redis.call('hdel', KEYS[5], ARGV[1]); "
                  + "return 0;",
              Arrays.<Object>asList(requestQueue.getName(), tasksCounterName, statusName, terminationTopicName, tasksName), 
              request.getRequestId(), RedissonExecutorService.SHUTDOWN_STATE, RedissonExecutorService.TERMINATED_STATE);
    }

    public RFuture<Boolean> cancelExecutionAsync(final String requestId) {
        final Class<?> syncInterface = RemoteExecutorService.class;
        String requestQueueName = getRequestQueueName(syncInterface);

        if (!redisson.getMap(tasksName, LongCodec.INSTANCE).containsKey(requestId)) {
            return RedissonPromise.newSucceededFuture(false);
        }

        final RPromise<Boolean> result = new RedissonPromise<Boolean>();
        
        RBlockingQueue<RemoteServiceRequest> requestQueue = redisson.getBlockingQueue(requestQueueName, getCodec());

        RemoteServiceRequest request = new RemoteServiceRequest(requestId);
        RFuture<Boolean> removeFuture = removeAsync(requestQueue, request);
        removeFuture.addListener(new FutureListener<Boolean>() {
            @Override
            public void operationComplete(Future<Boolean> future) throws Exception {
                if (!future.isSuccess()) {
                    result.tryFailure(future.cause());
                    return;
                }
                
                if (future.getNow()) {
                    result.trySuccess(true);
                } else {
                    String cancelRequestName = getCancelRequestQueueName(syncInterface, requestId);
                    
                    RBlockingQueue<RemoteServiceCancelRequest> cancelRequestQueue = redisson.getBlockingQueue(cancelRequestName, getCodec());
                    cancelRequestQueue.putAsync(new RemoteServiceCancelRequest(true, requestId + ":cancel-response"));
                    cancelRequestQueue.expireAsync(60, TimeUnit.SECONDS);
                    
                    String responseQueueName = getResponseQueueName(syncInterface, requestId + ":cancel-response");
                    RBlockingQueue<RemoteServiceCancelResponse> responseQueue = redisson.getBlockingQueue(responseQueueName, getCodec());
                    final RFuture<RemoteServiceCancelResponse> response = responseQueue.pollAsync(60, TimeUnit.SECONDS);
                    response.addListener(new FutureListener<RemoteServiceCancelResponse>() {
                        @Override
                        public void operationComplete(Future<RemoteServiceCancelResponse> future) throws Exception {
                            if (!future.isSuccess()) {
                                result.tryFailure(future.cause());
                                return;
                            }
                            
                            if (response.getNow() == null) {
                                result.trySuccess(false);
                                return;
                            }
                            result.trySuccess(response.getNow().isCanceled());
                        }
                    });
                }
            }
        });

        return result;
    }
    
}
