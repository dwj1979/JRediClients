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

import redis.clients.redisson.RedissonExecutorService;
import redis.clients.redisson.api.RBlockingQueue;
import redis.clients.redisson.api.RFuture;
import redis.clients.redisson.api.RedissonClient;
import redis.clients.redisson.api.RemoteInvocationOptions;
import redis.clients.redisson.client.codec.Codec;
import redis.clients.redisson.client.codec.LongCodec;
import redis.clients.redisson.client.protocol.RedisCommands;
import redis.clients.redisson.command.CommandExecutor;
import redis.clients.redisson.remote.RemoteServiceRequest;

import io.netty.buffer.ByteBuf;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;

/**
 * 
 * @author Nikita Koksharov
 *
 */
public class ScheduledTasksService extends TasksService {

    private String requestId;
    private String schedulerQueueName;
    private String schedulerChannelName;
    
    public ScheduledTasksService(Codec codec, RedissonClient redisson, String name, CommandExecutor commandExecutor) {
        super(codec, redisson, name, commandExecutor);
    }
    
    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
    
    public void setSchedulerChannelName(String schedulerChannelName) {
        this.schedulerChannelName = schedulerChannelName;
    }
    
    public void setSchedulerQueueName(String scheduledQueueName) {
        this.schedulerQueueName = scheduledQueueName;
    }
    
    @Override
    protected RFuture<Boolean> addAsync(RBlockingQueue<RemoteServiceRequest> requestQueue, RemoteServiceRequest request) {
        Long startTime = (Long)request.getArgs()[3];
        ByteBuf encodedRequest = encode(request);
        
        if (requestId != null) {
            return commandExecutor.evalWriteAsync(name, LongCodec.INSTANCE, RedisCommands.EVAL_BOOLEAN,
                    // check if executor service not in shutdown state and previous task exists
                    "if redis.call('exists', KEYS[2]) == 0 and redis.call('hexists', KEYS[5], ARGV[2]) == 1 then "
                        + "redis.call('zadd', KEYS[3], ARGV[1], ARGV[2]);"
                        + "redis.call('hset', KEYS[5], ARGV[2], ARGV[3]);"
                        + "redis.call('incr', KEYS[1]);"
                        // if new task added to queue head then publish its startTime 
                        // to all scheduler workers 
                        + "local v = redis.call('zrange', KEYS[3], 0, 0); "
                        + "if v[1] == ARGV[2] then "
                           + "redis.call('publish', KEYS[4], ARGV[1]); "
                        + "end "
                        + "return 1;"
                    + "end;"
                    + "return 0;", 
                    Arrays.<Object>asList(tasksCounterName, statusName, schedulerQueueName, schedulerChannelName, tasksName),
                    startTime, request.getRequestId(), encodedRequest);
        }
        
        return commandExecutor.evalWriteAsync(name, LongCodec.INSTANCE, RedisCommands.EVAL_BOOLEAN,
                // check if executor service not in shutdown state
                "if redis.call('exists', KEYS[2]) == 0 then "
                    + "redis.call('zadd', KEYS[3], ARGV[1], ARGV[2]);"
                    + "redis.call('hset', KEYS[5], ARGV[2], ARGV[3]);"
                    + "redis.call('incr', KEYS[1]);"
                    + "local v = redis.call('zrange', KEYS[3], 0, 0); "
                    // if new task added to queue head then publish its startTime 
                    // to all scheduler workers 
                    + "if v[1] == ARGV[2] then "
                       + "redis.call('publish', KEYS[4], ARGV[1]); "
                    + "end "
                    + "return 1;"
                + "end;"
                + "return 0;", 
                Arrays.<Object>asList(tasksCounterName, statusName, schedulerQueueName, schedulerChannelName, tasksName),
                startTime, request.getRequestId(), encodedRequest);
    }
    
    @Override
    protected void awaitResultAsync(final RemoteInvocationOptions optionsCopy, final RemotePromise<Object> result,
            final RemoteServiceRequest request, final String responseName) {
        if (!optionsCopy.isResultExpected()) {
            return;
        }
        
        Long startTime = 0L;
        if (request != null && request.getArgs() != null && request.getArgs().length > 3) {
            startTime = (Long)request.getArgs()[3];
        }
        long delay = startTime - System.currentTimeMillis();
        if (delay > 0) {
            commandExecutor.getConnectionManager().newTimeout(new TimerTask() {
                @Override
                public void run(Timeout timeout) throws Exception {
                    ScheduledTasksService.super.awaitResultAsync(optionsCopy, result, request, responseName);
                }
            }, delay, TimeUnit.MILLISECONDS);
        } else {
            super.awaitResultAsync(optionsCopy, result, request, responseName);
        }
    }

    @Override
    protected RFuture<Boolean> removeAsync(RBlockingQueue<RemoteServiceRequest> requestQueue, RemoteServiceRequest request) {
        return commandExecutor.evalWriteAsync(name, LongCodec.INSTANCE, RedisCommands.EVAL_BOOLEAN,
                   // remove from scheduler queue
                    "if redis.call('zrem', KEYS[2], ARGV[1]) > 0 then "
                      + "redis.call('hdel', KEYS[6], ARGV[1]); "
                      + "if redis.call('decr', KEYS[3]) == 0 then "
                         + "redis.call('del', KEYS[3]);"
                         + "if redis.call('get', KEYS[4]) == ARGV[2] then "
                            + "redis.call('set', KEYS[4], ARGV[3]);"
                            + "redis.call('publish', KEYS[5], ARGV[3]);"
                         + "end;"
                      + "end;"
                      + "return 1;"
                  + "end;"
                  + "local task = redis.call('hget', KEYS[6], ARGV[1]); "
                   // remove from executor queue
                  + "if task ~= false and redis.call('lrem', KEYS[1], 1, task) > 0 then "
                      + "redis.call('hdel', KEYS[6], ARGV[1]); "
                      + "if redis.call('decr', KEYS[3]) == 0 then "
                         + "redis.call('del', KEYS[3]);"
                         + "if redis.call('get', KEYS[4]) == ARGV[2] then "
                            + "redis.call('set', KEYS[4], ARGV[3]);"
                            + "redis.call('publish', KEYS[5], ARGV[3]);"
                         + "end;"
                      + "end;"
                      + "return 1;"
                  + "end;"
                   // delete scheduled task
                  + "redis.call('hdel', KEYS[6], ARGV[1]); "
                  + "return 0;",
              Arrays.<Object>asList(requestQueue.getName(), schedulerQueueName, tasksCounterName, statusName, terminationTopicName, tasksName), 
              request.getRequestId(), RedissonExecutorService.SHUTDOWN_STATE, RedissonExecutorService.TERMINATED_STATE);
    }
    
    @Override
    protected String generateRequestId() {
        if (requestId == null) {
            return super.generateRequestId();
        }
        return requestId;
    }    

}
