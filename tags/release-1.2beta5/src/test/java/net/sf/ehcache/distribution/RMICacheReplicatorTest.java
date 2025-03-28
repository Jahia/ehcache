/* ====================================================================
 * The Apache Software License, Version 1.1
 *
 * Copyright (c) 2003 - 2004 Greg Luck.  All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * 3. The end-user documentation included with the redistribution, if
 *    any, must include the following acknowlegement:
 *       "This product includes software developed by Greg Luck
 *       (http://sourceforge.net/users/gregluck) and contributors.
 *       See http://sourceforge.net/project/memberlist.php?group_id=93232
 *       for a list of contributors"
 *    Alternately, this acknowledgement may appear in the software itself,
 *    if and wherever such third-party acknowlegements normally appear.
 *
 * 4. The names "EHCache" must not be used to endorse or promote products
 *    derived from this software without prior written permission. For written
 *    permission, please contact Greg Luck (gregluck at users.sourceforge.net).
 *
 * 5. Products derived from this software may not be called "EHCache"
 *    nor may "EHCache" appear in their names without prior written
 *    permission of Greg Luck.
 *
 * THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED.  IN NO EVENT SHALL GREG LUCK OR OTHER
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF
 * USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by contributors
 * individuals on behalf of the EHCache project.  For more
 * information on EHCache, please see <http://ehcache.sourceforge.net/>.
 *
 */
package net.sf.ehcache.distribution;

import junit.framework.TestCase;
import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheException;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Element;
import net.sf.ehcache.AbstractCacheTest;
import net.sf.ehcache.event.CountingCacheEventListener;

import java.io.Serializable;
import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * Tests replication of Cache events
 * <p/>
 * Note these tests need a live network interface running in multicast mode to work
 *
 * @author Greg Luck
 * @version $Id: RMICacheReplicatorTest.java,v 1.2 2006/03/12 02:03:59 gregluck Exp $
 */
public class RMICacheReplicatorTest extends TestCase {

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
    public void testPut() throws CacheException, InterruptedException {

        if (DistributionUtil.isSingleRMIRegistryPerVM()) {
            return;
        }

        Serializable key = new Date();
        Serializable value = new Date();
        Element element1 = new Element(key, value);

        //Put
        cache1.put(element1);
        String[] cacheNames = manager1.getCacheNames();
        Arrays.sort(cacheNames);
        for (int i = 0; i < cacheNames.length; i++) {
            String name = cacheNames[i];
            manager1.getCache(name).put(new Element("" + i, new Integer(i)));
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
        assertEquals(53, count2);
        assertEquals(53, count3);
        assertEquals(53, count4);
        assertEquals(53, count5);

        //Should have been replicated to cache2.
        Element element2 = cache2.get(key);
        assertEquals(element1, element2);

        int countingListenerPutCount = CountingCacheEventListener.getCacheElementsPut(cache2).size();
        assertEquals(2, countingListenerPutCount);


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

        waitForProgagate();

        //local initiating cache's counting listener should have been notified
        assertEquals(3, CountingCacheEventListener.getCacheElementsPut(cache1).size());
        //remote receiving caches' counting listener should have been notified
        assertEquals(3, CountingCacheEventListener.getCacheElementsPut(cache2).size());

        //Update
        cache1.put(new Element("1", new Date()));
        cache1.put(new Element("2", new Date()));
        cache1.put(new Element("3", new Date()));

        waitForProgagate();

        //local initiating cache's counting listener should have been notified
        assertEquals(3, CountingCacheEventListener.getCacheElementsUpdated(cache1).size());
        //remote receiving caches' counting listener should have been notified
        assertEquals(3, CountingCacheEventListener.getCacheElementsUpdated(cache2).size());

        //Update
        cache1.remove("1");
        cache1.remove("2");
        cache1.remove("3");

        waitForProgagate();

        //local initiating cache's counting listener should have been notified
        assertEquals(3, CountingCacheEventListener.getCacheElementsRemoved(cache1).size());
        //remote receiving caches' counting listener should have been notified
        assertEquals(3, CountingCacheEventListener.getCacheElementsRemoved(cache2).size());

    }


    /**
     * Tests put and remove initiated from cache1 in a cluster
     * <p/>
     * This test goes into an infinite loop if the chain of notifications is not somehow broken.
     */
    public void testRemove() throws CacheException, InterruptedException {

        if (DistributionUtil.isSingleRMIRegistryPerVM()) {
            return;
        }

        Serializable key = new Date();
        Serializable value = new Date();
        Element element1 = new Element(key, value);

        //Put
        cache1.put(element1);
        waitForProgagate();

        //Should have been replicated to cache2.
        Element element2 = cache2.get(key);
        assertEquals(element1, element2);

        //Remove
        cache1.remove(key);

        waitForProgagate();

        //Should have been replicated to cache2.
        element2 = cache2.get(key);
        assertNull(element2);

    }



    /**
     * Tests put and update through copy initiated from cache1 in a cluster
     * <p/>
     * This test goes into an infinite loop if the chain of notifications is not somehow broken.
     */
    public void testUpdateViaCopy() throws CacheException, InterruptedException, IOException {

        if (DistributionUtil.isSingleRMIRegistryPerVM()) {
            return;
        }

        cache1 = manager1.getCache("sampleCache1");
        cache1.removeAll();

        cache2 = manager2.getCache("sampleCache1");
        cache2.removeAll();

        Serializable key = new Date();
        Serializable value = new Date();
        Element element1 = new Element(key, value);

        //Put
        cache1.put(element1);
        waitForProgagate();

        //Should have been replicated to cache2.
        Element element2 = cache2.get(key);
        assertEquals(element1, element2);

        //Update
        Element updatedElement1 = new Element(key, new Date());

        cache1.put(updatedElement1);
        waitForProgagate();

        //Should have been replicated to cache2.
        Element receivedUpdatedElement2 = cache2.get(key);
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
     * May need to wait if async
     *
     * @throws InterruptedException
     */
    protected void waitForProgagate() throws InterruptedException {
        Thread.sleep(2000);
    }

}
