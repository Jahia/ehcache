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
 * @version $Id: ActiveMQJMSReplicationTest.java 816 2008-10-17 12:34:50Z gregluck $
 */

package net.sf.ehcache.distribution.jms;

import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.Element;
import net.sf.ehcache.Status;
import net.sf.ehcache.CacheException;
import org.junit.After;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

import java.io.Serializable;
import java.util.Date;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ActiveMQ seems to have a bug in 5.1 where it does not cleanup temporary queues, even though they have been
 * deleted. That bug appears to be long standing. 5.2 as of 10/08 was not released.
 * http://www.nabble.com/Memory-Leak-Using-Temporary-Queues-td11218217.html#a11218217
 * http://issues.apache.org/activemq/browse/AMQ-1255
 */
public class ActiveMQJMSReplicationTest {



    static final int NBR_ELEMENTS = 100;

    static final String SAMPLE_CACHE_ASYNC = "sampleCacheAsync";
    static final String SAMPLE_CACHE_SYNC = "sampleCacheSync";
    static final String SAMPLE_CACHE_NOREP = "sampleCacheNorep";

    String cacheName;

    private static final Logger LOG = Logger.getLogger(ActiveMQJMSReplicationTest.class.getName());

    protected CacheManager manager1, manager2, manager3, manager4;

    //protected abstract String getConfigurationFile();

    protected String getConfigurationFile() {
        return "distribution/jms/ehcache-distributed-jms-activemq.xml";
    }

    @Before
    public void setUp() throws Exception {

        manager1 = new CacheManager(TestUtil.TEST_CONFIG_DIR + getConfigurationFile());
        manager1.setName("manager1");
        manager2 = new CacheManager(TestUtil.TEST_CONFIG_DIR + getConfigurationFile());
        manager2.setName("manager2");
        manager3 = new CacheManager(TestUtil.TEST_CONFIG_DIR + getConfigurationFile());
        manager3.setName("manager3");
        manager4 = new CacheManager(TestUtil.TEST_CONFIG_DIR + getConfigurationFile());
        manager4.setName("manager4");
        cacheName = SAMPLE_CACHE_ASYNC;
        Thread.sleep(200);
    }

    @After
    public void tearDown() throws Exception {
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
        Thread.sleep(20);
    }


    @Test
    public void testBasicReplicationAsynchronous() throws Exception {
        cacheName = SAMPLE_CACHE_ASYNC;
        basicReplicationTest();
    }

    @Test
    public void testBasicReplicationSynchronous() throws Exception {
        cacheName = SAMPLE_CACHE_SYNC;
        basicReplicationTest();
    }

    @Test
    public void testStartupAndShutdown() {
        //noop
    }


    public void basicReplicationTest() throws Exception {

        //put
        for (int i = 0; i < NBR_ELEMENTS; i++) {
            manager1.getCache(cacheName).put(new Element(i, "testdat"));
        }
        Thread.sleep(3000);

        LOG.info(manager1.getCache(cacheName).getKeys().size() + "  " + manager2.getCache(cacheName).getKeys().size()
                + " " + manager3.getCache(cacheName).getKeys().size()
                + " " + manager4.getCache(cacheName).getKeys().size());

        assertTrue(manager1.getCache(cacheName).getKeys().size() == manager2.getCache(cacheName).getKeys().size() &&
                manager1.getCache(cacheName).getKeys().size() == manager3.getCache(cacheName).getKeys().size() &&
                manager1.getCache(cacheName).getKeys().size() == manager4.getCache(cacheName).getKeys().size() &&
                manager1.getCache(cacheName).getKeys().size() == NBR_ELEMENTS);

        //update via copy
        for (int i = 0; i < NBR_ELEMENTS; i++) {
            manager1.getCache(cacheName).put(new Element(i, "testdat"));
        }
        Thread.sleep(3000);

        LOG.info(manager1.getCache(cacheName).getKeys().size() + "  " + manager2.getCache(cacheName).getKeys().size()
                + " " + manager3.getCache(cacheName).getKeys().size()
                + " " + manager4.getCache(cacheName).getKeys().size());

        assertTrue(manager1.getCache(cacheName).getKeys().size() == manager2.getCache(cacheName).getKeys().size() &&
                manager1.getCache(cacheName).getKeys().size() == manager3.getCache(cacheName).getKeys().size() &&
                manager1.getCache(cacheName).getKeys().size() == manager4.getCache(cacheName).getKeys().size() &&
                manager1.getCache(cacheName).getKeys().size() == NBR_ELEMENTS);


        //remove
        manager1.getCache(cacheName).remove(0);
        Thread.sleep(1010);

        LOG.info(manager1.getCache(cacheName).getKeys().size() + "  " + manager2.getCache(cacheName).getKeys().size()
                + " " + manager3.getCache(cacheName).getKeys().size()
                + " " + manager4.getCache(cacheName).getKeys().size());

        assertTrue(manager1.getCache(cacheName).getKeys().size() == manager2.getCache(cacheName).getKeys().size() &&
                manager1.getCache(cacheName).getKeys().size() == manager3.getCache(cacheName).getKeys().size() &&
                manager1.getCache(cacheName).getKeys().size() == manager4.getCache(cacheName).getKeys().size() &&
                manager1.getCache(cacheName).getKeys().size() == NBR_ELEMENTS - 1);

        //removeall
        manager1.getCache(cacheName).removeAll();
        Thread.sleep(1010);

        LOG.info(manager1.getCache(cacheName).getKeys().size() + "  " + manager2.getCache(cacheName).getKeys().size()
                + " " + manager3.getCache(cacheName).getKeys().size()
                + " " + manager4.getCache(cacheName).getKeys().size());

        assertTrue(manager1.getCache(cacheName).getKeys().size() == manager2.getCache(cacheName).getKeys().size() &&
                manager1.getCache(cacheName).getKeys().size() == manager3.getCache(cacheName).getKeys().size() &&
                manager1.getCache(cacheName).getKeys().size() == manager4.getCache(cacheName).getKeys().size() &&
                manager1.getCache(cacheName).getKeys().size() == 0);

    }


//    @Test
//    public void testShutdownManager() throws Exception {
//        cacheName = SAMPLE_CACHE_ASYNC;
//        manager1.getCache(cacheName).removeAll();
//        Thread.currentThread().sleep(1000);
//
//        CacheManagerPeerProvider provider = manager1.getCachePeerProvider();
//        JGroupManager jg = (JGroupManager) provider;
//        assertEquals(Status.STATUS_ALIVE, jg.getStatus());
//        manager1.shutdown();
//        assertEquals(Status.STATUS_SHUTDOWN, jg.getStatus());
//        //Lets see if the other still replicate
//        manager2.getCache(cacheName).put(new Element(new Integer(1), new Date()));
//        Thread.currentThread().sleep(2000);
//
//
//        assertTrue(manager2.getCache(cacheName).getKeys().size() == manager3.getCache(cacheName).getKeys().size() &&
//                manager2.getCache(cacheName).getKeys().size() == manager4.getCache(cacheName).getKeys().size() &&
//                manager2.getCache(cacheName).getKeys().size() == 1);
//
//
//    }

    @Test
    public void testAddManager() throws Exception {
        cacheName = SAMPLE_CACHE_ASYNC;
        if (manager1.getStatus() != Status.STATUS_SHUTDOWN)
            manager1.shutdown();


        Thread.sleep(1000);
        manager1 = new CacheManager(TestUtil.TEST_CONFIG_DIR + getConfigurationFile());
        Thread.sleep(3000);
        manager2.clearAll();

        Thread.sleep(1000);

        manager2.getCache(cacheName).put(new Element(2, new Date()));
        manager1.getCache(cacheName).put(new Element(3, new Date()));
        Thread.sleep(2000);

        assertTrue(manager1.getCache(cacheName).getKeys().size() == manager2.getCache(cacheName).getKeys().size() &&
                manager1.getCache(cacheName).getKeys().size() == manager3.getCache(cacheName).getKeys().size() &&
                manager1.getCache(cacheName).getKeys().size() == manager4.getCache(cacheName).getKeys().size() &&
                manager1.getCache(cacheName).getKeys().size() == 2);

    }


    @Test
    public void testNoreplication() throws InterruptedException {
        cacheName = SAMPLE_CACHE_NOREP;
        Ehcache cache1 = manager1.getCache(cacheName);
        Ehcache cache2 = manager2.getCache(cacheName);
        Element element = new Element(1, new Date());

        //put
        cache2.put(element);
        Thread.sleep(1000);
        assertTrue(cache1.getKeys().size() == 0 && cache2.getKeys().size() == 1);

        //update
        cache2.put(element);
        Thread.sleep(1000);
        assertTrue(cache1.getKeys().size() == 0 && cache2.getKeys().size() == 1);

        //remove
        cache1.put(element);
        cache1.remove(1);
        Thread.sleep(1000);
        assertTrue(cache1.getKeys().size() == 0 && cache2.getKeys().size() == 1);

        //removeAll
        cache1.removeAll();
        Thread.sleep(1000);
        assertTrue(cache1.getKeys().size() == 0 && cache2.getKeys().size() == 1);

    }

    /**
     * What happens when two cache instances replicate to each other and a change is initiated
     *
     * @throws InterruptedException -
     */
    @Test
    public void testVariousPuts() throws InterruptedException {
        cacheName = SAMPLE_CACHE_ASYNC;
        Ehcache cache1 = manager1.getCache(cacheName);
        Ehcache cache2 = manager2.getCache(cacheName);

        Serializable key = "1";
        Serializable value = new Date();
        Element element = new Element(key, value);

        //Put
        cache1.put(element);
        Thread.sleep(1000);

        //Should have been replicated to cache2.
        Element element2 = cache2.get(key);
        assertEquals(element, element2);


        //Remove
        cache1.remove(key);
        assertNull(cache1.get(key));

        //Should have been replicated to cache2.
        Thread.sleep(1000);
        element2 = cache2.get(key);
        assertNull(element2);

        //Put into 2
        Element element3 = new Element("3", "ddsfds");
        cache2.put(element3);
        Thread.sleep(1000);
        Element element4 = cache2.get("3");
        assertEquals(element3, element4);

        manager1.clearAll();
        Thread.sleep(1000);

    }


    /**
     * What happens when two cache instances replicate to each other and a change is initiated
     *
     * @throws InterruptedException -
     */
    @Test
    public void testPutAndRemove() throws InterruptedException {

        cacheName = SAMPLE_CACHE_SYNC;
        Ehcache cache1 = manager1.getCache(cacheName);
        Ehcache cache2 = manager2.getCache(cacheName);

        Serializable key = "1";
        Serializable value = new Date();
        Element element = new Element(key, value);

        //Put
        cache1.put(element);
        long version = element.getVersion();
        Thread.sleep(100);
        //make sure we are not getting our own circular update back
        assertEquals(version, cache1.get(key).getVersion());

        //Should have been replicated to cache2.
        Element element2 = cache2.get(key);
        assertEquals(element, element2);


        //Remove
        cache1.remove(key);
        assertNull(cache1.get(key));

        //Should have been replicated to cache2.
        Thread.sleep(100);
        element2 = cache2.get(key);
        assertNull(element2);

    }


    /**
     * Same as testPutandRemove but this one does:
     */
    @Test
    public void testPutAndRemoveStability() throws InterruptedException {
        for (int i = 0; i < 120; i++) {
            testPutAndRemove();
        }
    }


    /**
     * This is a manual test.
     * Start the test running and observe the output. You should see no exceptions.
     * Then kill the message queue.
     * <p/>
     * You will see errors like these if using Open MQ.
     * <p/>
     * Oct 11, 2008 10:11:10 PM com.sun.messaging.jmq.jmsclient.ExceptionHandler logCaughtException
     * WARNING: [I500]: Caught JVM Exception: java.io.EOFException
     * Oct 11, 2008 10:11:10 PM com.sun.messaging.jmq.jmsclient.ExceptionHandler logCaughtException
     * WARNING: [I500]: Caught JVM Exception: java.io.EOFException
     * Oct 11, 2008 10:11:10 PM com.sun.messaging.jmq.jmsclient.ConnectionRecover logRecoverState
     * INFO: [I107]: Connection recover state: RECOVER_INACTIVE, broker: localhost:7676(49990)
     * Oct 11, 2008 10:11:10 PM com.sun.messaging.jmq.jmsclient.ConnectionRecover logRecoverState
     * INFO: [I107]: Connection recover state: RECOVER_INACTIVE, broker: localhost:7676(49990)
     * Oct 11, 2008 10:11:10 PM com.sun.messaging.jmq.jmsclient.ExceptionHandler logCaughtException
     * WARNING: [I500]: Caught JVM Exception: java.io.EOFException
     * Oct 11, 2008 10:11:10 PM com.sun.messaging.jmq.jmsclient.ConnectionRecover logRecoverState
     * INFO: [I107]: Connection recover state: RECOVER_INACTIVE, broker: localhost:7676(49990)
     * Oct 11, 2008 10:11:10 PM com.sun.messaging.jmq.jmsclient.ConnectionRecover logRecoverState
     * INFO: [I107]: Connection recover state: RECOVER_INACTIVE, broker: localhost:7676(49990)
     * Oct 11, 2008 10:11:10 PM com.sun.messaging.jmq.jmsclient.ConnectionRecover logRecoverState
     * INFO: [I107]: Connection recover state: RECOVER_INACTIVE, broker: localhost:7676(49990)
     * Oct 11, 2008 10:11:10 PM com.sun.messaging.jmq.jmsclient.ConnectionRecover logRecoverState
     * INFO: [I107]: Connection recover state: RECOVER_INACTIVE, broker: localhost:7676(49990)
     * Oct 11, 2008 10:11:13 PM com.sun.messaging.jmq.jmsclient.ExceptionHandler throwConnectionException
     * WARNING: [C4003]: Error occurred on connection creation [localhost:7676]. - cause: java.net.ConnectException: Connection refused
     * <p/>
     * Then restart the message queue.
     * <p/>
     * You will see recover messages such as:
     * <p/>
     * INFO: [I107]: Connection recover state: RECOVER_INACTIVE, broker: localhost:7676(50206)
     * Oct 11, 2008 10:13:46 PM com.sun.messaging.jmq.jmsclient.ConnectionRecover logRecoverState
     * INFO: [I107]: Connection recover state: RECOVER_SUCCEEDED, broker: localhost:7676(50206)
     * Oct 11, 2008 10:13:46 PM com.sun.messaging.jmq.jmsclient.ConnectionRecover logRecoverState
     * INFO: [I107]: Connection recover state: RECOVER_INACTIVE, broker: localhost:7676(50206)
     * Oct 11, 2008 10:13:46 PM com.sun.messaging.jmq.jmsclient.ConnectionRecover logRecoverState
     * INFO: [I107]: Connection recover state: RECOVER_INACTIVE, broker: localhost:7676(50206)
     * Oct 11, 2008 10:13:46 PM com.sun.messaging.jmq.jmsclient.ConsumerReader run
     * WARNING: [C4001]: Write packet failed., packet type = ACKNOWLEDGE(24)
     * com.sun.messaging.jms.JMSException: [C4001]: Write packet failed., packet type = ACKNOWLEDGE(24)
     * at com.sun.messaging.jmq.jmsclient.ConnectionImpl.checkPacketType(ConnectionImpl.java:654)
     * at com.sun.messaging.jmq.jmsclient.ConnectionImpl.checkReconnecting(ConnectionImpl.java:641)
     * at com.sun.messaging.jmq.jmsclient.ProtocolHandler.checkConnectionState(ProtocolHandler.java:766)
     * at com.sun.messaging.jmq.jmsclient.ProtocolHandler.writePacketNoAck(ProtocolHandler.java:360)
     * at com.sun.messaging.jmq.jmsclient.ProtocolHandler.acknowledge(ProtocolHandler.java:2608)
     * at com.sun.messaging.jmq.jmsclient.SessionImpl.doAcknowledge(SessionImpl.java:1382)
     * at com.sun.messaging.jmq.jmsclient.SessionImpl.dupsOkCommitAcknowledge(SessionImpl.java:1427)
     * at com.sun.messaging.jmq.jmsclient.SessionImpl.syncedDupsOkCommitAcknowledge(SessionImpl.java:1450)
     * at com.sun.messaging.jmq.jmsclient.SessionReader.deliver(SessionReader.java:141)
     * at com.sun.messaging.jmq.jmsclient.ConsumerReader.run(ConsumerReader.java:190)
     * at java.lang.Thread.run(Thread.java:613)
     * Oct 11, 2008 10:13:46 PM com.sun.messaging.jmq.jmsclient.ConnectionRecover logRecoverState
     * INFO: [I107]: Connection recover state: RECOVER_INACTIVE, broker: localhost:7676(50206)
     * Oct 11, 2008 10:13:46 PM com.sun.messaging.jmq.jmsclient.ConsumerReader run
     * WARNING: [C4001]: Write packet failed., packet type = ACKNOWLEDGE(24)
     * com.sun.messaging.jms.JMSException: [C4001]: Write packet failed., packet type = ACKNOWLEDGE(24)
     * at com.sun.messaging.jmq.jmsclient.ConnectionImpl.checkPacketType(ConnectionImpl.java:654)
     * at com.sun.messaging.jmq.jmsclient.ConnectionImpl.checkReconnecting(ConnectionImpl.java:641)
     * at com.sun.messaging.jmq.jmsclient.ProtocolHandler.checkConnectionState(ProtocolHandler.java:766)
     * at com.sun.messaging.jmq.jmsclient.ProtocolHandler.writePacketNoAck(ProtocolHandler.java:360)
     * at com.sun.messaging.jmq.jmsclient.ProtocolHandler.acknowledge(ProtocolHandler.java:2608)
     * at com.sun.messaging.jmq.jmsclient.SessionImpl.doAcknowledge(SessionImpl.java:1382)
     * at com.sun.messaging.jmq.jmsclient.SessionImpl.dupsOkCommitAcknowledge(SessionImpl.java:1427)
     * at com.sun.messaging.jmq.jmsclient.SessionImpl.syncedDupsOkCommitAcknowledge(SessionImpl.java:1450)
     * at com.sun.messaging.jmq.jmsclient.SessionReader.deliver(SessionReader.java:141)
     * at com.sun.messaging.jmq.jmsclient.ConsumerReader.run(ConsumerReader.java:190)
     * at java.lang.Thread.run(Thread.java:613)
     * Oct 11, 2008 10:13:46 PM com.sun.messaging.jmq.jmsclient.ConnectionRecover logRecoverState
     * <p/>
     * Normal processing will then resume. i.e. the ehcache cluster reforms once the message queue is back.
     * <p/>
     * To enable this behaviour the following must be set on the connection factory configuration.
     * <p/>
     * Open MQ
     * imqReconnect='true' - without this reconnect will not happen
     * imqPingInterval='5' - Consumers will not reconnect until they notice the connection is down. The ping interval
     * does this. The default is 30. Set it lower if you want the ehcache cluster to reform more quickly.
     * Finally, unlimited retry attempts are recommended. This is the default.
     */
    //@Test
    public void testPutAndRemoveMessageQueueFailure() throws InterruptedException {
        for (int i = 0; i < 1000; i++) {
            try {
                testPutAndRemove();
                Thread.sleep(5000);
            } catch (Exception e) {
                LOG.log(Level.SEVERE, e.getMessage(), e);
            }
        }
    }

    @Test
    public void testSimultaneousPutRemove() throws InterruptedException {
        cacheName = SAMPLE_CACHE_SYNC; //Synced one
        Ehcache cache1 = manager1.getCache(cacheName);
        Ehcache cache2 = manager2.getCache(cacheName);


        Serializable key = "1";
        Serializable value = new Date();
        Element element = new Element(key, value);

        //Put
        cache1.put(element);
        Thread.sleep(1000);
        cache2.remove(element.getKey());
        Thread.sleep(1000);


        assertNull(cache1.get(element.getKey()));
        manager1.clearAll();
        Thread.sleep(1000);

        cache2.put(element);
        cache2.remove(element.getKey());
        Thread.sleep(1000);
        cache1.put(element);
        Thread.sleep(1000);
        assertNotNull(cache2.get(element.getKey()));

        manager1.clearAll();
        Thread.sleep(1000);

    }


    /**
     * Tests the JMSCacheLoader.
     * <p/>
     * We put an item in cache1, which does not replicate.
     * <p/>
     * We then do a get on cache2, which has a JMSCacheLoader which should ask the cluster for the answer.
     * If a cache does not have an element it should leave the message on the queue for the next node to process.
     */
    @Test
    public void testGet() throws InterruptedException {
        cacheName = SAMPLE_CACHE_SYNC;
        manager3.shutdown();
        manager4.shutdown();
        Thread.sleep(20);
        Ehcache cache1 = manager1.getCache("sampleCacheNorep");
        Ehcache cache2 = manager2.getCache("sampleCacheNorep");

        Serializable key = "1";
        Serializable value = new Date();
        Element element = new Element(key, value);

        //Put
        cache1.put(element);
        long version = element.getVersion();
        Thread.sleep(1050);


        //Should not have been replicated to cache2.
        Element element2 = cache2.get(key);
        assertEquals(null, element2);

        //Should load from cache1
        for (int i = 0; i < 120; i++) {
            element2 = cache2.getWithLoader(key, null, null);
            assertEquals(value, element2.getValue());
            cache2.remove(key);
        }

        //Should load from cache1
        element2 = cache2.getWithLoader(key, null, null);
        assertEquals(value, element2.getValue());
        cache2.remove(key);
    }


    /**
     * Tests the JMSCacheLoader.
     * <p/>
     * We put an item in cache1, which does not replicate.
     * <p/>
     * We then do a get on cache2, which has a JMSCacheLoader which should ask the cluster for the answer.
     * If a cache does not have an element it should leave the message on the queue for the next node to process.
     */
    @Test
    public void testGetAll() throws InterruptedException {
        cacheName = SAMPLE_CACHE_SYNC;
        manager3.shutdown();
        manager4.shutdown();
        Thread.sleep(20);
        Ehcache cache1 = manager1.getCache("sampleCacheNorep");
        Ehcache cache2 = manager2.getCache("sampleCacheNorep");

        Serializable key = "1";
        Serializable value = new Date();
        Element element = new Element(key, value);
        Element element2 = new Element(2, "dog");
        Element element3 = new Element(3, "cat");

        ArrayList keys = new ArrayList();
        keys.add("1");
        keys.add(2);

        //Put
        cache1.put(element);
        cache1.put(element2);
        cache1.put(element3);
        Thread.sleep(1050);


        //Should not have been replicated to cache2.
        Element element1Retrieved = cache2.get(key);
        assertEquals(null, element1Retrieved);

        //Should load from cache2
        for (int i = 0; i < 120; i++) {
            Map received = cache2.getAllWithLoader(keys, null);
            assertEquals(2, received.size());
            assertEquals(value, received.get("1"));
            assertEquals("dog", received.get(2));
            cache2.remove(key);
            cache2.remove(2);
        }
    }


    /**
     * Tests the JMSCacheLoader.
     * <p/>
     * We put an item in cache1, which does not replicate.
     * <p/>
     * We then do a get on cache2, which has a JMSCacheLoader which should ask the cluster for the answer.
     * If a cache does not have an element it should leave the message on the queue for the next node to process.
     * @throws InterruptedException -
     */
    @Test
    public void testGetTimeout() throws InterruptedException {
        cacheName = SAMPLE_CACHE_SYNC;
        manager3.shutdown();
        manager4.shutdown();
        Thread.sleep(20);
        Ehcache cache1 = manager1.getCache("sampleCacheNorep");
        Ehcache cache2 = manager2.getCache("sampleCacheNorep");

        Serializable key = "net.sf.ehcache.distribution.jms.Delay";
        Serializable value = new Date();
        Element element = new Element(key, value);

        //Put
        cache1.put(element);
        Thread.sleep(1050);

        //Should not have been replicated to cache2.
        Element element2 = cache2.get(key);
        assertEquals(null, element2);

        //Should timeout loading from cache2
        element2 = cache2.getWithLoader(key, null, null);
        assertNull(element2);
        cache2.remove(key);
    }



}
