/**
 * Copyright 2015 Palantir Technologies
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.common.random;


import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SecureRandomPool {
    private static final Logger log = LoggerFactory.getLogger(SecureRandomPool.class);

    private final List<SecureRandom> pool;
    private final SecureRandom seedSource;
    private final AtomicLong next = new AtomicLong(0L);

    /**
     * Creates a SecureRandomPool using the specified algorithm.
     * @param algorithm
     */
    public SecureRandomPool(String algorithm, int poolSize) {
        this(algorithm, poolSize, null);
    }

    /**
     * Creates a SecureRandomPool using the specified algorithm.  The provided
     * SecureRandom is used to seed each new SecureRandom in the pool.
     * @param algorithm
     * @param seed
     */
    public SecureRandomPool(String algorithm, int poolSize, SecureRandom seed) {
        if (algorithm == null) {
            throw new IllegalArgumentException("algorithm is null");
        }

        pool = new ArrayList<SecureRandom>(poolSize);
        seedSource = getSeedSource(algorithm, (seed != null) ? seed : new SecureRandom());

        try {
            for (int i=0; i<poolSize; i++) {
                byte[] seedBytes = new byte[20];
                seedSource.nextBytes(seedBytes);
                SecureRandom random = SecureRandom.getInstance(algorithm); // (authorized)
                random.setSeed(seedBytes);
                pool.add(random);
            }
        } catch (NoSuchAlgorithmException e) {
            String msg = "Error getting SecureRandom using " + algorithm + " algorithm.";
            log.error(msg, e);
            throw new RuntimeException(msg, e);
        }
    }

    public SecureRandom getSecureRandom() {
        int i = (int) (Math.abs(next.getAndIncrement() % pool.size()));
        return pool.get(i);
    }

    /**
     * 1. The provided seed is the initial seed.  On Linux, it likely reads from
     * /dev/random or /dev/urandom and is potentially very slow.<br/>
     * 2. The initial seed is then used to create a source seed based on the provided
     * algorithm.  This algorithm should be something fast, such as "SHA1PRNG".<br/>
     * 3. The source seed is then used to seed all of the SecureRandoms in the
     * pool.  If the algorithm is fast, then initialization of the pool should
     * fast as well.<br/>
     * @param algorithm
     * @param seed
     * @return
     */
    private SecureRandom getSeedSource(String algorithm, SecureRandom seed) {
        try {
            SecureRandom seedSource = SecureRandom.getInstance(algorithm); // (authorized)
            byte[] seedBytes = new byte[20];
            seed.nextBytes(seedBytes);
            seedSource.setSeed(seedBytes);
            return seedSource;
        } catch (NoSuchAlgorithmException e) {
            log.error("Error getting SecureRandom using " + algorithm + " algorithm for seed source.", e);
            return seed;
        }
    }
}
