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
package redis.clients.redisson.codec;

import java.io.IOException;

import redis.clients.redisson.client.codec.Codec;
import redis.clients.redisson.client.handler.State;
import redis.clients.redisson.client.protocol.Decoder;
import redis.clients.redisson.client.protocol.Encoder;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.compression.Snappy;

/**
 * Snappy compression codec.
 * Uses inner <code>Codec</code> to convert object to binary stream.
 * <code>FstCodec</code> used by default.
 *
 * @see redis.clients.redisson.codec.FstCodec
 *
 * @author Nikita Koksharov
 *
 */
public class SnappyCodec implements Codec {

    private static final ThreadLocal<Snappy> snappyDecoder = new ThreadLocal<Snappy>() {
        protected Snappy initialValue() {
            return new Snappy();
        };
    };
    
    private static final ThreadLocal<Snappy> snappyEncoder = new ThreadLocal<Snappy>() {
        protected Snappy initialValue() {
            return new Snappy();
        };
    };

    private final Codec innerCodec;

    public SnappyCodec() {
        this(new FstCodec());
    }

    public SnappyCodec(Codec innerCodec) {
        this.innerCodec = innerCodec;
    }

    public SnappyCodec(ClassLoader classLoader) {
        this(new FstCodec(classLoader));
    }
    
    private final Decoder<Object> decoder = new Decoder<Object>() {
        
        @Override
        public Object decode(ByteBuf buf, State state) throws IOException {
            ByteBuf out = ByteBufAllocator.DEFAULT.buffer();
            try {
                snappyDecoder.get().decode(buf, out);
                return innerCodec.getValueDecoder().decode(out, state);
            } finally {
                snappyDecoder.get().reset();
                out.release();
            }
        }
    };

    private final Encoder encoder = new Encoder() {

        @Override
        public ByteBuf encode(Object in) throws IOException {
            ByteBuf buf = innerCodec.getValueEncoder().encode(in);
            ByteBuf out = ByteBufAllocator.DEFAULT.buffer(buf.readableBytes() + 128);
            try {
                snappyEncoder.get().encode(buf, out, buf.readableBytes());
                return out;
            } finally {
                buf.release();
                snappyEncoder.get().reset();
            }
        }
    };

    @Override
    public Decoder<Object> getMapValueDecoder() {
        return getValueDecoder();
    }

    @Override
    public Encoder getMapValueEncoder() {
        return getValueEncoder();
    }

    @Override
    public Decoder<Object> getMapKeyDecoder() {
        return getValueDecoder();
    }

    @Override
    public Encoder getMapKeyEncoder() {
        return getValueEncoder();
    }

    @Override
    public Decoder<Object> getValueDecoder() {
        return decoder;
    }

    @Override
    public Encoder getValueEncoder() {
        return encoder;
    }

}