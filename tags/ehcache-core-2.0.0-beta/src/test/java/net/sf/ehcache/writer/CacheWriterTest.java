/**
 *  Copyright 2003-2009 Terracotta, Inc.
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

package net.sf.ehcache.writer;

import net.sf.ehcache.AbstractCacheTest;
import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Element;
import net.sf.ehcache.Status;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.config.CacheWriterConfiguration;
import net.sf.ehcache.event.CountingCacheEventListener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for a CacheWriters
 *
 * @author Geert Bevin
 * @version $Id$
 */
public class CacheWriterTest extends AbstractCacheTest {

    private static final Logger LOG = LoggerFactory.getLogger(CacheWriterTest.class.getName());

    protected CacheManager manager;

    @Before
    public void setUp() throws Exception {
        manager = CacheManager.create(AbstractCacheTest.TEST_CONFIG_DIR + "ehcache-writer.xml");
    }

    @After
    public void tearDown() throws Exception {
        if (!manager.getStatus().equals(Status.STATUS_SHUTDOWN)) {
            manager.shutdown();
        }
    }

    @Test
    public void testWriteThroughXml() {
        Cache cache = manager.getCache("writeThroughCacheXml");
        assertNotNull(cache.getRegisteredCacheWriter());

        TestCacheWriter writer = (TestCacheWriter) cache.getRegisteredCacheWriter();

        Element el1 = new Element("key1", "value1");
        Element el2 = new Element("key2", "value2");
        Element el3 = new Element("key3", "value3");
        assertEquals(0, writer.getWrittenElements().size());
        cache.putWithWriter(el1);
        assertEquals(1, writer.getWrittenElements().size());
        cache.putWithWriter(el2);
        assertEquals(2, writer.getWrittenElements().size());
        cache.putWithWriter(el3);
        assertEquals(3, writer.getWrittenElements().size());
        assertEquals("value1", writer.getWrittenElements().get("key1").getValue());
        assertEquals("value2", writer.getWrittenElements().get("key2").getValue());
        assertEquals("value3", writer.getWrittenElements().get("key3").getValue());
        cache.removeWithWriter(el2.getKey());
        assertEquals(3, writer.getWrittenElements().size());
        assertNotNull(writer.getWrittenElements().get("key1"));
        assertNotNull(writer.getWrittenElements().get("key2"));
        assertNotNull(writer.getWrittenElements().get("key3"));
        assertEquals(1, writer.getDeletedKeys().size());
        assertTrue(writer.getDeletedKeys().contains("key2"));
    }

    @Test
    public void testWriteThroughXmlProperties() {
        Cache cache = manager.getCache("writeThroughCacheXmlProperties");
        assertNotNull(cache.getRegisteredCacheWriter());

        TestCacheWriter writer = (TestCacheWriter) cache.getRegisteredCacheWriter();

        Element el1 = new Element("key1", "value1");
        Element el2 = new Element("key2", "value2");
        Element el3 = new Element("key3", "value3");
        assertEquals(0, writer.getWrittenElements().size());
        cache.putWithWriter(el1);
        assertEquals(1, writer.getWrittenElements().size());
        cache.putWithWriter(el2);
        assertEquals(2, writer.getWrittenElements().size());
        cache.putWithWriter(el3);
        assertEquals(3, writer.getWrittenElements().size());
        assertNull(writer.getWrittenElements().get("key1"));
        assertNull(writer.getWrittenElements().get("key2"));
        assertNull(writer.getWrittenElements().get("key3"));
        assertEquals("value1", writer.getWrittenElements().get("prekey1suff").getValue());
        assertEquals("value2", writer.getWrittenElements().get("prekey2suff").getValue());
        assertEquals("value3", writer.getWrittenElements().get("prekey3suff").getValue());
        cache.removeWithWriter(el1.getKey());
        assertEquals(3, writer.getWrittenElements().size());
        assertNotNull(writer.getWrittenElements().get("prekey1suff"));
        assertNotNull(writer.getWrittenElements().get("prekey2suff"));
        assertNotNull(writer.getWrittenElements().get("prekey3suff"));
        assertEquals(1, writer.getDeletedKeys().size());
        assertTrue(writer.getDeletedKeys().contains("prekey1suff"));
    }

    @Test
    public void testWriteThroughJavaRegistration() {
        Cache cache = manager.getCache("writeThroughCacheJavaRegistration");
        assertNull(cache.getRegisteredCacheWriter());

        TestCacheWriter writer = new TestCacheWriter(new Properties());
        cache.registerCacheWriter(writer);

        Element el1 = new Element("key1", "value1");
        Element el2 = new Element("key2", "value2");
        Element el3 = new Element("key3", "value3");
        Element el4 = new Element("key4", "value4");
        assertEquals(0, writer.getWrittenElements().size());
        cache.putWithWriter(el1);
        assertEquals(1, writer.getWrittenElements().size());
        cache.putWithWriter(el2);
        assertEquals(2, writer.getWrittenElements().size());
        cache.putWithWriter(el3);
        assertEquals(3, writer.getWrittenElements().size());
        cache.putWithWriter(el4);
        assertEquals(4, writer.getWrittenElements().size());
        assertEquals("value1", writer.getWrittenElements().get("key1").getValue());
        assertEquals("value2", writer.getWrittenElements().get("key2").getValue());
        assertEquals("value3", writer.getWrittenElements().get("key3").getValue());
        assertEquals("value4", writer.getWrittenElements().get("key4").getValue());
        cache.removeWithWriter(el1.getKey());
        cache.removeWithWriter(el2.getKey());
        cache.removeWithWriter(el3.getKey());
        assertEquals(4, writer.getWrittenElements().size());
        assertNotNull(writer.getWrittenElements().get("key1"));
        assertNotNull(writer.getWrittenElements().get("key2"));
        assertNotNull(writer.getWrittenElements().get("key3"));
        assertNotNull(writer.getWrittenElements().get("key4"));
        assertEquals(3, writer.getDeletedKeys().size());
        assertTrue(writer.getDeletedKeys().contains("key1"));
        assertTrue(writer.getDeletedKeys().contains("key2"));
        assertTrue(writer.getDeletedKeys().contains("key3"));
    }

    @Test
    public void testWriteThroughNotifyListeners() {
        Cache cache = new Cache(new CacheConfiguration("writeThroughCacheOnly", 10)
                .cacheEventListenerFactory(new CacheConfiguration.CacheEventListenerFactoryConfiguration().className("net.sf.ehcache.event.CountingCacheEventListenerFactory")));
        assertNull(cache.getRegisteredCacheWriter());

        CacheManager.getInstance().addCache(cache);

        TestCacheWriterException writer = new TestCacheWriterException();
        cache.registerCacheWriter(writer);

        // without listeners notification
        cache.getCacheConfiguration().getCacheWriterConfiguration().setNotifyListenersOnException(false);

        CountingCacheEventListener.resetCounters();

        try {
            cache.putWithWriter(new Element("key1", "value1"));
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // expected
            assertEquals(0, CountingCacheEventListener.getCacheElementsPut(cache).size());
        }

        CountingCacheEventListener.resetCounters();

        try {
            cache.putWithWriter(new Element("key1", "value1"));
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // expected
            assertEquals(0, CountingCacheEventListener.getCacheElementsUpdated(cache).size());
        }

        CountingCacheEventListener.resetCounters();

        try {
            cache.removeWithWriter("key1");
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // expected
            assertEquals(0, CountingCacheEventListener.getCacheElementsRemoved(cache).size());
        }

        // with listeners notification
        cache.getCacheConfiguration().getCacheWriterConfiguration().setNotifyListenersOnException(true);

        CountingCacheEventListener.resetCounters();

        try {
            cache.putWithWriter(new Element("key1", "value1"));
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // expected
            assertEquals(1, CountingCacheEventListener.getCacheElementsPut(cache).size());
        }

        CountingCacheEventListener.resetCounters();

        try {
            cache.putWithWriter(new Element("key1", "value1"));
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // expected
            assertEquals(1, CountingCacheEventListener.getCacheElementsUpdated(cache).size());
        }

        CountingCacheEventListener.resetCounters();

        try {
            cache.removeWithWriter("key1");
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // expected
            assertEquals(1, CountingCacheEventListener.getCacheElementsRemoved(cache).size());
        }
    }

    @Test
    public void testWriteBehindSolelyJava() throws InterruptedException {
        Cache cache = new Cache(
                new CacheConfiguration("writeBehindSolelyJava", 10)
                        .cacheWriter(new CacheWriterConfiguration()
                        .writeMode(CacheWriterConfiguration.WriteMode.WRITE_BEHIND)
                        .minWriteDelay(2)
                        .maxWriteDelay(8)
                        .cacheWriterFactory(new CacheWriterConfiguration.CacheWriterFactoryConfiguration()
                        .className("net.sf.ehcache.writer.TestCacheWriterFactory"))));
        assertNotNull(cache.getRegisteredCacheWriter());

        CacheManager.getInstance().addCache(cache);
        TestCacheWriter writer = (TestCacheWriter) cache.getRegisteredCacheWriter();
        assertEquals(0, writer.getWrittenElements().size());

        Element el1 = new Element("key1", "value1");
        Element el2 = new Element("key2", "value2");
        Element el3 = new Element("key3", "value3");
        cache.putWithWriter(el1);
        cache.putWithWriter(el2);
        cache.putWithWriter(el3);

        Thread.sleep(3000);

        assertEquals(3, writer.getWrittenElements().size());
        assertNotNull(writer.getWrittenElements().get("key1"));
        assertNotNull(writer.getWrittenElements().get("key2"));
        assertNotNull(writer.getWrittenElements().get("key3"));
        assertEquals(0, writer.getDeletedKeys().size());

        cache.removeWithWriter(el2.getKey());
        cache.removeWithWriter(el3.getKey());

        assertEquals(3, writer.getWrittenElements().size());
        assertEquals(0, writer.getDeletedKeys().size());

        Thread.sleep(3000);

        assertEquals(3, writer.getWrittenElements().size());
        assertEquals(2, writer.getDeletedKeys().size());
        assertTrue(writer.getDeletedKeys().contains("key2"));
        assertTrue(writer.getDeletedKeys().contains("key3"));
    }

    @Test
    public void testWriteBehindBatched() throws InterruptedException {
        Cache cache = new Cache(
                new CacheConfiguration("writeBehindBatched", 10)
                        .cacheWriter(new CacheWriterConfiguration()
                        .writeMode(CacheWriterConfiguration.WriteMode.WRITE_BEHIND)
                        .minWriteDelay(1)
                        .maxWriteDelay(4)
                        .writeBatching(true)
                        .writeBatchSize(10)
                        .cacheWriterFactory(new CacheWriterConfiguration.CacheWriterFactoryConfiguration()
                        .className("net.sf.ehcache.writer.TestCacheWriterFactory")
                        .properties("key.prefix=pre2; key.suffix=suff2")
                        .propertySeparator(";"))));
        assertNotNull(cache.getRegisteredCacheWriter());

        CacheManager.getInstance().addCache(cache);
        TestCacheWriter writer = (TestCacheWriter) cache.getRegisteredCacheWriter();
        assertEquals(0, writer.getWrittenElements().size());

        Element el1 = new Element("key1", "value1");
        Element el2 = new Element("key2", "value2");
        Element el3 = new Element("key3", "value3");
        cache.putWithWriter(el1);
        cache.putWithWriter(el2);
        cache.putWithWriter(el3);

        Thread.sleep(3000);

        assertEquals(0, writer.getWrittenElements().size());

        Thread.sleep(2000);

        assertEquals(3, writer.getWrittenElements().size());
        assertFalse(writer.getWrittenElements().containsKey("key1"));
        assertFalse(writer.getWrittenElements().containsKey("key2"));
        assertFalse(writer.getWrittenElements().containsKey("key3"));
        assertFalse(writer.getWrittenElements().containsKey("pre2key1suff2"));
        assertFalse(writer.getWrittenElements().containsKey("pre2key2suff2"));
        assertFalse(writer.getWrittenElements().containsKey("pre2key3suff2"));
        assertTrue(writer.getWrittenElements().containsKey("pre2key1suff2-batched"));
        assertTrue(writer.getWrittenElements().containsKey("pre2key2suff2-batched"));
        assertTrue(writer.getWrittenElements().containsKey("pre2key3suff2-batched"));

        assertEquals(0, writer.getDeletedKeys().size());

        cache.removeWithWriter(el2.getKey());
        cache.removeWithWriter(el3.getKey());

        Thread.sleep(2000);

        assertEquals(3, writer.getWrittenElements().size());
        assertEquals(0, writer.getDeletedKeys().size());

        Thread.sleep(3000);

        assertEquals(3, writer.getWrittenElements().size());
        assertEquals(2, writer.getDeletedKeys().size());
        assertTrue(writer.getDeletedKeys().contains("pre2key2suff2-batched"));
        assertTrue(writer.getDeletedKeys().contains("pre2key3suff2-batched"));
    }

    @Test
    public void testWriteBehindBatchedCoalescing() throws InterruptedException {
        Cache cache = new Cache(
                new CacheConfiguration("writeBehindBatchedCoalescing", 10)
                        .cacheWriter(new CacheWriterConfiguration()
                        .writeMode(CacheWriterConfiguration.WriteMode.WRITE_BEHIND)
                        .minWriteDelay(1)
                        .maxWriteDelay(1)
                        .writeBatching(true)
                        .writeBatchSize(10)
                        .writeCoalescing(true)
                        .cacheWriterFactory(new CacheWriterConfiguration.CacheWriterFactoryConfiguration()
                        .className("net.sf.ehcache.writer.TestCacheWriterFactory"))));
        assertNotNull(cache.getRegisteredCacheWriter());

        CacheManager.getInstance().addCache(cache);
        TestCacheWriter writer = (TestCacheWriter) cache.getRegisteredCacheWriter();
        assertEquals(0, writer.getWrittenElements().size());

        Element el1 = new Element("key1", "value1");
        Element el2a = new Element("key2", "value2a");
        Element el2b = new Element("key2", "value2b");
        Element el3 = new Element("key3", "value3");
        cache.putWithWriter(el1);
        cache.putWithWriter(el2a);
        cache.putWithWriter(el3);
        cache.putWithWriter(el2b);
        cache.removeWithWriter("key1");

        Thread.sleep(2000);

        assertEquals(2, writer.getWrittenElements().size());

        assertFalse(writer.getWrittenElements().containsKey("key1-batched"));
        assertTrue(writer.getWrittenElements().containsKey("key2-batched"));
        assertTrue(writer.getWrittenElements().containsKey("key3-batched"));
        assertEquals("value2b", writer.getWrittenElements().get("key2-batched").getObjectValue());
        assertEquals("value3", writer.getWrittenElements().get("key3-batched").getObjectValue());

        assertEquals(1, writer.getDeletedKeys().size());
        
        assertTrue(writer.getDeletedKeys().contains("key1-batched"));
    }

    @Test
    public void testWriteBehindExceptionWithoutRetrying() throws InterruptedException {
        Cache cache = new Cache(
                new CacheConfiguration("writeBehindExceptionWithoutRetrying", 10)
                        .cacheWriter(new CacheWriterConfiguration()
                        .writeMode(CacheWriterConfiguration.WriteMode.WRITE_BEHIND)
                        .minWriteDelay(0)
                        .maxWriteDelay(0)
                        .retryAttempts(0)
                        .retryAttemptDelaySeconds(0)));
        TestCacheWriterRetries writer = new TestCacheWriterRetries(3);
        cache.registerCacheWriter(writer);

        CacheManager.getInstance().addCache(cache);
        assertEquals(0, writer.getWrittenElements().size());

        cache.putWithWriter(new Element("key1", "value1"));
        cache.putWithWriter(new Element("key2", "value1"));
        cache.putWithWriter(new Element("key3", "value1"));
        cache.removeWithWriter("key2");

        Thread.sleep(2000);

        assertEquals(0, writer.getWrittenElements().size());
    }

    @Test
    public void testWriteBehindRetryWithoutExceptions() throws InterruptedException {
        Cache cache = new Cache(
                new CacheConfiguration("writeBehindRetryWithoutExceptions", 10)
                        .cacheWriter(new CacheWriterConfiguration()
                        .writeMode(CacheWriterConfiguration.WriteMode.WRITE_BEHIND)
                        .minWriteDelay(0)
                        .maxWriteDelay(0)
                        .retryAttempts(3)
                        .retryAttemptDelaySeconds(0)));
        TestCacheWriterRetries writer = new TestCacheWriterRetries(0);
        cache.registerCacheWriter(writer);

        CacheManager.getInstance().addCache(cache);
        assertEquals(0, writer.getWrittenElements().size());

        cache.putWithWriter(new Element("key1", "value1"));
        cache.putWithWriter(new Element("key2", "value1"));
        cache.putWithWriter(new Element("key3", "value1"));
        cache.removeWithWriter("key2");

        Thread.sleep(2000);

        assertEquals(2, writer.getWrittenElements().size());
        assertEquals(1, (long)writer.getWriteCount().get("key1"));
        assertEquals(1, (long)writer.getWriteCount().get("key2"));
        assertEquals(1, (long)writer.getWriteCount().get("key3"));
        assertFalse(writer.getDeleteCount().containsKey("key1"));
        assertEquals(1, (long)writer.getDeleteCount().get("key2"));
        assertFalse(writer.getDeleteCount().containsKey("key3"));
    }

    @Test
    public void testWriteBehindRetryWithoutDelay() throws InterruptedException {
        Cache cache = new Cache(
                new CacheConfiguration("writeBehindRetryWithoutDelay", 10)
                        .cacheWriter(new CacheWriterConfiguration()
                        .writeMode(CacheWriterConfiguration.WriteMode.WRITE_BEHIND)
                        .minWriteDelay(0)
                        .maxWriteDelay(0)
                        .retryAttempts(3)
                        .retryAttemptDelaySeconds(0)));
        TestCacheWriterRetries writer = new TestCacheWriterRetries(3);
        cache.registerCacheWriter(writer);

        CacheManager.getInstance().addCache(cache);
        assertEquals(0, writer.getWrittenElements().size());

        cache.putWithWriter(new Element("key1", "value1"));
        cache.putWithWriter(new Element("key2", "value2"));
        cache.putWithWriter(new Element("key3", "value3"));
        cache.removeWithWriter("key2");

        Thread.sleep(2000);

        assertEquals(2, writer.getWrittenElements().size());
        assertEquals(1, (long)writer.getWriteCount().get("key1"));
        assertEquals(1, (long)writer.getWriteCount().get("key2"));
        assertEquals(1, (long)writer.getWriteCount().get("key3"));
        assertEquals(1, (long)writer.getDeleteCount().get("key2"));
    }

    @Test
    public void testWriteBehindRetryWithDelay() throws InterruptedException {
        Cache cache = new Cache(
                new CacheConfiguration("writeBehindRetryWithDelay", 10)
                        .cacheWriter(new CacheWriterConfiguration()
                        .writeMode(CacheWriterConfiguration.WriteMode.WRITE_BEHIND)
                        .minWriteDelay(0)
                        .maxWriteDelay(0)
                        .retryAttempts(3)
                        .retryAttemptDelaySeconds(1)));
        TestCacheWriterRetries writer = new TestCacheWriterRetries(3);
        cache.registerCacheWriter(writer);

        CacheManager.getInstance().addCache(cache);
        assertEquals(0, writer.getWrittenElements().size());

        cache.putWithWriter(new Element("key1", "value1"));
        cache.putWithWriter(new Element("key2", "value2"));
        cache.putWithWriter(new Element("key3", "value3"));
        cache.removeWithWriter("key2");

        Thread.sleep(3000);

        assertEquals(0, writer.getWrittenElements().size());
        assertFalse(writer.getWriteCount().containsKey("key1"));
        assertFalse(writer.getWriteCount().containsKey("key2"));
        assertFalse(writer.getWriteCount().containsKey("key3"));
        assertFalse(writer.getDeleteCount().containsKey("key2"));

        Thread.sleep(1000);

        assertEquals(1, writer.getWrittenElements().size());
        assertEquals(1, (long)writer.getWriteCount().get("key1"));
        assertFalse(writer.getWriteCount().containsKey("key2"));
        assertFalse(writer.getWriteCount().containsKey("key3"));
        assertFalse(writer.getDeleteCount().containsKey("key2"));

        Thread.sleep(2000);

        assertEquals(1, writer.getWrittenElements().size());

        Thread.sleep(1000);

        assertEquals(2, writer.getWrittenElements().size());
        assertEquals(1, (long)writer.getWriteCount().get("key1"));
        assertEquals(1, (long)writer.getWriteCount().get("key2"));
        assertFalse(writer.getWriteCount().containsKey("key3"));
        assertFalse(writer.getDeleteCount().containsKey("key2"));

        Thread.sleep(2000);

        assertEquals(2, writer.getWrittenElements().size());

        Thread.sleep(1000);

        assertEquals(3, writer.getWrittenElements().size());
        assertEquals(1, (long)writer.getWriteCount().get("key1"));
        assertEquals(1, (long)writer.getWriteCount().get("key2"));
        assertEquals(1, (long)writer.getWriteCount().get("key3"));
        assertFalse(writer.getDeleteCount().containsKey("key2"));

        Thread.sleep(2000);

        assertEquals(3, writer.getWrittenElements().size());

        Thread.sleep(1000);

        assertEquals(2, writer.getWrittenElements().size());
        assertEquals(1, (long)writer.getWriteCount().get("key1"));
        assertEquals(1, (long)writer.getWriteCount().get("key2"));
        assertEquals(1, (long)writer.getWriteCount().get("key3"));
        assertEquals(1, (long)writer.getDeleteCount().get("key2"));
    }

    @Test
    public void testWriteBehindExceptionWithoutRetryingBatched() throws InterruptedException {
        Cache cache = new Cache(
                new CacheConfiguration("writeBehindExceptionWithoutRetryingBatched", 10)
                        .cacheWriter(new CacheWriterConfiguration()
                        .writeMode(CacheWriterConfiguration.WriteMode.WRITE_BEHIND)
                        .minWriteDelay(1)
                        .maxWriteDelay(1)
                        .writeBatching(true)
                        .writeBatchSize(10)
                        .retryAttempts(0)
                        .retryAttemptDelaySeconds(0)));
        TestCacheWriterRetries writer = new TestCacheWriterRetries(3);
        cache.registerCacheWriter(writer);

        CacheManager.getInstance().addCache(cache);
        assertEquals(0, writer.getWrittenElements().size());

        cache.putWithWriter(new Element("key1", "value1"));
        cache.putWithWriter(new Element("key2", "value1"));
        cache.putWithWriter(new Element("key3", "value1"));
        cache.removeWithWriter("key2");

        Thread.sleep(2000);

        assertEquals(2, writer.getWrittenElements().size());
        assertEquals(1, (long)writer.getWriteCount().get("key1"));
        assertEquals(1, (long)writer.getWriteCount().get("key2"));
        assertFalse(writer.getWriteCount().containsKey("key3"));
        assertFalse(writer.getDeleteCount().containsKey("key1"));
        assertFalse(writer.getDeleteCount().containsKey("key2"));
        assertFalse(writer.getDeleteCount().containsKey("key3"));
    }

    @Test
    public void testWriteBehindRetryWithoutExceptionsBatched() throws InterruptedException {
        Cache cache = new Cache(
                new CacheConfiguration("writeBehindRetryWithoutExceptionsBatched", 10)
                        .cacheWriter(new CacheWriterConfiguration()
                        .writeMode(CacheWriterConfiguration.WriteMode.WRITE_BEHIND)
                        .minWriteDelay(1)
                        .maxWriteDelay(1)
                        .writeBatching(true)
                        .writeBatchSize(10)
                        .retryAttempts(3)
                        .retryAttemptDelaySeconds(0)));
        TestCacheWriterRetries writer = new TestCacheWriterRetries(0);
        cache.registerCacheWriter(writer);

        CacheManager.getInstance().addCache(cache);
        assertEquals(0, writer.getWrittenElements().size());

        cache.putWithWriter(new Element("key1", "value1"));
        cache.putWithWriter(new Element("key2", "value2"));
        cache.putWithWriter(new Element("key3", "value3"));
        cache.removeWithWriter("key2");

        Thread.sleep(2000);

        assertEquals(2, writer.getWrittenElements().size());
        assertEquals(1, (long)writer.getWriteCount().get("key1"));
        assertEquals(1, (long)writer.getWriteCount().get("key2"));
        assertEquals(1, (long)writer.getWriteCount().get("key3"));
        assertFalse(writer.getDeleteCount().containsKey("key1"));
        assertEquals(1, (long)writer.getDeleteCount().get("key2"));
        assertFalse(writer.getDeleteCount().containsKey("key3"));
    }

    @Test
    public void testWriteBehindRetryWithoutDelayBatched() throws InterruptedException {
        Cache cache = new Cache(
                new CacheConfiguration("writeBehindRetryWithoutDelayBatched", 10)
                        .cacheWriter(new CacheWriterConfiguration()
                        .writeMode(CacheWriterConfiguration.WriteMode.WRITE_BEHIND)
                        .minWriteDelay(1)
                        .maxWriteDelay(1)
                        .writeBatching(true)
                        .writeBatchSize(10)
                        .retryAttempts(3)
                        .retryAttemptDelaySeconds(0)));
        TestCacheWriterRetries writer = new TestCacheWriterRetries(3);
        cache.registerCacheWriter(writer);

        CacheManager.getInstance().addCache(cache);
        assertEquals(0, writer.getWrittenElements().size());

        cache.putWithWriter(new Element("key1", "value1"));
        cache.putWithWriter(new Element("key2", "value2"));
        cache.putWithWriter(new Element("key3", "value3"));
        cache.removeWithWriter("key2");

        Thread.sleep(2000);

        assertEquals(2, writer.getWrittenElements().size());
        assertEquals(4, (long)writer.getWriteCount().get("key1"));
        assertEquals(4, (long)writer.getWriteCount().get("key2"));
        assertEquals(1, (long)writer.getWriteCount().get("key3"));
        assertEquals(1, (long)writer.getDeleteCount().get("key2"));
    }

    @Test
    public void testWriteBehindRetryWithDelayBatched() throws InterruptedException {
        Cache cache = new Cache(
                new CacheConfiguration("writeBehindRetryWithDelayBatched", 10)
                        .cacheWriter(new CacheWriterConfiguration()
                        .writeMode(CacheWriterConfiguration.WriteMode.WRITE_BEHIND)
                        .minWriteDelay(1)
                        .maxWriteDelay(1)
                        .writeBatching(true)
                        .writeBatchSize(10)
                        .retryAttempts(3)
                        .retryAttemptDelaySeconds(1)));
        TestCacheWriterRetries writer = new TestCacheWriterRetries(3);
        cache.registerCacheWriter(writer);

        CacheManager.getInstance().addCache(cache);
        assertEquals(0, writer.getWrittenElements().size());

        cache.putWithWriter(new Element("key1", "value1"));
        cache.putWithWriter(new Element("key2", "value2"));
        cache.putWithWriter(new Element("key3", "value3"));
        cache.removeWithWriter("key2");

        Thread.sleep(3000);

        assertEquals(2, writer.getWrittenElements().size());
        assertTrue(writer.getWriteCount().containsKey("key1"));
        assertTrue(writer.getWriteCount().containsKey("key2"));
        assertFalse(writer.getWriteCount().containsKey("key3"));
        assertFalse(writer.getDeleteCount().containsKey("key1"));
        assertFalse(writer.getDeleteCount().containsKey("key2"));
        assertFalse(writer.getDeleteCount().containsKey("key3"));

        Thread.sleep(3000);

        assertEquals(3, writer.getWrittenElements().size());
        assertEquals(4, (long)writer.getWriteCount().get("key1"));
        assertEquals(4, (long)writer.getWriteCount().get("key2"));
        assertEquals(1, (long)writer.getWriteCount().get("key3"));
        assertFalse(writer.getDeleteCount().containsKey("key1"));
        assertFalse(writer.getDeleteCount().containsKey("key2"));
        assertFalse(writer.getDeleteCount().containsKey("key3"));

        Thread.sleep(5000);

        assertEquals(2, writer.getWrittenElements().size());
        assertEquals(4, (long)writer.getWriteCount().get("key1"));
        assertEquals(4, (long)writer.getWriteCount().get("key2"));
        assertEquals(1, (long)writer.getWriteCount().get("key3"));
        assertFalse(writer.getDeleteCount().containsKey("key1"));
        assertEquals(1, (long)writer.getDeleteCount().get("key2"));
        assertFalse(writer.getDeleteCount().containsKey("key3"));
    }
}