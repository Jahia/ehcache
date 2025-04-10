/**
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
 *
 * @author Pierre Monestie (pmonestie__REMOVE__THIS__@gmail.com)
 * @author <a href="mailto:gluck@gregluck.com">Greg Luck</a>
 * @version $Id$
 */

package net.sf.ehcache.distribution.jgroups;

import junit.framework.TestCase;
import net.sf.ehcache.AbstractCacheTest;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.Element;
import net.sf.ehcache.Status;
import net.sf.ehcache.distribution.CacheManagerPeerProvider;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.Serializable;
import java.util.Date;

public class JGroupsReplicationTest extends TestCase {

    private static final String SAMPLE_CACHE_NOREP = "sampleCacheNorep";

    private static final int NBR_ELEMENTS = 100;

    private static final String SAMPLE_CACHE1 = "sampleCacheAsync";
    private static final String SAMPLE_CACHE2 = "sampleCacheSync";
    String cacheName;

    private static final Log LOG = LogFactory.getLog(JGroupsReplicationTest.class.getName());

    protected CacheManager manager1, manager2, manager3, manager4, manager5;

    protected void setUp() throws Exception {
        manager1 = new CacheManager(AbstractCacheTest.TEST_CONFIG_DIR + "distribution/jgroups/ehcache-distributed-jgroups.xml");
        manager2 = new CacheManager(AbstractCacheTest.TEST_CONFIG_DIR + "distribution/jgroups/ehcache-distributed-jgroups.xml");
        manager3 = new CacheManager(AbstractCacheTest.TEST_CONFIG_DIR + "distribution/jgroups/ehcache-distributed-jgroups.xml");
        manager4 = new CacheManager(AbstractCacheTest.TEST_CONFIG_DIR + "distribution/jgroups/ehcache-distributed-jgroups.xml");
        cacheName = SAMPLE_CACHE1;
        Thread.currentThread().sleep(4000);
    }

    public void tearDown() throws Exception {
        LOG.debug("Tearing down cm1");
        manager1.shutdown();
        LOG.debug("Tearing down cm2");
        manager2.shutdown();
        LOG.debug("Tearing down cm3");
        manager3.shutdown();
        LOG.debug("Tearing down cm4");
        manager4.shutdown();
    }

    public void testBasicReplication() throws Exception {


        for (int i = 0; i < NBR_ELEMENTS; i++) {
            manager1.getEhcache(cacheName).put(new Element(new Integer(i), "testdat"));
            //Thread.currentThread().sleep(2);
        }
        Thread.currentThread().sleep(3000);

        LOG.debug(manager1.getEhcache(cacheName).getKeys().size() + "  " + manager2.getEhcache(cacheName).getKeys().size() + " " + manager3.getEhcache(cacheName).getKeys().size());
        assertTrue(manager1.getEhcache(cacheName).getKeys().size() == manager2.getEhcache(cacheName).getKeys().size() &&
                manager1.getEhcache(cacheName).getKeys().size() == manager3.getEhcache(cacheName).getKeys().size() &&
                manager1.getEhcache(cacheName).getKeys().size() == manager4.getEhcache(cacheName).getKeys().size() &&
                manager1.getEhcache(cacheName).getKeys().size() == NBR_ELEMENTS);

        manager1.getEhcache(cacheName).removeAll();
        Thread.currentThread().sleep(1000);
        assertTrue(manager1.getEhcache(cacheName).getKeys().size() == manager2.getEhcache(cacheName).getKeys().size() &&
                manager1.getEhcache(cacheName).getKeys().size() == manager3.getEhcache(cacheName).getKeys().size() &&
                manager1.getEhcache(cacheName).getKeys().size() == manager4.getEhcache(cacheName).getKeys().size() &&
                manager1.getEhcache(cacheName).getKeys().size() == 0);

    }

    public void testBasicReplicationSynchroneous() throws Exception {
        cacheName = SAMPLE_CACHE2;
        testBasicReplication();
    }

    public void testShutdownManager() throws Exception {
        cacheName = SAMPLE_CACHE1;
        manager1.getEhcache(cacheName).removeAll();
        Thread.currentThread().sleep(1000);

        CacheManagerPeerProvider provider = manager1.getCachePeerProvider();
        JGroupManager jg = (JGroupManager) provider;
        assertEquals(Status.STATUS_ALIVE, jg.getStatus());
        manager1.shutdown();
        assertEquals(Status.STATUS_SHUTDOWN, jg.getStatus());
        //Lets see if the other still replicate
        manager2.getEhcache(cacheName).put(new Element(new Integer(1), new Date()));
        Thread.currentThread().sleep(2000);


        assertTrue(manager2.getEhcache(cacheName).getKeys().size() == manager3.getEhcache(cacheName).getKeys().size() &&
                manager2.getEhcache(cacheName).getKeys().size() == manager4.getEhcache(cacheName).getKeys().size() &&
                manager2.getEhcache(cacheName).getKeys().size() == 1);


    }

    public void testAddManager() throws Exception {
        cacheName = SAMPLE_CACHE1;
        if (manager1.getStatus() != Status.STATUS_SHUTDOWN)
            manager1.shutdown();


        Thread.currentThread().sleep(1000);
        manager1 = new CacheManager(AbstractCacheTest.TEST_CONFIG_DIR + "distribution/jgroups/ehcache-distributed-jgroups.xml");
        Thread.currentThread().sleep(3000);
        manager2.clearAll();

        Thread.currentThread().sleep(1000);

        manager2.getEhcache(cacheName).put(new Element(new Integer(2), new Date()));
        manager1.getEhcache(cacheName).put(new Element(new Integer(3), new Date()));
        Thread.currentThread().sleep(2000);

        assertTrue(manager1.getEhcache(cacheName).getKeys().size() == manager2.getEhcache(cacheName).getKeys().size() &&
                manager1.getEhcache(cacheName).getKeys().size() == manager3.getEhcache(cacheName).getKeys().size() &&
                manager1.getEhcache(cacheName).getKeys().size() == manager4.getEhcache(cacheName).getKeys().size() &&
                manager1.getEhcache(cacheName).getKeys().size() == 2);

    }


    public void testConfig() throws InterruptedException {
        cacheName = SAMPLE_CACHE_NOREP;
        Ehcache cache1 = manager1.getEhcache(cacheName);
        Ehcache cache2 = manager2.getEhcache(cacheName);
        Element element = new Element(new Integer(1), new Date());
        cache2.put(element);
        Thread.currentThread().sleep(1000);
        assertTrue(cache1.getKeys().size() == 0 && cache2.getKeys().size() == 1);

    }

    /**
     * What happens when two cache instances replicate to each other and a change is initiated
     */
    public void testVariousPuts() throws InterruptedException {
        cacheName = SAMPLE_CACHE1;
        Ehcache cache1 = manager1.getEhcache(cacheName);
        Ehcache cache2 = manager2.getEhcache(cacheName);

        Serializable key = "1";
        Serializable value = new Date();
        Element element = new Element(key, value);

        //Put
        cache1.put(element);
        Thread.currentThread().sleep(1000);

        //Should have been replicated to cache2.
        Element element2 = cache2.get(key);
        assertEquals(element, element2);

        //Remove
        cache1.remove(key);
        assertNull(cache1.get(key));

        //Should have been replicated to cache2.
        Thread.currentThread().sleep(1000);
        element2 = cache2.get(key);
        assertNull(element2);

        //Put into 2
        Element element3 = new Element("3", "ddsfds");
        cache2.put(element3);
        Thread.currentThread().sleep(1000);
        Element element4 = cache2.get("3");
        assertEquals(element3, element4);

        manager1.clearAll();
        Thread.currentThread().sleep(1000);

    }

    public void testSimultaneousPutRemove() throws InterruptedException {
        cacheName = SAMPLE_CACHE2; //Synced one
        Ehcache cache1 = manager1.getEhcache(cacheName);
        Ehcache cache2 = manager2.getEhcache(cacheName);


        Serializable key = "1";
        Serializable value = new Date();
        Element element = new Element(key, value);

        //Put
        cache1.put(element);
        Thread.currentThread().sleep(1000);
        cache2.remove(element.getKey());
        Thread.currentThread().sleep(1000);


        assertNull(cache1.get(element.getKey()));
        manager1.clearAll();
        Thread.currentThread().sleep(1000);

        cache2.put(element);
        cache2.remove(element.getKey());
        Thread.currentThread().sleep(1000);
        cache1.put(element);
        Thread.currentThread().sleep(1000);
        assertNotNull(cache2.get(element.getKey()));

        manager1.clearAll();
        Thread.currentThread().sleep(1000);

    }

}
