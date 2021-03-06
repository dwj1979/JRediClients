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

import java.util.HashMap;
import java.util.Map;

import redis.clients.redisson.client.handler.State;
import redis.clients.redisson.client.protocol.Decoder;

import io.netty.buffer.ByteBuf;
import io.netty.util.CharsetUtil;

/**
 * 
 * @author Nikita Koksharov
 *
 */
public class StringMapDataDecoder implements Decoder<Map<String, String>> {

    @Override
    public Map<String, String> decode(ByteBuf buf, State state) {
        String value = buf.toString(CharsetUtil.UTF_8);
        Map<String, String> result = new HashMap<String, String>();
        for (String entry : value.split("\r\n|\n")) {
            String[] parts = entry.split(":");
            if (parts.length == 2) {
                result.put(parts[0], parts[1]);
            }
        }
        return result;
    }

}
