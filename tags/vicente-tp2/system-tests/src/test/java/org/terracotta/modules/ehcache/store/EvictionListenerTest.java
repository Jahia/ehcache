/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package org.terracotta.modules.ehcache.store;

import com.tc.properties.TCPropertiesConsts;
import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheException;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.Element;
import net.sf.ehcache.event.CacheEventListener;

import org.terracotta.ehcache.tests.AbstractCacheTestBase;
import org.terracotta.ehcache.tests.ClientBase;
import org.terracotta.toolkit.Toolkit;
import org.terracotta.toolkit.concurrent.ToolkitBarrier;
import org.terracotta.toolkit.concurrent.atomic.ToolkitAtomicLong;

import com.tc.test.config.model.TestConfig;

import junit.framework.Assert;

public class EvictionListenerTest extends AbstractCacheTestBase {

  private static final int NODE_COUNT = 2;

  public EvictionListenerTest(TestConfig testConfig) {
    super("evict-cache-test.xml", testConfig, App.class, App.class);
    testConfig.getL2Config().setDgcEnabled(true);
    testConfig.getL2Config().setDgcIntervalInSec(10);
    testConfig.addTcProperty(TCPropertiesConsts.EHCACHE_EVICTOR_LOGGING_ENABLED, "true");
  }

  public static class App extends ClientBase implements CacheEventListener {

    private final ToolkitBarrier    barrier;
    private final ToolkitAtomicLong evictedCount;

    public App(String[] args) {
      super("test2", args);
      this.evictedCount = getClusteringToolkit().getAtomicLong("testLong");
      this.barrier = getClusteringToolkit().getBarrier("testBarrier", NODE_COUNT);
    }

    public static void main(String[] args) {
      new App(args).run();
    }

    @Override
    protected void runTest(Cache cache, Toolkit clusteringToolkit) throws Throwable {
      final int index = barrier.await();

      cache.getCacheEventNotificationService().registerListener(this);

      // XXX: assert that the cache is clustered via methods on cache config (when methods exist)
      System.err.println(cache);

      Assert.assertEquals(0, cache.getSize());

      barrier.await();

      int numOfElements = 1000;

      if (index == 0) {
        for (int i = 0; i < numOfElements; i++) {
          cache.put(new Element(i, "value"));
        }
      }
      barrier.await();

      Thread.sleep(30 * 1000);

      while (cache.getSize() >= 600) {
        Thread.sleep(1000);
        System.out.println("XXXX client" + index + " size: " + cache.getSize());
      }

      System.out.println("XXXX client" + index + " final size: " + cache.getSize());
      long evictedElements = numOfElements - cache.getSize();

      barrier.await();

      Thread.sleep(30 * 1000);
      System.out.println("XXXX client" + index + ": "
                         + cache.getCacheEventNotificationService().getElementsEvictedCounter());

      this.evictedCount.addAndGet(cache.getCacheEventNotificationService().getElementsEvictedCounter());

      barrier.await();

      Assert.assertEquals("XXXX client " + index + " failed.", evictedElements, this.evictedCount.get());
    }

    public void dispose() {
      // don't care
    }

    public void notifyElementEvicted(Ehcache cache, Element element) {
      System.out.println("Element [" + element + "] evicted");
    }

    public void notifyElementExpired(Ehcache cache, Element element) {
      // don't care
    }

    public void notifyElementPut(Ehcache cache, Element element) throws CacheException {
      // don't care
    }

    public void notifyElementRemoved(Ehcache cache, Element element) throws CacheException {
      // don't care
    }

    public void notifyElementUpdated(Ehcache cache, Element element) throws CacheException {
      // don't care
    }

    public void notifyRemoveAll(Ehcache cache) {
      // don't care
    }

    @Override
    public Object clone() throws CloneNotSupportedException {
      return super.clone();
    }
  }

}
