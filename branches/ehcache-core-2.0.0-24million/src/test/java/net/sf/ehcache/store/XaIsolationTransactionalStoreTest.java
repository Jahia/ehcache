package net.sf.ehcache.store;

import net.sf.ehcache.Cache;
import net.sf.ehcache.Element;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.transaction.manager.TransactionManagerLookup;
import net.sf.ehcache.transaction.xa.EhcacheXAStoreImpl;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import javax.transaction.RollbackException;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.TransactionManager;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.util.ArrayList;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author Alex Snaps
 */
public class XaIsolationTransactionalStoreTest {

    private static final String KEY = "KEY";
    private static final String OTHER_KEY = "OTHER";

    private Store store;
    private Store underlyingStore;
    private Store oldVersionStore;


    private boolean keyInStore = false;

    @Before
    public void setupMockedStore()
            throws SystemException, RollbackException {

        underlyingStore = mock(Store.class);
        oldVersionStore = mock(Store.class);
        final Xid xid = mock(Xid.class);
        Transaction tx = mock(Transaction.class);

        when(tx.enlistResource((XAResource) anyObject())).thenAnswer(new Answer() {
            public Object answer(InvocationOnMock invocation) {
                XAResource xaResource = (XAResource) invocation.getArguments()[0];
                try {
                    xaResource.start(xid, XAResource.TMNOFLAGS);
                } catch (XAException e) {
                    throw new RuntimeException(e);
                }
                return true;
            }
        });


        TransactionManager tm = mock(TransactionManager.class);
        when(tm.getTransaction()).thenReturn(tx);
        TransactionManagerLookup transactionManagerLookup = mock(TransactionManagerLookup.class);
        when(transactionManagerLookup.getTransactionManager()).thenReturn(tm);

        CacheConfiguration cacheConfiguration = mock(CacheConfiguration.class);
        when(cacheConfiguration.clone()).thenReturn(cacheConfiguration);
        when(cacheConfiguration.getCacheEventListenerConfigurations()).thenReturn(new ArrayList());
        Cache cache = new Cache(cacheConfiguration);

        EhcacheXAStoreImpl xaStore = new EhcacheXAStoreImpl(underlyingStore, oldVersionStore);
        when(underlyingStore.isCacheCoherent()).thenReturn(true);
        when(underlyingStore.getKeyArray()).thenAnswer(new Answer<Object>() {
            public Object answer(InvocationOnMock invocationOnMock)
                    throws Throwable {
                if (keyInStore) {
                    return new Object[]{KEY};
                }
                return new Object[0];
            }
        });
        when(underlyingStore.containsKey(KEY)).thenAnswer(new Answer<Object>() {
            public Object answer(InvocationOnMock invocationOnMock)
                    throws Throwable {
                return keyInStore;
            }
        });
        when(underlyingStore.containsKey(OTHER_KEY)).thenAnswer(new Answer<Object>() {
            public Object answer(InvocationOnMock invocationOnMock)
                    throws Throwable {
                return false;
            }
        });
        store = new XATransactionalStore(cache, xaStore, transactionManagerLookup);
    }

    @Test
    public void testIsolationNoKey() {
        Element element = new Element(KEY, "VALUE");
        assertThat(store.get(element.getKey()), nullValue());
        assertThat(store.getSize(), is(0));
        assertThat(store.getTerracottaClusteredSize(), is(0));
        assertThat(store.containsKey(KEY), is(false));
        store.put(element);
        assertThat(store.get(element.getKey()), sameInstance(element));
        assertThat(store.getSize(), is(1));
        assertThat(store.getTerracottaClusteredSize(), is(1));
        assertThat(store.containsKey(KEY), is(true));
        assertThat(store.remove(element.getKey()), sameInstance(element));
        assertThat(store.getSize(), is(0));
        assertThat(store.containsKey(KEY), is(false));
        assertThat(store.get(element.getKey()), nullValue());
        assertThat(store.remove(element.getKey()), nullValue());
        assertThat(store.getSize(), is(0));
        assertThat(store.getTerracottaClusteredSize(), is(0));
        store.put(element);
        store.put(element);
        assertThat(store.get(element.getKey()), sameInstance(element));
        assertThat(store.getSize(), is(1));
        assertThat(store.getTerracottaClusteredSize(), is(1));
        assertThat(store.containsKey(KEY), is(true));
        assertThat(store.remove(KEY), sameInstance(element));
        assertThat(store.remove(KEY), nullValue());
        store.remove(KEY);
        assertThat(store.getSize(), is(0));
        assertThat(store.getTerracottaClusteredSize(), is(0));
        assertThat(store.get(element.getKey()), nullValue());

        assertThat(store.containsKey(KEY), is(false));
    }

    @Test
    public void testIsolationWithKey() {
        Element element = new Element(KEY, "VALUE");
        putInStore(element);

        assertThat(store.get(element.getKey()), sameInstance(element));
        assertThat(store.getSize(), is(1));
        assertThat(store.getTerracottaClusteredSize(), is(1));
        assertThat(store.getKeyArray().length, is(1));
        assertThat(store.containsKey(KEY), is(true));
        Element newElement = new Element(element.getKey(), "NEW_VALUE");
        store.put(newElement);
        assertThat(store.get(element.getKey()), sameInstance(newElement));
        assertThat(store.getSize(), is(1));
        assertThat(store.getTerracottaClusteredSize(), is(1));
        assertThat(store.getKeyArray().length, is(1));
        assertThat(store.containsKey(KEY), is(true));
        assertThat(store.remove(element.getKey()), sameInstance(newElement));
        assertThat(store.getSize(), is(0));
        assertThat(store.getTerracottaClusteredSize(), is(0));
        assertThat(store.getKeyArray().length, is(0));
        assertThat(store.get(element.getKey()), nullValue());
        assertThat(store.remove(element.getKey()), nullValue());
        assertThat(store.getSize(), is(0));
        assertThat(store.getTerracottaClusteredSize(), is(0));
        assertThat(store.getKeyArray().length, is(0));
        assertThat(store.containsKey(KEY), is(false));
        store.put(newElement);
        store.put(newElement);
        assertThat(store.get(element.getKey()), sameInstance(newElement));
        assertThat(store.getSize(), is(1));
        assertThat(store.getTerracottaClusteredSize(), is(1));
        assertThat(store.getKeyArray().length, is(1));
        assertThat(store.containsKey(KEY), is(true));
        assertThat(store.remove(KEY), sameInstance(newElement));
        assertThat(store.remove(KEY), nullValue());
        assertThat(store.getSize(), is(0));
        assertThat(store.getTerracottaClusteredSize(), is(0));
        assertThat(store.getKeyArray().length, is(0));
        assertThat(store.get(element.getKey()), nullValue());
        assertThat(store.containsKey(KEY), is(false));
    }

    @Test
    public void testIsolationWithOtherKey() {
        Element element = new Element(KEY, "VALUE");
        putInStore(element);

        assertThat(store.get(element.getKey()), sameInstance(element));
        assertThat(store.getSize(), is(1));
        assertThat(store.getTerracottaClusteredSize(), is(1));
        assertThat(store.getKeyArray().length, is(1));
        assertThat(store.containsKey(KEY), is(true));
        assertThat(store.containsKey(OTHER_KEY), is(false));
        Element newElement = new Element(OTHER_KEY, "NEW_VALUE");
        store.put(newElement);
        assertThat(store.get(element.getKey()), sameInstance(element));
        assertThat(store.get(KEY), sameInstance(element));
        assertThat(store.get(OTHER_KEY), sameInstance(newElement));
        assertThat(store.getSize(), is(2));
        assertThat(store.getTerracottaClusteredSize(), is(2));
        assertThat(store.getKeyArray().length, is(2));
        assertThat(store.containsKey(element.getKey()), is(true));
        assertThat(store.containsKey(KEY), is(true));
        assertThat(store.remove(OTHER_KEY), sameInstance(newElement));
        assertThat(store.getSize(), is(1));
        assertThat(store.getTerracottaClusteredSize(), is(1));
        assertThat(store.getKeyArray().length, is(1));
        assertThat(store.get(OTHER_KEY), nullValue());
        assertThat(store.get(KEY), sameInstance(element));
        assertThat(store.remove(OTHER_KEY), nullValue());
        assertThat(store.getSize(), is(1));
        assertThat(store.getTerracottaClusteredSize(), is(1));
        assertThat(store.getKeyArray().length, is(1));
        assertThat(store.containsKey(OTHER_KEY), is(false));
        assertThat(store.containsKey(KEY), is(true));
        store.put(newElement);
        store.put(newElement);
        assertThat(store.get(OTHER_KEY), sameInstance(newElement));
        assertThat(store.getSize(), is(2));
        assertThat(store.getTerracottaClusteredSize(), is(2));
        assertThat(store.getKeyArray().length, is(2));
        assertThat(store.containsKey(KEY), is(true));
        assertThat(store.containsKey(OTHER_KEY), is(true));
        assertThat(store.remove(OTHER_KEY), sameInstance(newElement));
        assertThat(store.remove(OTHER_KEY), nullValue());
        assertThat(store.remove(KEY), sameInstance(element));
        assertThat(store.remove(KEY), nullValue());
        assertThat(store.getSize(), is(0));
        assertThat(store.getTerracottaClusteredSize(), is(0));
        assertThat(store.getKeyArray().length, is(0));
        assertThat(store.get(OTHER_KEY), nullValue());
        assertThat(store.get(KEY), nullValue());
        assertThat(store.containsKey(OTHER_KEY), is(false));
        assertThat(store.containsKey(KEY), is(false));
    }

    private void putInStore(final Element element) {
        when(oldVersionStore.get(element.getKey())).thenReturn(element);
        when(oldVersionStore.getQuiet(element.getKey())).thenReturn(element);

        when(underlyingStore.getSize()).thenReturn(1);
        when(underlyingStore.getTerracottaClusteredSize()).thenReturn(1);
        keyInStore = true;
    }

}
