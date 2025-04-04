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
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Element;
import net.sf.ehcache.AbstractCacheTest;

import java.rmi.Naming;
import java.util.Date;

/**
 *
 * Note these tests need a live network interface running in multicast mode to work
 *
 * @author Greg Luck
 * @version $Id$
 */
public class RMIDistributedCacheTest extends TestCase {


    /**
     * manager
     */
    protected CacheManager manager;
    /**
     * the cache name we wish to test
     */
    private String cacheName1 = "sampleCache1";
    private String cacheName2 = "sampleCache2";
    /**
     * the cache we wish to test
     */
    private Cache sampleCache1;
    private Cache sampleCache2;


    private String hostName = "localhost";

    private Integer port = new Integer(40000);
    private Element element;
    private CachePeer cache1Peer;
    private CachePeer cache2Peer;

    /**
     * {@inheritDoc}
     *
     * @throws Exception
     */
    protected void setUp() throws Exception {
        if (DistributionUtil.isSingleRMIRegistryPerVM()) {
            return;
        }

        manager = CacheManager.create(AbstractCacheTest.TEST_CONFIG_DIR + "distribution/ehcache-distributed1.xml");
        sampleCache1 = manager.getCache(cacheName1);
        sampleCache2 = manager.getCache(cacheName2);
        sampleCache1.removeAll();
        element = new Element("key", new Date());
        sampleCache1.put(element);
        CacheManagerPeerListener cacheManagerPeerListener =
                new RMICacheManagerPeerListener(hostName, port, manager, new Integer(2000));
        cacheManagerPeerListener.init();
        cache1Peer = (CachePeer) Naming.lookup(createNamingUrl() + cacheName1);
        cache2Peer = (CachePeer) Naming.lookup(createNamingUrl() + cacheName2);
    }

    /**
     * Shutdown the cache
     */
    protected void tearDown() throws InterruptedException {
        if (DistributionUtil.isSingleRMIRegistryPerVM()) {
            return;
        }

        Thread.sleep(10);
        manager.shutdown();
    }


    /**
     * Getting an RMI Server going is a big deal
     */
    public void testCreation() throws Exception {
        if (DistributionUtil.isSingleRMIRegistryPerVM()) {
            return;
        }

        assertNotNull(cache1Peer);
        assertNotNull(cache2Peer);
    }

    /**
     * The use of one-time registry creation and Naming.rebind should mean we can create as many listeneres as we like.
     * They will simply replace the ones that were there.
     */
    public void testMultipleCreationOfRMIServers() throws Exception {
        if (DistributionUtil.isSingleRMIRegistryPerVM()) {
            return;
        }

        for (int i = 0; i < 100; i++) {
            new RMICacheManagerPeerListener(hostName, port, manager, new Integer(2000));
        }
        cache1Peer = (CachePeer) Naming.lookup(createNamingUrl() + cacheName1);
        assertNotNull(cache1Peer);
    }

    private String createNamingUrl() {
        return "//" + hostName + ":" + port + "/";
    }

    /**
     * Attempts to get the cache name
     *
     * @throws java.net.MalformedURLException
     * @throws java.rmi.NotBoundException
     * @throws java.rmi.RemoteException
     */
    public void testGetName() throws Exception {
        if (DistributionUtil.isSingleRMIRegistryPerVM()) {
            return;
        }

        String lookupCacheName = cache1Peer.getName();
        assertEquals(cacheName1, lookupCacheName);
        lookupCacheName = cache2Peer.getName();
        assertEquals(cacheName2, lookupCacheName);
    }


}
