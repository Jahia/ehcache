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

import junit.framework.TestCase;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.store.MemoryStoreEvictionPolicy;

import javax.cache.Cache;
import javax.cache.CacheException;
import javax.cache.CacheFactory;
import javax.cache.CacheManager;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Greg Luck
 * @version $Id$
 */
public class CacheManagerTest extends TestCase {


    /**
     * the CacheManager Singleton instance
     */
    protected CacheManager singletonManager;

    /**
     * a CacheManager which is created as an instance
     */
    protected CacheManager instanceManager;

    /**
     * Shutdown managers.
     * Check that the manager is removed from CacheManager.ALL_CACHE_MANAGERS
     */
    protected void tearDown() throws Exception {

    }


    /**
     * Tests the constructors.
     * <p/>
     * The factory method and new return different instances.
     * <p/>
     * getInstance always returns the same instance
     */
    public void testCacheManagerConstructor() {
        CacheManager cacheManager = new CacheManager();
        CacheManager cacheManager2 = CacheManager.getInstance();
        CacheManager cacheManager3 = CacheManager.getInstance();
        assertTrue(cacheManager != cacheManager2);
        assertTrue(cacheManager2 == cacheManager3);
    }


    /**
     * CacheManager requires a resource called javax.cache.CacheFactory containing the fully
     * qualified class name of a cache factory be at /META-INF/services/javax.cache.CacheFactory.
     *
     * @throws CacheException
     */
    public void testLoadCacheFactory() throws CacheException {

        singletonManager = CacheManager.getInstance();
        CacheFactory cacheFactory = singletonManager.getCacheFactory();
        assertNotNull(cacheFactory);
    }


    /**
     * CacheManager requires a resource called javax.cache.CacheFactory containing the fully
     * qualified class name of a cache factory be at /META-INF/services/javax.cache.CacheFactory.
     *
     * Create a cache using found factory
     *
     * @throws CacheException
     */
    public void testCreateCacheFromFactory() throws CacheException {

        singletonManager = CacheManager.getInstance();
        CacheFactory cacheFactory = singletonManager.getCacheFactory();
        assertNotNull(cacheFactory);

        Map config = new HashMap();
        config.put("name", "test");
        config.put("maxElementsInMemory", "10");
        config.put("memoryStoreEvictionPolicy", "LFU");
        config.put("overflowToDisk", "true");
        config.put("eternal", "false");
        config.put("timeToLiveSeconds", "5");
        config.put("timeToIdleSeconds", "5");
        config.put("diskPersistent", "false");
        config.put("diskExpiryThreadIntervalSeconds", "120");

        Cache cache = cacheFactory.createCache(config);
        assertNotNull(cache);
        javax.servlet.http.HttpSessionAttributeListener

    }

    /**
     * Tests that the CacheManager was successfully created
     */
    public void testCreateCacheManager() throws net.sf.ehcache.CacheException {
        singletonManager = CacheManager.getInstance();
        CacheManager singletonManager2 = CacheManager.getInstance();
        assertNotNull(singletonManager);
        assertEquals(singletonManager, singletonManager2);
    }


    /**
     * Tests that the CacheManager was successfully created
     */
    public void testRegisterCache() throws net.sf.ehcache.CacheException {
        singletonManager = CacheManager.getInstance();
        Ehcache ehcache = new net.sf.ehcache.Cache("name", 10, MemoryStoreEvictionPolicy.LFU,
                false, null, false, 10, 10, false, 60, null);
        singletonManager.registerCache("test", new JCache(ehcache));
        Cache cache = singletonManager.getCache("test");
        assertNotNull(cache);
    }


}
