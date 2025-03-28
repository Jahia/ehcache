/**
 *  Copyright 2003-2006 Greg Luck
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package net.sf.ehcache.constructs.blocking;

import net.sf.ehcache.CacheException;
import net.sf.ehcache.StopWatch;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;


/**
 * Test cases for the {@link UpdatingSelfPopulatingCache}.
 *
 * @author <a href="mailto:gluck@thoughtworks.com">Greg Luck</a>
 * @version $Id$
 */
public class UpdatingSelfPopulatingCacheTest extends SelfPopulatingCacheTest {
    private static final Log LOG = LogFactory.getLog(UpdatingSelfPopulatingCacheTest.class.getName());

    /**
     * Tests fetching an entry, and then an update.
     */
    public void testFetchAndUpdate() throws Exception {
        final String value = "value";
        final CountingCacheEntryFactory factory = new CountingCacheEntryFactory(value);
        final UpdatingSelfPopulatingCache cache =
                new UpdatingSelfPopulatingCache("sampleCacheNotEternalButNoIdleOrExpiry", factory);

        // Lookup
        Object actualValue = cache.get("key");
        assertSame(value, actualValue);
        assertEquals(1, factory.getCount());

        actualValue = cache.get("key");
        assertSame(value, actualValue);
        assertEquals(2, factory.getCount());

        actualValue = cache.get("key");
        assertSame(value, actualValue);
        assertEquals(3, factory.getCount());

        cache.clear();
    }

    /**
     * Tests when fetch fails.
     */
    public void testFetchFail() throws Exception {
        final Exception exception = new Exception("Failed.");
        final UpdatingCacheEntryFactory factory = new UpdatingCacheEntryFactory() {
            public Serializable createEntry(final Serializable key)
                    throws Exception {
                throw exception;
            }

            public void updateEntryValue(Serializable key, Serializable value)
                    throws Exception {
                throw exception;
            }
        };

        final UpdatingSelfPopulatingCache cache =
                new UpdatingSelfPopulatingCache("sampleCacheNotEternalButNoIdleOrExpiry", factory);

        // Lookup
        try {
            cache.get("key");
            fail();
        } catch (final Exception e) {
            Thread.sleep(1);

            // Check the error
            assertEquals("Could not fetch object for cache entry \"key\".", e.getMessage());
        }

        cache.clear();
    }

    /**
     * Tests refreshing the entries.
     */
    public void testRefresh() throws Exception {
        final String value = "value";
        final CountingCacheEntryFactory factory = new CountingCacheEntryFactory(value);
        final UpdatingSelfPopulatingCache cache =
                new UpdatingSelfPopulatingCache("sampleCacheNotEternalButNoIdleOrExpiry", factory);

        // Refresh
        try {
            cache.refresh();
            fail();
        } catch (CacheException e) {
            //expected.
            assertEquals("UpdatingSelfPopulatingCache objects should not be refreshed.", e.getMessage());
        }

        cache.clear();
    }

    /**
     * Thrashes a UpdatingSelfPopulatingCache and looks for liveness problems
     * Note. These timings are without logging. Turn logging off to run this test.
     * <p/>
     * To get this test to fail, add the synchronized keyword to {@link UpdatingSelfPopulatingCache#get(java.io.Serializable)}.
     */
    public void testThrashUpdatingSelfPopulatingCache() throws Exception {
        final String value = "value";
        final CountingCacheEntryFactory factory = new CountingCacheEntryFactory(value);
        final UpdatingSelfPopulatingCache cache =
                new UpdatingSelfPopulatingCache("sampleCacheNotEternalButNoIdleOrExpiry", factory);
        long duration = thrashCache(cache, 300L, 1500L);
        LOG.debug("Thrash Duration:" + duration);
    }

    /**
     * This method tries to get the cache to slow up.
     * It creates 40 threads, does blocking gets and monitors the liveness right the way through
     */
    private long thrashCache(final UpdatingSelfPopulatingCache cache, final long liveness, final long retrievalTime)
            throws Exception {
        StopWatch stopWatch = new StopWatch();

        // Create threads that do gets
        final List executables = new ArrayList();
        for (int i = 0; i < 10; i++) {
            final UpdatingSelfPopulatingCacheTest.Executable executable = new UpdatingSelfPopulatingCacheTest.Executable() {
                public void execute() throws Exception {
                    for (int i = 0; i < 10; i++) {
                        final String key = "key" + i;
                        Serializable value = cache.get(key);
                        checkLiveness(cache, liveness);
                        if (value == null) {
                            cache.put(key, "value" + i);
                        }
                        //The key will be in. Now check we can get it quickly
                        checkRetrievalOnKnownKey(cache, retrievalTime, key);
                    }
                }
            };
            executables.add(executable);
        }

        runThreads(executables);
        cache.clear();
        return stopWatch.getElapsedTime();
    }

    /**
     * Checks that the liveness method returns in less than a given amount of time.
     * liveness() is a method that simply returns a String. It should be very fast. It can be
     * delayed because it is a synchronized method, and must acquire an object lock before continuing
     * The old blocking cache was taking up to several minutes in production
     *
     * @param cache a BlockingCache
     */
    private void checkLiveness(UpdatingSelfPopulatingCache cache, long liveness) {
        StopWatch stopWatch = new StopWatch();
        cache.liveness();
        long measuredLiveness = stopWatch.getElapsedTime();
        assertTrue("liveness is " + measuredLiveness + " but should be less than " + liveness + "ms",
                measuredLiveness < liveness);
    }

    /**
     * Checks that the liveness method returns in less than a given amount of time.
     * liveness() is a method that simply returns a String. It should be very fast. It can be
     * delayed because it is a synchronized method, and must acquire
     * an object lock before continuing. The old blocking cache was taking up to several minutes in production
     *
     * @param cache a BlockingCache
     */
    private void checkRetrievalOnKnownKey(UpdatingSelfPopulatingCache cache, long requiredRetrievalTime, Serializable key)
            throws BlockingCacheException {
        StopWatch stopWatch = new StopWatch();
        cache.get(key);
        long measuredRetrievalTime = stopWatch.getElapsedTime();
        assertTrue("Retrieval time on known key is " + measuredRetrievalTime
                + " but should be less than " + requiredRetrievalTime + "ms",
                measuredRetrievalTime < requiredRetrievalTime);
    }

    /**
     * Runs a set of threads, for a fixed amount of time.
     */
    private void runThreads(final List executables) throws Exception {

        final long endTime = System.currentTimeMillis() + 10000;
        final Throwable[] errors = new Throwable[1];

        // Spin up the threads
        final Thread[] threads = new Thread[executables.size()];
        for (int i = 0; i < threads.length; i++) {
            final UpdatingSelfPopulatingCacheTest.Executable executable = (UpdatingSelfPopulatingCacheTest.Executable)
                    executables.get(i);
            threads[i] = new Thread() {
                public void run() {
                    try {
                        // Run the thread until the given end time
                        while (System.currentTimeMillis() < endTime) {
                            executable.execute();
                        }
                    } catch (Throwable t) {
                        // Hang on to any errors
                        errors[0] = t;
                    }
                }
            };

            threads[i].start();
        }
        LOG.debug("Started " + threads.length + " threads");

        // Wait for the threads to finish
        for (int i = 0; i < threads.length; i++) {
            threads[i].join();
        }

        // Throw any error that happened
        if (errors[0] != null) {
            throw new Exception("Test thread failed.", errors[0]);
        }
    }

    /**
     * A runnable, that can throw an exception.
     */
    private interface Executable {
        // Executes this object.
        void execute() throws Exception;
    }


}
