/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package org.terracotta.modules.ehcache.l1bm;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Element;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.config.TerracottaConfiguration;
import net.sf.ehcache.config.TerracottaConfiguration.Consistency;
import net.sf.ehcache.config.TerracottaConfiguration.ValueMode;

import org.terracotta.api.ClusteringToolkit;
import org.terracotta.coordination.Barrier;
import org.terracotta.ehcache.tests.AbstractCacheTestBase;
import org.terracotta.ehcache.tests.ClientBase;

import com.tc.test.config.model.TestConfig;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;

import junit.framework.Assert;

public class L1BMOnHeapWithTTLSanityTest extends AbstractCacheTestBase {
  private static final int NODE_COUNT = 2;

  public L1BMOnHeapWithTTLSanityTest(TestConfig testConfig) {
    super(testConfig, App.class, App.class);
  }

  public static class App extends ClientBase {
    private final Barrier barrier;

    public App(String[] args) {
      super(args);
      this.barrier = getClusteringToolkit().getBarrier("test", NODE_COUNT);
    }

    public static void main(String[] args) {
      new App(args).run();
    }

    @Override
    protected void runTest(Cache cache, ClusteringToolkit clusteringToolkit) throws Throwable {

      Cache dcv2EventualSerializationWithStats = createCache("dcv2EventualSerializationWithStats", cacheManager,
                                                              Consistency.EVENTUAL, ValueMode.SERIALIZATION);
      dcv2EventualSerializationWithStats.setStatisticsEnabled(true);
      testL1BigMemorySanity(dcv2EventualSerializationWithStats, true);

      Cache dcv2EventualIdentityWithStats = createCache("dcv2EventualIdentityWithStats", cacheManager,
                                                         Consistency.EVENTUAL, ValueMode.IDENTITY);
      dcv2EventualIdentityWithStats.setStatisticsEnabled(true);
      testL1BigMemorySanity(dcv2EventualIdentityWithStats, true);

      Cache dcv2EventualSerializationWithoutStats = createCache("dcv2EventualSerializationWithoutStats", cacheManager,
                                                                 Consistency.EVENTUAL, ValueMode.SERIALIZATION);
      dcv2EventualSerializationWithoutStats.setStatisticsEnabled(false);
      testL1BigMemorySanity(dcv2EventualSerializationWithoutStats, true);

      Cache dcv2EventualIdentityWithoutStats = createCache("dcv2EventualIdentityWithoutStats", cacheManager,
                                                            Consistency.EVENTUAL, ValueMode.IDENTITY);
      dcv2EventualIdentityWithoutStats.setStatisticsEnabled(false);
      testL1BigMemorySanity(dcv2EventualIdentityWithoutStats, true);

      Cache dcv2StrongSerializationWithStats = createCache("dcv2StrongSerializationWithStats", cacheManager,
                                                            Consistency.STRONG, ValueMode.SERIALIZATION);
      dcv2StrongSerializationWithStats.setStatisticsEnabled(true);
      testL1BigMemorySanity(dcv2StrongSerializationWithStats, false);

      Cache dcv2StrongIdentityWithStats = createCache("dcv2StrongIdentityWithStats", cacheManager,
                                                       Consistency.STRONG, ValueMode.IDENTITY);
      dcv2StrongIdentityWithStats.setStatisticsEnabled(true);
      testL1BigMemorySanity(dcv2StrongIdentityWithStats, false);

      Cache dcv2StrongWithoutStats = createCache("dcv2StrongWithoutStats", cacheManager, Consistency.STRONG,
                                                  ValueMode.SERIALIZATION);
      dcv2StrongWithoutStats.setStatisticsEnabled(false);
      testL1BigMemorySanity(dcv2StrongWithoutStats, false);

      Cache dcv2StrongIdentityWithoutStats = createCache("dcv2StrongIdentityWithoutStats", cacheManager,
                                                          Consistency.STRONG, ValueMode.IDENTITY);
      dcv2StrongIdentityWithoutStats.setStatisticsEnabled(false);
      testL1BigMemorySanity(dcv2StrongIdentityWithoutStats, false);
    }

    private void testL1BigMemorySanity(Cache cache, boolean shouldWait) throws InterruptedException,
        BrokenBarrierException {
      int index = barrier.await();
      int numOfElements = 500;
      if (index == 0) {
        for (int i = 0; i < numOfElements; i++) {
          cache.put(new Element("key" + i, "val" + i));
        }
        System.out.println("XXXXX done with putting " + cache.getSize() + " entries");
      }
      barrier.await();
      if (shouldWait) {
        while (cache.getSize() != numOfElements) {
          Thread.sleep(1000);
        }
      }
      Assert.assertEquals(numOfElements, cache.getSize());
      System.out.println("XXXXXX client " + index + " cache size: " + cache.getSize() + " local: "
                         + cache.getMemoryStoreSize());
      if (index == 0) {
        Assert.assertTrue(cache.getMemoryStoreSize() > 0);
      } else {
        Assert.assertEquals(0, cache.getMemoryStoreSize());
      }

      barrier.await();

      System.out.println("XXXXXX testing get");
      for (int i = 0; i < numOfElements; i++) {
        Assert.assertNotNull("value for key" + i + " is null", cache.get("key" + i));
      }
      Assert.assertTrue(cache.getMemoryStoreSize() > 0);

      barrier.await();
      System.out.println("XXXX done with basic get, now removing random entries...");
      Set removedKeySet = new HashSet<String>();
      for (int i = 0; i < numOfElements; i++) {
        if (i % 10 == 0) {
          removedKeySet.add("key" + i);
        }
      }

      if (index == 0) {
        cache.removeAll(removedKeySet);
      }

      barrier.await();
      if (shouldWait) {
        waitForAllCurrentTransactionsToComplete();
      }
      barrier.await();

      System.out.println("XXXXX removed " + removedKeySet.size() + " elemets. Cache size: " + cache.getSize());

      System.out.println("XXXX testing get after remove.");
      for (int i = 0; i < numOfElements; i++) {
        String key = "key" + i;
        if (removedKeySet.contains(key)) {
          Assert.assertNull("value for " + key + " is not null", cache.get(key));
        } else {
          Assert.assertNotNull("value for " + key + " is null", cache.get(key));
        }
      }

      // wait for 90 secs to get element expired
      System.out.println("waiting fo elements to get expired...");
      Thread.sleep(30000);
      System.out.println("All elements should be null after expiration...");
      for (int i = 0; i < numOfElements; i++) {
        Assert.assertNull("value for key" + i + " is not null", cache.get("key" + i));
      }
      System.out.println("XXXXXX done with " + cache.getName());
    }

    private Cache createCache(String cacheName, CacheManager cm, Consistency consistency,
                               ValueMode valueMode) {
      CacheConfiguration cacheConfiguration = new CacheConfiguration();
      cacheConfiguration.setTimeToLiveSeconds(30);
      cacheConfiguration.setName(cacheName);
      cacheConfiguration.setMaxBytesLocalHeap(409600L);

      TerracottaConfiguration tcConfiguration = new TerracottaConfiguration();
      tcConfiguration.setConsistency(consistency);
      tcConfiguration.setValueMode(valueMode.name());
      cacheConfiguration.addTerracotta(tcConfiguration);

      Cache cache = new Cache(cacheConfiguration);
      cm.addCache(cache);
      return cache;
    }
  }
}
