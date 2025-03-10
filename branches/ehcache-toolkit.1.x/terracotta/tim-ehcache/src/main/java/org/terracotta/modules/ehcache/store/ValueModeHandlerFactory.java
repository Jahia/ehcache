/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package org.terracotta.modules.ehcache.store;

import net.sf.ehcache.CacheException;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.config.TerracottaConfiguration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class ValueModeHandlerFactory {

  private static final Logger LOG = LoggerFactory.getLogger(ValueModeHandlerFactory.class.getName());

  public static ValueModeHandler createValueModeHandler(final ClusteredStore store,
                                                        final CacheConfiguration cacheConfiguration) {

    final TerracottaConfiguration terracottaConfiguration = cacheConfiguration.getTerracottaConfiguration();
    switch (terracottaConfiguration.getValueMode()) {
      case IDENTITY:
        return new ValueModeHandlerIdentity(store);
      case SERIALIZATION:
        if (hibernateTypesPresent()) {
          LOG.info("Hibernate types found on the classpath : Enabling Hibernate value mode optimizations");
          return new ValueModeHandlerHibernate(store, cacheConfiguration);
        } else {
          return new ValueModeHandlerSerialization(store, cacheConfiguration);
        }
      default:
        throw new CacheException("The Terracotta value type '" + terracottaConfiguration.getValueMode()
                                 + "' is not supported.");
    }
  }

  private static boolean hibernateTypesPresent() {
    try {
      Class.forName("org.hibernate.cache.CacheKey");
      return true;
    } catch (ClassNotFoundException ex) {
      return false;
    }
  }
}
