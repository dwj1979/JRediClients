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
package redis.clients.redisson.pubsub;

import redis.clients.redisson.RedissonLockEntry;
import redis.clients.redisson.misc.RPromise;

/**
 * 
 * @author Nikita Koksharov
 *
 */
public class LockPubSub extends PublishSubscribe<RedissonLockEntry> {

    public static final Long unlockMessage = 0L;

    @Override
    protected RedissonLockEntry createEntry(RPromise<RedissonLockEntry> newPromise) {
        return new RedissonLockEntry(newPromise);
    }

    @Override
    protected void onMessage(RedissonLockEntry value, Long message) {
        if (message.equals(unlockMessage)) {
            value.getLatch().release();

            while (true) {
                Runnable runnableToExecute = null;
                synchronized (value) {
                    Runnable runnable = value.getListeners().poll();
                    if (runnable != null) {
                        if (value.getLatch().tryAcquire()) {
                            runnableToExecute = runnable;
                        } else {
                            value.addListener(runnable);
                        }
                    }
                }
                
                if (runnableToExecute != null) {
                    runnableToExecute.run();
                } else {
                    return;
                }
            }
        }
    }

}
