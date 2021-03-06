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
package redis.clients.redisson.client.protocol.decoder;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import redis.clients.redisson.client.handler.State;

import io.netty.buffer.ByteBuf;

/**
 * 
 * @author Nikita Koksharov
 *
 */
public class MapCacheScanResultReplayDecoder implements MultiDecoder<MapCacheScanResult<Object, Object>> {

    @Override
    public Object decode(ByteBuf buf, State state) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public MapCacheScanResult<Object, Object> decode(List<Object> parts, State state) {
        Long pos = (Long)parts.get(0);
        Map<Object, Object> values = (Map<Object, Object>)parts.get(1);
        List<Object> idleKeys = (List<Object>) parts.get(2);
        return new MapCacheScanResult<Object, Object>(pos, values, idleKeys);
    }

    @Override
    public boolean isApplicable(int paramNum, State state) {
        return false;
    }

}
