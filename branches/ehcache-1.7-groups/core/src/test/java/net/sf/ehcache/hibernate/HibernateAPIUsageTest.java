/**
 *  Copyright 2003-2008 Luck Consulting Pty Ltd
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

package net.sf.ehcache.hibernate;

import net.sf.ehcache.AbstractCacheTest;
import net.sf.ehcache.CacheException;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.CacheTest;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.Element;
import org.hibernate.cfg.Environment;
import org.junit.After;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Test;

import java.io.IOException;
import java.io.Serializable;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;


/**
 * Tests for a Cache
 *
 * @author Greg Luck, Claus Ibsen
 * @version $Id$
 */
public class HibernateAPIUsageTest extends AbstractCacheTest {
    private static final Logger LOG = Logger.getLogger(CacheTest.class.getName());
    private static final int EMPTY_ELEMENT_SIZE = 298;


    /**
     * teardown
     */
    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }


    /**
     * Make sure ehcache works with one of the main projects using it: Hibernate-2.1.8
     */
    @Test
    public void testAPIAsUsedByHibernate2() throws net.sf.hibernate.cache.CacheException {
        net.sf.hibernate.cache.EhCacheProvider provider = new net.sf.hibernate.cache.EhCacheProvider();
        provider.start(null);
        net.sf.hibernate.cache.Cache cache = provider.buildCache("sampleCache1", null);
        assertNotNull(manager.getCache("sampleCache1"));

        Serializable key = "key";
        Serializable value = "value";
        cache.put(key, value);
        assertEquals(value, cache.get(key));

        cache.remove(key);
        assertEquals(null, cache.get(key));
    }


    /**
     * Make sure ehcache works with one of the main projects using it: Hibernate-3.1.3 and Hibernate 3.2
     * Note this test was updated to Hibernate3.2cr2 9 May 2006
     * <p/>
     * Note that getElementCountInMemory() is broken. It reports the total cache size rather than the memory size. Fixed in Hibernate 3.2
     * getTimeout appears to be broken. It returns 4096 minutes!
     */
    @Test
    public void testAPIAsUsedByHibernate3() {

        /*Shutdown cache manager so that hibernate can start one using the same ehcache.xml disk path
          because it does not use the singleton CacheManager any more */
        manager.shutdown();

        org.hibernate.cache.EhCacheProvider provider = new org.hibernate.cache.EhCacheProvider();
        provider.start(null);
        org.hibernate.cache.Cache cache = provider.buildCache("sampleCache1", null);

        //Check created and name
        assertNotNull(cache.getRegionName());
        assertEquals("sampleCache1", cache.getRegionName());

        Serializable key = "key";
        Serializable value = "value";

        cache.put(key, value);
        assertEquals(value, cache.get(key));
        assertEquals(value, cache.read(key));

        cache.remove(key);
        assertEquals(null, cache.get(key));

        //Behaves like a put
        cache.update(key, value);
        assertEquals(value, cache.get(key));
        cache.remove(key);

        //Check counts and stats
        for (int i = 0; i < 10010; i++) {
            cache.put("" + i, value);
        }
        //this is now fixed
        assertEquals(10000, cache.getElementCountInMemory());
        assertEquals(10, cache.getElementCountOnDisk());

        //clear
        cache.clear();
        assertEquals(0, cache.getElementCountInMemory());
        cache.put(key, value);
        assertTrue(EMPTY_ELEMENT_SIZE == cache.getSizeInMemory());

        //locks
        //timeout. This seems strange
        assertEquals(245760000, cache.getTimeout());
        cache.lock(key);
        cache.unlock(key);

        //toMap
        Map map = cache.toMap();
        assertEquals(1, map.size());
        assertEquals(value, map.get(key));

        long time1 = cache.nextTimestamp();
        long time2 = cache.nextTimestamp();
        assertTrue(time2 > time1);

        cache.clear();

        cache.destroy();
        try {
            cache.get(key);
            fail();
        } catch (IllegalStateException e) {
            //expected
        }

        provider.stop();

    }


    /**
     * Test new features:
     * <ol>
     * <li>Support for Object signatures
     * <li>support for multiple SessionFactory objects in Hibernate, which presumably mean multiple providers.
     * We can have two caches of the same name in different providers and interact with both
     * </ol>
     */
    @Test
    public void testNewHibernate32CacheAndProviderNewFeatures() {

        /*Shutdown cache manager so that hibernate can start one using the same cache.xml disk path
          because it does not use the singleton CacheManager any more */
        manager.shutdown();

        org.hibernate.cache.EhCacheProvider provider = new org.hibernate.cache.EhCacheProvider();
        provider.start(null);
        org.hibernate.cache.Cache cache = provider.buildCache("sampleCache1", null);

        //start up second provider pointing to ehcache-failsage.xml because it is there
        org.hibernate.cache.EhCacheProvider provider2 = new org.hibernate.cache.EhCacheProvider();

        //Fire up a second provider, CacheManager and cache concurrently
        Properties properties = new Properties();

        properties.setProperty(Environment.CACHE_PROVIDER_CONFIG, "ehcache-2.xml");
        provider2.start(properties);
        org.hibernate.cache.Cache cache2 = provider.buildCache("sampleCache1", null);

        //Check created and name
        assertNotNull(cache.getRegionName());
        assertEquals("sampleCache1", cache.getRegionName());

        //Test with Object rather than Serializable
        Object key = new Object();
        Object value = new Object();

        cache.put(key, value);
        assertEquals(value, cache.get(key));
        assertEquals(value, cache.read(key));
        cache2.put(key, value);
        assertEquals(value, cache2.get(key));
        assertEquals(value, cache2.read(key));

        cache.remove(key);
        assertEquals(null, cache.get(key));
        cache2.remove(key);
        assertEquals(null, cache2.get(key));

        //Behaves like a put
        cache.update(key, value);
        assertEquals(value, cache.get(key));
        cache.remove(key);
        cache2.update(key, value);
        assertEquals(value, cache2.get(key));
        cache2.remove(key);

        //Check counts and stats
        for (int i = 0; i < 10010; i++) {
            cache.put("" + i, value);
        }
        assertEquals(10000, cache.getElementCountInMemory());
        //objects don't overflow, only Serializable
        assertEquals(0, cache.getElementCountOnDisk());

        //clear
        cache.clear();
        assertEquals(0, cache.getElementCountInMemory());
        cache.put(key, value);
        //Not Serializable therefore unmeasurable using ehcache's estimation algorithm
        assertTrue(0 == cache.getSizeInMemory());

        //locks
        //timeout. This seems strange
        assertEquals(245760000, cache.getTimeout());
        cache.lock(key);
        cache.unlock(key);

        //toMap - broken in Hibernate 3.2
//        Map map = cache.toMap();
//        assertEquals(1, map.size());
//        assertEquals(value, map.get(key));

        long time1 = cache.nextTimestamp();
        long time2 = cache.nextTimestamp();
        assertTrue(time2 > time1);

        cache.destroy();
        try {
            cache.get(key);
            fail();
        } catch (IllegalStateException e) {
            //expected
        }

        cache2.destroy();
        try {
            cache2.get(key);
            fail();
        } catch (IllegalStateException e) {
            //expected
        }

        provider.stop();
        provider2.stop();
    }


    /**
     * Test ehcache packaged provider and EhCache with Hibernate-3.1.3
     * Leave broken timeout until get clarification from Emmanuel
     */
    @Test
    public void testNewHibernateEhcacheAndProviderBackwardCompatible() {

        /*Shutdown cache manager so that hibernate can start one using the same cache.xml disk path
          because it does not use the singleton CacheManager any more */
        manager.shutdown();

        net.sf.ehcache.hibernate.EhCacheProvider provider = new net.sf.ehcache.hibernate.EhCacheProvider();

        //Fire up a second provider, CacheManager and cache concurrently
        Properties properties = new Properties();

        properties.setProperty("net.sf.ehcache.configurationResourceName", "ehcache-2.xml");
        provider.start(properties);
        org.hibernate.cache.Cache cache = provider.buildCache("sampleCache1", null);

        //Check created and name
        assertNotNull(cache.getRegionName());
        assertEquals("sampleCache1", cache.getRegionName());

        Serializable key = "key";
        Serializable value = "value";

        cache.put(key, value);
        assertEquals(value, cache.get(key));
        assertEquals(value, cache.read(key));

        cache.remove(key);
        assertEquals(null, cache.get(key));

        //Behaves like a put
        cache.update(key, value);
        assertEquals(value, cache.get(key));
        cache.remove(key);

        //Check counts and stats
        for (int i = 0; i < 10010; i++) {
            cache.put("" + i, value);
        }
        assertEquals(10000, cache.getElementCountInMemory());
        assertEquals(10, cache.getElementCountOnDisk());

        //clear
        cache.clear();
        assertEquals(0, cache.getElementCountInMemory());
        cache.put(key, value);
        assertTrue(EMPTY_ELEMENT_SIZE == cache.getSizeInMemory());

        //locks
        //timeout. This seems strange
        assertEquals(245760000, cache.getTimeout());
        cache.lock(key);
        cache.unlock(key);

        //toMap
        Map map = cache.toMap();
        assertEquals(1, map.size());
        assertEquals(value, map.get(key));

        long time1 = cache.nextTimestamp();
        long time2 = cache.nextTimestamp();
        assertTrue(time2 > time1);

        cache.destroy();
        try {
            cache.get(key);
            fail();
        } catch (IllegalStateException e) {
            //expected
        }

        ((net.sf.ehcache.hibernate.EhCache) cache).getBackingCache().getCacheManager().shutdown();


    }


    /**
     * An integration test, at the CacheManager level, to make sure persistence works
     */
    @Test
    public void testPersistentStoreFromCacheManager() throws IOException, InterruptedException, CacheException {

        manager.shutdown();

        //initialise
        CacheManager manager = CacheManager.create(AbstractCacheTest.TEST_CONFIG_DIR + "ehcache.xml");
        Ehcache cache = manager.getCache("persistentLongExpiryIntervalCache");

        for (int i = 0; i < 100; i++) {
            byte[] data = new byte[1024];
            cache.put(new Element("key" + (i + 100), data));
        }
        assertEquals(100, cache.getSize());

        manager.shutdown();

        net.sf.ehcache.hibernate.EhCacheProvider provider = new net.sf.ehcache.hibernate.EhCacheProvider();
        provider.start(null);
        org.hibernate.cache.Cache hibernateCache = provider.buildCache("persistentLongExpiryIntervalCache", null);

        assertEquals(100, hibernateCache.getElementCountInMemory() + hibernateCache.getElementCountOnDisk());

        provider.stop();


    }


    /**
     * An integration test, at the CacheManager level, to make sure persistence works
     */
    @Test
    public void testPersistentStoreFromCacheManagerUsingHibernate321Provider() throws Exception {

        manager.shutdown();

        //initialise
        CacheManager manager = CacheManager.create(AbstractCacheTest.TEST_CONFIG_DIR + "ehcache.xml");
        Ehcache cache = manager.getCache("persistentLongExpiryIntervalCache");
        cache.removeAll();

        for (int i = 0; i < 100; i++) {
            byte[] data = new byte[1024];
            cache.put(new Element("key" + (i + 100), data));
        }
        assertEquals(100, cache.getSize());

        manager.shutdown();

        //Create hibernate using ehcache
        org.hibernate.cache.EhCacheProvider provider = new org.hibernate.cache.EhCacheProvider();
        provider.start(null);
        org.hibernate.cache.Cache hibernateCache = provider.buildCache("persistentLongExpiryIntervalCache", null);

        assertEquals(100, hibernateCache.getElementCountInMemory() + hibernateCache.getElementCountOnDisk());

        provider.stop();

    }

    /**
     * Test ehcache packaged provider and EhCache with Hibernate-3.1.3
     * Leave broken timeout until get clarification from Emmanuel
     * <p/>
     * Test new features:
     * <ol>
     * <li>Support for Object signatures
     * <li>support for multiple SessionFactory objects in Hibernate, which presumably mean multiple providers.
     * We can have two caches of the same name in different providers and interact with both
     * </ol>
     */
    @Test
    public void testNewHibernateEhcacheAndProviderNewFeatures() {

        /*Shutdown cache manager so that hibernate can start one using the same ehcache.xml disk path
          because it does not use the singleton CacheManager any more */
        manager.shutdown();

        net.sf.ehcache.hibernate.EhCacheProvider provider = new net.sf.ehcache.hibernate.EhCacheProvider();
        provider.start(null);
        org.hibernate.cache.Cache cache = provider.buildCache("sampleCache1", null);

        //start up second provider pointing to ehcache-failsafe.xml because it is there
        net.sf.ehcache.hibernate.EhCacheProvider provider2 = new net.sf.ehcache.hibernate.EhCacheProvider();

        //Fire up a second provider, CacheManager and cache concurrently
        Properties properties = new Properties();
        properties.setProperty(EhCacheProvider.NET_SF_EHCACHE_CONFIGURATION_RESOURCE_NAME, "ehcache-2.xml");
        provider2.start(properties);
        org.hibernate.cache.Cache cache2 = provider.buildCache("sampleCache1", null);

        //Check created and name
        assertNotNull(cache.getRegionName());
        assertEquals("sampleCache1", cache.getRegionName());

        //Test with Object rather than Serializable
        Object key = new Object();
        Object value = new Object();

        cache.put(key, value);
        assertEquals(value, cache.get(key));
        assertEquals(value, cache.read(key));
        cache2.put(key, value);
        assertEquals(value, cache2.get(key));
        assertEquals(value, cache2.read(key));

        cache.remove(key);
        assertEquals(null, cache.get(key));
        cache2.remove(key);
        assertEquals(null, cache2.get(key));

        //Behaves like a put
        cache.update(key, value);
        assertEquals(value, cache.get(key));
        cache.remove(key);
        cache2.update(key, value);
        assertEquals(value, cache2.get(key));
        cache2.remove(key);

        //Check counts and stats
        for (int i = 0; i < 10010; i++) {
            cache.put("" + i, value);
        }
        assertEquals(10000, cache.getElementCountInMemory());
        //objects don't overflow, only Serializable
        assertEquals(0, cache.getElementCountOnDisk());

        //clear
        cache.clear();
        assertEquals(0, cache.getElementCountInMemory());
        cache.put(key, value);
        //Not Serializable therefore unmeasurable using ehcache's estimation algorithm
        assertTrue(0 == cache.getSizeInMemory());

        //locks
        //timeout. This seems strange
        assertEquals(245760000, cache.getTimeout());
        cache.lock(key);
        cache.unlock(key);

        //toMap
        Map map = cache.toMap();
        assertEquals(1, map.size());
        assertEquals(value, map.get(key));

        long time1 = cache.nextTimestamp();
        long time2 = cache.nextTimestamp();
        assertTrue(time2 > time1);

        cache.destroy();
        try {
            cache.get(key);
            fail();
        } catch (IllegalStateException e) {
            //expected
        }

        cache2.destroy();
        try {
            cache2.get(key);
            fail();
        } catch (IllegalStateException e) {
            //expected
        }

        ((net.sf.ehcache.hibernate.EhCache) cache).getBackingCache().getCacheManager().shutdown();
    }

    /**
     * Test ehcache packaged provider and EhCache with Hibernate-3.1.3
     * Leave broken timeout until get clarification from Emmanuel
     * <p/>
     * Test new features:
     * <ol>
     * <li>Support for Object signatures
     * </ol>
     */
    @Test
    public void testNewHibernateSingletonEhcacheAndProviderNewFeatures() {

        /*Shutdown cache manager so that hibernate can start one using the same ehcache.xml disk path
          because it does not use the singleton CacheManager any more */
        manager.shutdown();

        net.sf.ehcache.hibernate.SingletonEhCacheProvider provider = new net.sf.ehcache.hibernate.SingletonEhCacheProvider();
        provider.start(null);
        org.hibernate.cache.Cache cache = provider.buildCache("sampleCache1", null);

        net.sf.ehcache.hibernate.SingletonEhCacheProvider provider2 = new net.sf.ehcache.hibernate.SingletonEhCacheProvider();
        provider2.start(null);
        org.hibernate.cache.Cache cache2 = provider.buildCache("sampleCache1", null);

        //Check created and name
        assertNotNull(cache.getRegionName());
        assertEquals("sampleCache1", cache.getRegionName());

        //Test with Object rather than Serializable
        Object key = new Object();
        Object value = new Object();

        cache.put(key, value);
        assertEquals(value, cache2.get(key));
        assertEquals(value, cache.read(key));
        cache2.put(key, value);
        assertEquals(value, cache.get(key));
        assertEquals(value, cache2.read(key));

        cache.remove(key);
        assertEquals(null, cache.get(key));
        cache2.remove(key);
        assertEquals(null, cache2.get(key));

        //Behaves like a put
        cache.update(key, value);
        assertEquals(value, cache.get(key));
        cache.remove(key);
        cache2.update(key, value);
        assertEquals(value, cache2.get(key));
        cache2.remove(key);

        //Check counts and stats
        for (int i = 0; i < 10010; i++) {
            cache.put("" + i, value);
        }
        assertEquals(10000, cache.getElementCountInMemory());
        //objects don't overflow, only Serializable
        assertEquals(0, cache.getElementCountOnDisk());

        //clear
        cache.clear();
        assertEquals(0, cache.getElementCountInMemory());
        cache.put(key, value);
        //Not Serializable therefore unmeasurable using ehcache's estimation algorithm
        assertTrue(0 == cache.getSizeInMemory());

        //locks
        //timeout. This seems strange
        assertEquals(245760000, cache.getTimeout());
        cache.lock(key);
        cache.unlock(key);

        //toMap
        Map map = cache.toMap();
        assertEquals(1, map.size());
        assertEquals(value, map.get(key));

        long time1 = cache.nextTimestamp();
        long time2 = cache.nextTimestamp();
        assertTrue(time2 > time1);

        cache.destroy();
        try {
            cache.get(key);
            fail();
        } catch (IllegalStateException e) {
            //expected
        }

        cache2.destroy();
        //second destroy ok
        cache2.destroy();

        try {
            cache2.get(key);
            fail();
        } catch (IllegalStateException e) {
            //expected
        }


        ((net.sf.ehcache.hibernate.EhCache) cache).getBackingCache().getCacheManager().shutdown();
        ((net.sf.ehcache.hibernate.EhCache) cache).getBackingCache().getCacheManager().shutdown();
        ((net.sf.ehcache.hibernate.EhCache) cache2).getBackingCache().getCacheManager().shutdown();

        //Spring and Hibernate together can call destroy after the CacheManager has been shutdown
        //See bug 1901094. We need to deal with this as "normal".
        cache2.destroy();
    }
}
