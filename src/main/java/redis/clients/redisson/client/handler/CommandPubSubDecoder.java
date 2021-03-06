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

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import redis.clients.redisson.client.RedisPubSubConnection;
import redis.clients.redisson.client.protocol.CommandData;
import redis.clients.redisson.client.protocol.Decoder;
import redis.clients.redisson.client.protocol.RedisCommands;
import redis.clients.redisson.client.protocol.decoder.MultiDecoder;
import redis.clients.redisson.client.protocol.pubsub.Message;
import redis.clients.redisson.client.protocol.pubsub.PubSubMessage;
import redis.clients.redisson.client.protocol.pubsub.PubSubPatternMessage;
import redis.clients.redisson.client.protocol.pubsub.PubSubStatusMessage;

import io.netty.channel.Channel;
import io.netty.util.internal.PlatformDependent;

/**
 * Redis Publish Subscribe protocol decoder
 *
 * @author Nikita Koksharov
 *
 */
public class CommandPubSubDecoder extends CommandDecoder {

    // It is not needed to use concurrent map because responses are coming consecutive
    private final Map<String, PubSubEntry> entries = new HashMap<String, PubSubEntry>();
    private final Map<PubSubKey, CommandData<Object, Object>> commands = PlatformDependent.newConcurrentHashMap();

    private final ExecutorService executor;
    private final boolean keepOrder;
    
    public CommandPubSubDecoder(ExecutorService executor, boolean keepOrder) {
        this.executor = executor;
        this.keepOrder = keepOrder;
    }

    public void addPubSubCommand(String channel, CommandData<Object, Object> data) {
        String operation = data.getCommand().getName().toLowerCase();
        commands.put(new PubSubKey(channel, operation), data);
    }

    @Override
    protected void decodeResult(CommandData<Object, Object> data, List<Object> parts, Channel channel,
            final Object result) throws IOException {
        super.decodeResult(data, parts, channel, result);
        
        if (result instanceof Message) {
            checkpoint();

            if (result instanceof PubSubStatusMessage) {
                String channelName = ((PubSubStatusMessage) result).getChannel();
                String operation = ((PubSubStatusMessage) result).getType().name().toLowerCase();
                PubSubKey key = new PubSubKey(channelName, operation);
                CommandData<Object, Object> d = commands.get(key);
                if (Arrays.asList(RedisCommands.PSUBSCRIBE.getName(), RedisCommands.SUBSCRIBE.getName()).contains(d.getCommand().getName())) {
                    commands.remove(key);
                    entries.put(channelName, new PubSubEntry(d.getMessageDecoder()));
                }
                if (Arrays.asList(RedisCommands.PUNSUBSCRIBE.getName(), RedisCommands.UNSUBSCRIBE.getName()).contains(d.getCommand().getName())) {
                    commands.remove(key);
                    entries.remove(key);
                }
            }
            
            final RedisPubSubConnection pubSubConnection = RedisPubSubConnection.getFrom(channel);
            
            if (keepOrder) {
                String ch = ((Message) result).getChannel();
                if (result instanceof PubSubPatternMessage) {
                    ch = ((PubSubPatternMessage)result).getPattern();
                }
                PubSubEntry item = entries.get(ch);
                enqueueMessage(result, pubSubConnection, item);
            } else {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        if (result instanceof PubSubStatusMessage) {
                            pubSubConnection.onMessage((PubSubStatusMessage) result);
                        } else if (result instanceof PubSubMessage) {
                            pubSubConnection.onMessage((PubSubMessage) result);
                        } else {
                            pubSubConnection.onMessage((PubSubPatternMessage) result);
                        }
                    }
                });
            }
        }
    }

    private void enqueueMessage(Object result, final RedisPubSubConnection pubSubConnection, final PubSubEntry entry) {
        if (result != null) {
            entry.getQueue().add((Message)result);
        }
        
        if (entry.getSent().compareAndSet(false, true)) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        while (true) {
                            Message result = entry.getQueue().poll();
                            if (result != null) {
                                if (result instanceof PubSubStatusMessage) {
                                    pubSubConnection.onMessage((PubSubStatusMessage) result);
                                } else if (result instanceof PubSubMessage) {
                                    pubSubConnection.onMessage((PubSubMessage) result);
                                } else {
                                    pubSubConnection.onMessage((PubSubPatternMessage) result);
                                }
                            } else {
                                break;
                            }
                        }
                    } finally {
                        entry.getSent().set(false);
                        if (!entry.getQueue().isEmpty()) {
                            enqueueMessage(null, pubSubConnection, entry);
                        }
                    }
                }
            });
        }
    }
    
    @Override
    protected MultiDecoder<Object> messageDecoder(CommandData<Object, Object> data, List<Object> parts) {
        if (data == null) {
            if (parts.isEmpty()) {
                return null;
            }
            String command = parts.get(0).toString();
            if (Arrays.asList("subscribe", "psubscribe", "punsubscribe", "unsubscribe").contains(command)) {
                String channelName = parts.get(1).toString();
                PubSubKey key = new PubSubKey(channelName, command);
                CommandData<Object, Object> commandData = commands.get(key);
                if (commandData == null) {
                    return null;
                }
                return commandData.getCommand().getReplayMultiDecoder();
            } else if (parts.get(0).equals("message")) {
                String channelName = (String) parts.get(1);
                return entries.get(channelName).getDecoder();
            } else if (parts.get(0).equals("pmessage")) {
                String patternName = (String) parts.get(1);
                return entries.get(patternName).getDecoder();
            }
        }

        return data.getCommand().getReplayMultiDecoder();
    }

    @Override
    protected Decoder<Object> selectDecoder(CommandData<Object, Object> data, List<Object> parts) {
        if (data == null && parts != null) {
            if (parts.size() == 2 && "message".equals(parts.get(0))) {
                String channelName = (String) parts.get(1);
                return entries.get(channelName).getDecoder();
            }
            if (parts.size() == 3 && "pmessage".equals(parts.get(0))) {
                String patternName = (String) parts.get(1);
                return entries.get(patternName).getDecoder();
            }
        }
        return super.selectDecoder(data, parts);
    }

}
