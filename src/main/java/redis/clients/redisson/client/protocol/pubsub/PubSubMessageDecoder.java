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
package redis.clients.redisson.client.protocol.pubsub;

import java.io.IOException;
import java.util.List;

import redis.clients.redisson.client.handler.State;
import redis.clients.redisson.client.protocol.Decoder;
import redis.clients.redisson.client.protocol.decoder.MultiDecoder;

import io.netty.buffer.ByteBuf;

public class PubSubMessageDecoder implements MultiDecoder<Object> {

    private final Decoder<Object> decoder;

    public PubSubMessageDecoder(Decoder<Object> decoder) {
        super();
        this.decoder = decoder;
    }

    @Override
    public Object decode(ByteBuf buf, State state) throws IOException {
        return decoder.decode(buf, null);
    }

    @Override
    public PubSubMessage decode(List<Object> parts, State state) {
        return new PubSubMessage(parts.get(1).toString(), parts.get(2));
    }

    @Override
    public boolean isApplicable(int paramNum, State state) {
        return true;
    }

}
