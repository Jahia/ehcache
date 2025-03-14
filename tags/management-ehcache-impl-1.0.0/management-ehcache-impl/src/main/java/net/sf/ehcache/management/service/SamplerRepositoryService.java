package net.sf.ehcache.management.service;

import net.sf.ehcache.CacheManager;

/**
 * A interface for services registering {@link CacheManager} objects for sampling.
 *
 * @author brandony
 */
public interface SamplerRepositoryService {
  /**
   * A locator interface for this interface.
   */
  interface Locator {
    SamplerRepositoryService locateSamplerRepositoryService();
  }

  /**
   * Register a {@link CacheManager} for sampling.
   *
   * @param cacheManager to register
   */
  void register(CacheManager cacheManager);

  /**
   * Unregister a {@link CacheManager} for sampling.
   *
   * @param cacheManager to register
   */
  void unregister(CacheManager cacheManager);

  /**
   * An indicator as to whether or not any {@link CacheManager} objects have been registered.
   * @return
   */
  boolean hasRegistered();
}
