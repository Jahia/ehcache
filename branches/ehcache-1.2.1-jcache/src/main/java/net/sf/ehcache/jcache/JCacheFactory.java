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
package net.sf.ehcache.jcache;

import net.sf.ehcache.Ehcache;
import net.sf.ehcache.store.MemoryStoreEvictionPolicy;
import net.sf.ehcache.util.PropertyUtil;

import javax.cache.Cache;
import javax.cache.CacheException;
import javax.cache.CacheFactory;
import java.util.Map;

/**
 * A CacheFactory implementation fo JCache
 * @author Greg Luck
 * @version $Id$
 */
public class JCacheFactory implements CacheFactory {

    /**
     * Creates a new implementation specific Cache object using the environment parameters.
     *
     * The created cache is not accessible from the JCache CacheManager until it has been registered with the manager.
     *
     * Create caches are registered with a singleton ehcache CacheManager.
     *
     * @param environment String value for the following properties:
     *            String name,
     *            int maxElementsInMemory,
     *            MemoryStoreEvictionPolicy memoryStoreEvictionPolicy (one of LFU, LRU or FIFO)
     *            boolean overflowToDisk,
     *            boolean eternal,
     *            long timeToLiveSeconds,
     *            long timeToIdleSeconds,
     *            boolean diskPersistent,
     *            long diskExpiryThreadIntervalSeconds
     *
     * Note that the following cannot be set:
     * <ol>
     * <li>diskStorePath - this is set on the CacheManager and ignored here
     * <li>RegisteredEventListeners - register any of these after cache creation
     * <li>BootstrapCacheLoader - not supported here
     * </ol>
     * @return a newly created JCache
     * @throws CacheException
     */
    public Cache createCache(Map environment) throws CacheException {


        String name = PropertyUtil.extractAndLogProperty("name", environment);

        String maxElementsInMemoryString = PropertyUtil.extractAndLogProperty("maxElementsInMemory", environment);
        int maxElementsInMemory = Integer.parseInt(maxElementsInMemoryString);

        String memoryStoreEvictionPolicyString = PropertyUtil.extractAndLogProperty("memoryStoreEvictionPolicy", environment);
        MemoryStoreEvictionPolicy memoryStoreEvictionPolicy =
                MemoryStoreEvictionPolicy.fromString(memoryStoreEvictionPolicyString);

        String overflowToDiskString = PropertyUtil.extractAndLogProperty("overflowToDisk", environment);
        boolean overflowToDisk = Boolean.parseBoolean(overflowToDiskString);

        String eternalString = PropertyUtil.extractAndLogProperty("eternal", environment);
        boolean eternal = Boolean.parseBoolean(eternalString);

        String timeToLiveSecondsString = PropertyUtil.extractAndLogProperty("timeToLiveSeconds", environment);
        long timeToLiveSeconds = Long.parseLong(timeToLiveSecondsString);

        String timeToIdleSecondsString = PropertyUtil.extractAndLogProperty("timeToIdleSeconds", environment);
        long timeToIdleSeconds = Long.parseLong(timeToIdleSecondsString);

        String diskPersistentString = PropertyUtil.extractAndLogProperty("diskPersistentSeconds", environment);
        boolean diskPersistent = Boolean.parseBoolean(diskPersistentString);

        String diskExpiryThreadIntervalSecondsString =
                PropertyUtil.extractAndLogProperty("diskExpiryThreadIntervalSeconds", environment);
        long diskExpiryThreadIntervalSeconds = Long.parseLong(diskExpiryThreadIntervalSecondsString);

        Ehcache cache = new net.sf.ehcache.Cache(name, maxElementsInMemory, memoryStoreEvictionPolicy,
                overflowToDisk, null, eternal,
                timeToLiveSeconds, timeToIdleSeconds, diskPersistent, diskExpiryThreadIntervalSeconds,
                null, null);


        net.sf.ehcache.CacheManager.getInstance().addCache(cache);

        return new JCache(cache);

    }
}
