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
import java.util.ArrayList;
import java.util.List;

import redis.clients.redisson.client.handler.State;
import redis.clients.redisson.client.protocol.Decoder;
import redis.clients.redisson.cluster.ClusterNodeInfo;
import redis.clients.redisson.cluster.ClusterSlotRange;

import io.netty.buffer.ByteBuf;
import io.netty.util.CharsetUtil;

/**
 * 
 * @author Nikita Koksharov
 *
 */
public class ClusterNodesDecoder implements Decoder<List<ClusterNodeInfo>> {

    @Override
    public List<ClusterNodeInfo> decode(ByteBuf buf, State state) throws IOException {
        String response = buf.toString(CharsetUtil.UTF_8);
        
        List<ClusterNodeInfo> nodes = new ArrayList<ClusterNodeInfo>();
        for (String nodeInfo : response.split("\n")) {
            ClusterNodeInfo node = new ClusterNodeInfo(nodeInfo);
            String[] params = nodeInfo.split(" ");

            String nodeId = params[0];
            node.setNodeId(nodeId);

            String addr = "redis://" + params[1].split("@")[0];
            node.setAddress(addr);

            String flags = params[2];
            for (String flag : flags.split(",")) {
                String flagValue = flag.toUpperCase().replaceAll("\\?", "");
                node.addFlag(ClusterNodeInfo.Flag.valueOf(flagValue));
            }

            String slaveOf = params[3];
            if (!"-".equals(slaveOf)) {
                node.setSlaveOf(slaveOf);
            }

            if (params.length > 8) {
                for (int i = 0; i < params.length - 8; i++) {
                    String slots = params[i + 8];
                    if (slots.indexOf("-<-") != -1 || slots.indexOf("->-") != -1) {
                        continue;
                    }

                    String[] parts = slots.split("-");
                    if(parts.length == 1) {
                        node.addSlotRange(new ClusterSlotRange(Integer.valueOf(parts[0]), Integer.valueOf(parts[0])));
                    } else if(parts.length == 2) {
                        node.addSlotRange(new ClusterSlotRange(Integer.valueOf(parts[0]), Integer.valueOf(parts[1])));
                    }
                }
            }
            nodes.add(node);
        }
        return nodes;
    }

}
