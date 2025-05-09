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

package net.sf.ehcache.distribution;

import junit.framework.TestCase;
import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheException;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Element;
import net.sf.ehcache.AbstractCacheTest;
import net.sf.ehcache.StopWatch;
import net.sf.ehcache.event.CountingCacheEventListener;

import java.io.Serializable;
import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Tests replication of Cache events
 * <p/>
 * Note these tests need a live network interface running in multicast mode to work
 *
 * @author Greg Luck
 * @version $Id$
 */
public class RMICacheReplicatorTest extends TestCase {

    private static final Log LOG = LogFactory.getLog(RMICacheReplicatorTest.class.getName());

    private static final boolean ASYNCHRONOUS = true;
    private static final boolean SYNCHRONOUS = false;

    /**
     * CacheManager 1 in the cluster
     */
    protected CacheManager manager1;
    /**
     * CacheManager 2 in the cluster
     */
    protected CacheManager manager2;
    /**
     * CacheManager 3 in the cluster
     */
    protected CacheManager manager3;
    /**
     * CacheManager 4 in the cluster
     */
    protected CacheManager manager4;
    /**
     * CacheManager 5 in the cluster
     */
    protected CacheManager manager5;
    /**
     * CacheManager 6 in the cluster
     */
    protected CacheManager manager6;

    /**
     * The name of the cache under test
     */
    protected String cacheName = "sampleCache1";
    /**
     * CacheManager 1 of 2s cache being replicated
     */
    protected Cache cache1;

    /**
     * CacheManager 2 of 2s cache being replicated
     */
    protected Cache cache2;


    /**
     * {@inheritDoc}
     * Sets up two caches: cache1 is local. cache2 is to be receive updates
     *
     * @throws Exception
     */
    protected void setUp() throws Exception {
        if (DistributionUtil.isSingleRMIRegistryPerVM()) {
            return;
        }

        CountingCacheEventListener.resetCounters();
        manager1 = new CacheManager(AbstractCacheTest.TEST_CONFIG_DIR + "distribution/ehcache-distributed1.xml");
        manager2 = new CacheManager(AbstractCacheTest.TEST_CONFIG_DIR + "distribution/ehcache-distributed2.xml");
        manager3 = new CacheManager(AbstractCacheTest.TEST_CONFIG_DIR + "distribution/ehcache-distributed3.xml");
        manager4 = new CacheManager(AbstractCacheTest.TEST_CONFIG_DIR + "distribution/ehcache-distributed4.xml");
        manager5 = new CacheManager(AbstractCacheTest.TEST_CONFIG_DIR + "distribution/ehcache-distributed5.xml");

        manager1.getCache(cacheName).removeAll();

        cache1 = manager1.getCache(cacheName);
        cache1.removeAll();

        cache2 = manager2.getCache(cacheName);
        cache2.removeAll();

        //allow cluster to be established
        Thread.sleep(500);

    }


    /**
     * {@inheritDoc}
     *
     * @throws Exception
     */
    protected void tearDown() throws Exception {

        if (DistributionUtil.isSingleRMIRegistryPerVM()) {
            return;
        }

        if (manager1 != null) {
            manager1.shutdown();
        }
        if (manager2 != null) {
            manager2.shutdown();
        }
        if (manager3 != null) {
            manager3.shutdown();
        }
        if (manager4 != null) {
            manager4.shutdown();
        }

        if (manager5 != null) {
            manager5.shutdown();
        }

        if (manager6 != null) {
            manager6.shutdown();
        }

    }

    /**
     * 5 cache managers should means that each cache has four remote peers
     */
    public void testRemoteCachePeersEqualsNumberOfCacheManagersInCluster() {

        if (DistributionUtil.isSingleRMIRegistryPerVM()) {
            return;
        }


        CacheManagerPeerProvider provider = manager1.getCachePeerProvider();
        List remotePeersOfCache1 = provider.listRemoteCachePeers(cache1);
        assertEquals(4, remotePeersOfCache1.size());
    }

    /**
     * Does a new cache manager in the cluster get detected?
     */
    public void testRemoteCachePeersDetectsNewCacheManager() throws InterruptedException {

        if (DistributionUtil.isSingleRMIRegistryPerVM()) {
            return;
        }

        CacheManagerPeerProvider provider = manager1.getCachePeerProvider();
        List remotePeersOfCache1 = provider.listRemoteCachePeers(cache1);
        assertEquals(4, remotePeersOfCache1.size());

        //Add new CacheManager to cluster
        manager6 = new CacheManager(AbstractCacheTest.TEST_CONFIG_DIR + "distribution/ehcache-distributed6.xml");

        //Allow detection to occur
        Thread.sleep(1010);

        remotePeersOfCache1 = provider.listRemoteCachePeers(cache1);
        assertEquals(5, remotePeersOfCache1.size());
    }

    /**
     * Does a down cache manager in the cluster get removed?
     */
    public void testRemoteCachePeersDetectsDownCacheManager() throws InterruptedException {

        if (DistributionUtil.isSingleRMIRegistryPerVM()) {
            return;
        }


        CacheManagerPeerProvider provider = manager1.getCachePeerProvider();
        List remotePeersOfCache1 = provider.listRemoteCachePeers(cache1);
        assertEquals(4, remotePeersOfCache1.size());

        //Drop a CacheManager from the cluster
        manager5.shutdown();

        //Allow change detection to occur. Heartbeat 1 second and is not stale until 5000
        Thread.sleep(6010);
        remotePeersOfCache1 = provider.listRemoteCachePeers(cache1);


        assertEquals(3, remotePeersOfCache1.size());
    }

    /**
     * Does a down cache manager in the cluster get removed?
     */
    public void testRemoteCachePeersDetectsDownCacheManagerSlow() throws InterruptedException {

        if (DistributionUtil.isSingleRMIRegistryPerVM()) {
            return;
        }

        CacheManagerPeerProvider provider = manager1.getCachePeerProvider();
        List remotePeersOfCache1 = provider.listRemoteCachePeers(cache1);
        assertEquals(4, remotePeersOfCache1.size());

        //Drop a CacheManager from the cluster
        manager5.shutdown();

        //still works because the lookup fails
        remotePeersOfCache1 = provider.listRemoteCachePeers(cache1);
        assertEquals(3, remotePeersOfCache1.size());
    }

    /**
     * Tests put and remove initiated from cache1 in a cluster
     * <p/>
     * This test goes into an infinite loop if the chain of notifications is not somehow broken.
     */
    public void testPutProgagatesFromAndToEveryCacheManagerAndCache() throws CacheException, InterruptedException {

        if (DistributionUtil.isSingleRMIRegistryPerVM()) {
            return;
        }

        //Put
        String[] cacheNames = manager1.getCacheNames();
        Arrays.sort(cacheNames);
        for (int i = 0; i < cacheNames.length; i++) {
            String name = cacheNames[i];
            manager1.getCache(name).put(new Element("" + i, new Integer(i)));
            //Add some non serializable elements that should not get propagated
            manager1.getCache(name).put(new Element("nonSerializable" + i, new Object()));
        }

        waitForProgagate();

        int count2 = 0;
        int count3 = 0;
        int count4 = 0;
        int count5 = 0;
        for (int i = 0; i < cacheNames.length; i++) {
            String name = cacheNames[i];
            Element element2 = manager2.getCache(name).get("" + i);
            if (element2 != null) {
                count2++;
            }
            Element element3 = manager3.getCache(name).get("" + i);
            if (element3 != null) {
                count3++;
            }
            Element element4 = manager4.getCache(name).get("" + i);
            if (element4 != null) {
                count4++;
            }
            Element element5 = manager5.getCache(name).get("" + i);
            if (element5 != null) {
                count5++;
            }
            //LOG.debug("element propagated to cache named " + name + ": " + element);
        }
        assertEquals(55, count2);
        assertEquals(55, count3);
        assertEquals(55, count4);
        assertEquals(55, count5);

    }

    /**
     * See what happens when we send a 2000 puts through at the same time.
     * <p/>
     * Running a remote cache peer on a separate computer takes 28 seconds to get all notifications.
     */
    public void testBigPutsProgagates() throws CacheException, InterruptedException {
        //Give everything a chance to startup
        Thread.sleep(1000);
        StopWatch stopWatch = new StopWatch();
        Integer index = null;
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 1000; j++) {
                index = new Integer(((1000 * i) + j));
                cache1.put(new Element(index,
                        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
            }
        }
        long elapsed = stopWatch.getElapsedTime();
        long putTime = ((elapsed / 1000));
        LOG.info("Put Elapsed time: " + putTime);
        //assertTrue(putTime < 8);

        assertEquals(2000, cache1.getSize());

        Thread.sleep(40000);
        assertEquals(2000, manager2.getCache("sampleCache1").getSize());
        assertEquals(2000, manager3.getCache("sampleCache1").getSize());
        assertEquals(2000, manager4.getCache("sampleCache1").getSize());
        assertEquals(2000, manager5.getCache("sampleCache1").getSize());

    }


    /**
     * Test various cache configurations for cache1 - explicit setting of:
     * properties="replicateAsynchronously=true, replicatePuts=true, replicateUpdates=true, replicateUpdatesViaCopy=true, replicateRemovals=true "/>
     */
    public void testPutWithExplicitReplicationConfig() throws InterruptedException {
        putTest(manager1.getCache("sampleCache1"), manager2.getCache("sampleCache1"), ASYNCHRONOUS);
    }

    /**
     * Test various cache configurations for cache1 - explicit setting of:
     * properties="replicateAsynchronously=false, replicatePuts=true, replicateUpdates=true, replicateUpdatesViaCopy=true, replicateRemovals=true "/>
     */
    public void testPutWithExplicitReplicationSynchronousConfig() throws InterruptedException {
        putTest(manager1.getCache("sampleCache3"), manager2.getCache("sampleCache3"), SYNCHRONOUS);
    }


    /**
     * Test put replicated for cache4 - no properties.
     * Defaults should be replicateAsynchronously=true, replicatePuts=true, replicateUpdates=true, replicateUpdatesViaCopy=true, replicateRemovals=true
     */
    public void testPutWithEmptyReplicationPropertiesConfig() throws InterruptedException {
        putTest(manager1.getCache("sampleCache4"), manager2.getCache("sampleCache4"), ASYNCHRONOUS);
    }

    /**
     * Test put replicated for cache4 - missing replicatePuts property.
     * replicateAsynchronously=true, replicatePuts=true, replicateUpdates=true, replicateUpdatesViaCopy=true, replicateRemovals=true
     * should equal replicateAsynchronously=true, replicateUpdates=true, replicateUpdatesViaCopy=true, replicateRemovals=true
     */
    public void testPutWithOneMissingReplicationPropertyConfig() throws InterruptedException {
        putTest(manager1.getCache("sampleCache5"), manager2.getCache("sampleCache5"), ASYNCHRONOUS);
    }


    /**
     * Tests put and remove initiated from cache1 in a cluster
     * <p/>
     * This test goes into an infinite loop if the chain of notifications is not somehow broken.
     */
    public void putTest(Cache fromCache, Cache toCache, boolean asynchronous) throws CacheException, InterruptedException {

        if (DistributionUtil.isSingleRMIRegistryPerVM()) {
            return;
        }

        Serializable key = new Date();
        Serializable value = new Date();
        Element sourceElement = new Element(key, value);

        //Put
        fromCache.put(sourceElement);
        int i = 0;

        if (asynchronous) {
            waitForProgagate();
        }

        //Should have been replicated to toCache.
        Element deliveredElement = toCache.get(key);
        assertEquals(sourceElement, deliveredElement);

    }

    /**
     * Checks that a put received from a remote cache notifies any registered listeners.
     * <p/>
     * This test goes into an infinite loop if the chain of notifications is not somehow broken.
     */
    public void testRemotePutNotificationGetsToOtherListeners() throws CacheException, InterruptedException {

        if (DistributionUtil.isSingleRMIRegistryPerVM()) {
            return;
        }

        Serializable key = new Date();
        Serializable value = new Date();
        Element element1 = new Element(key, value);

        //Put
        cache1.put(new Element("1", new Date()));
        cache1.put(new Element("2", new Date()));
        cache1.put(new Element("3", new Date()));

        //Nonserializable and non deliverable put
        Object nonSerializableObject = new Object();
        cache1.put(new Element(nonSerializableObject, new Object()));


        waitForProgagate();

        //local initiating cache's counting listener should have been notified
        assertEquals(4, CountingCacheEventListener.getCacheElementsPut(cache1).size());
        //remote receiving caches' counting listener should have been notified
        assertEquals(3, CountingCacheEventListener.getCacheElementsPut(cache2).size());

        //Update
        cache1.put(new Element("1", new Date()));
        cache1.put(new Element("2", new Date()));
        cache1.put(new Element("3", new Date()));

        //Nonserializable and non deliverable put
        cache1.put(new Element(nonSerializableObject, new Object()));

        waitForProgagate();

        //local initiating cache's counting listener should have been notified
        assertEquals(4, CountingCacheEventListener.getCacheElementsUpdated(cache1).size());
        //remote receiving caches' counting listener should have been notified
        assertEquals(3, CountingCacheEventListener.getCacheElementsUpdated(cache2).size());

        //Remove
        cache1.remove("1");
        cache1.remove("2");
        cache1.remove("3");
        cache1.remove(nonSerializableObject);

        waitForProgagate();

        //local initiating cache's counting listener should have been notified
        assertEquals(4, CountingCacheEventListener.getCacheElementsRemoved(cache1).size());
        //remote receiving caches' counting listener should have been notified
        assertEquals(3, CountingCacheEventListener.getCacheElementsRemoved(cache2).size());

    }


    /**
     * Test various cache configurations for cache1 - explicit setting of:
     * properties="replicateAsynchronously=true, replicatePuts=true, replicateUpdates=true, replicateUpdatesViaCopy=true, replicateRemovals=true "/>
     */
    public void testRemoveWithExplicitReplicationConfig() throws InterruptedException {
        removeTest(manager1.getCache("sampleCache1"), manager2.getCache("sampleCache1"), ASYNCHRONOUS);
    }

    /**
     * Test various cache configurations for cache1 - explicit setting of:
     * properties="replicateAsynchronously=true, replicatePuts=true, replicateUpdates=true, replicateUpdatesViaCopy=true, replicateRemovals=true "/>
     */
    public void testRemoveWithExplicitReplicationSynchronousConfig() throws InterruptedException {
        removeTest(manager1.getCache("sampleCache3"), manager2.getCache("sampleCache3"), SYNCHRONOUS);
    }


    /**
     * Test put replicated for cache4 - no properties.
     * Defaults should be replicateAsynchronously=true, replicatePuts=true, replicateUpdates=true, replicateUpdatesViaCopy=true, replicateRemovals=true
     */
    public void testRemoveWithEmptyReplicationPropertiesConfig() throws InterruptedException {
        removeTest(manager1.getCache("sampleCache4"), manager2.getCache("sampleCache4"), ASYNCHRONOUS);
    }

    /**
     * Tests put and remove initiated from a cache to another cache in a cluster
     * <p/>
     * This test goes into an infinite loop if the chain of notifications is not somehow broken.
     */
    public void removeTest(Cache fromCache, Cache toCache, boolean asynchronous) throws CacheException, InterruptedException {

        if (DistributionUtil.isSingleRMIRegistryPerVM()) {
            return;
        }

        Serializable key = new Date();
        Serializable value = new Date();
        Element element1 = new Element(key, value);

        //Put
        fromCache.put(element1);

        if (asynchronous) {
            waitForProgagate();
        }

        //Should have been replicated to cache2.
        Element element2 = toCache.get(key);
        assertEquals(element1, element2);

        //Remove
        fromCache.remove(key);
        if (asynchronous) {
            waitForProgagate();
        }

        //Should have been replicated to cache2.
        element2 = toCache.get(key);
        assertNull(element2);

    }


    /**
     * Test various cache configurations for cache1 - explicit setting of:
     * properties="replicateAsynchronously=true, replicatePuts=true, replicateUpdates=true, replicateUpdatesViaCopy=true, replicateRemovals=true "/>
     */
    public void testUpdateWithExplicitReplicationConfig() throws Exception {
        updateViaCopyTest(manager1.getCache("sampleCache1"), manager2.getCache("sampleCache1"), ASYNCHRONOUS);
    }

    /**
     * Test various cache configurations for cache1 - explicit setting of:
     * properties="replicateAsynchronously=true, replicatePuts=true, replicateUpdates=true, replicateUpdatesViaCopy=true, replicateRemovals=true "/>
     */
    public void testUpdateWithExplicitReplicationSynchronousConfig() throws Exception {
        updateViaCopyTest(manager1.getCache("sampleCache3"), manager2.getCache("sampleCache3"), SYNCHRONOUS);
    }


    /**
     * Test put replicated for cache4 - no properties.
     * Defaults should be replicateAsynchronously=true, replicatePuts=true, replicateUpdates=true, replicateUpdatesViaCopy=true, replicateRemovals=true
     */
    public void testUpdateWithEmptyReplicationPropertiesConfig() throws Exception {
        updateViaCopyTest(manager1.getCache("sampleCache4"), manager2.getCache("sampleCache4"), ASYNCHRONOUS);
    }

    /**
     * Tests put and update through copy initiated from cache1 in a cluster
     * <p/>
     * This test goes into an infinite loop if the chain of notifications is not somehow broken.
     */
    public void updateViaCopyTest(Cache fromCache, Cache toCache, boolean asynchronous) throws Exception {

        if (DistributionUtil.isSingleRMIRegistryPerVM()) {
            return;
        }

        fromCache.removeAll();
        toCache.removeAll();

        Serializable key = new Date();
        Serializable value = new Date();
        Element element1 = new Element(key, value);

        //Put
        fromCache.put(element1);
        if (asynchronous) {
            waitForProgagate();
        }

        //Should have been replicated to cache2.
        Element element2 = toCache.get(key);
        assertEquals(element1, element2);

        //Update
        Element updatedElement1 = new Element(key, new Date());

        fromCache.put(updatedElement1);
        if (asynchronous) {
            waitForProgagate();
        }

        //Should have been replicated to cache2.
        Element receivedUpdatedElement2 = toCache.get(key);
        assertEquals(updatedElement1, receivedUpdatedElement2);

    }


    /**
     * Tests put and update through invalidation initiated from cache1 in a cluster
     * <p/>
     * This test goes into an infinite loop if the chain of notifications is not somehow broken.
     */
    public void testUpdateViaInvalidate() throws CacheException, InterruptedException, IOException {

        if (DistributionUtil.isSingleRMIRegistryPerVM()) {
            return;
        }

        cache1 = manager1.getCache("sampleCache2");
        cache1.removeAll();

        cache2 = manager2.getCache("sampleCache2");
        cache2.removeAll();

        Serializable key = "1";
        Serializable value = new Date();
        Element element1 = new Element(key, value);

        //Put
        cache1.put(element1);
        waitForProgagate();

        //Should have been replicated to cache2.
        Element element2 = cache2.get(key);
        assertEquals(element1, element2);

        //Update
        cache1.put(element1);
        waitForProgagate();

        //Should have been removed in cache2.
        element2 = cache2.get(key);
        assertNull(element2);

    }

    /**
     * What happens when two cache instances replicate to each other and a change is initiated
     */
    public void testInfiniteNotificationsLoop() throws InterruptedException {

        if (DistributionUtil.isSingleRMIRegistryPerVM()) {
            return;
        }

        Serializable key = "1";
        Serializable value = new Date();
        Element element = new Element(key, value);

        //Put
        cache1.put(element);
        waitForProgagate();

        //Should have been replicated to cache2.
        Element element2 = cache2.get(key);
        assertEquals(element, element2);

        //Remove
        cache1.remove(key);
        assertNull(cache1.get(key));

        //Should have been replicated to cache2.
        waitForProgagate();
        element2 = cache2.get(key);
        assertNull(element2);

        //Put into 2
        Element element3 = new Element("3", "ddsfds");
        cache2.put(element3);
        waitForProgagate();
        Element element4 = cache2.get("3");
        assertEquals(element3, element4);

    }


    /**
     * Need to wait for async
     *
     * @throws InterruptedException
     */
    protected void waitForProgagate() throws InterruptedException {
        Thread.sleep(2000);
    }

    /**
     * Need to wait for async
     *
     * @throws InterruptedException
     */
    protected void waitForSlowProgagate() throws InterruptedException {
        Thread.sleep(6000);
    }

}
