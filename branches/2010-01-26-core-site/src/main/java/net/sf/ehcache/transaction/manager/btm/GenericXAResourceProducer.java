package net.sf.ehcache.transaction.manager.btm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bitronix.tm.internal.XAResourceHolderState;
import bitronix.tm.recovery.RecoveryException;
import bitronix.tm.resource.ResourceConfigurationException;
import bitronix.tm.resource.ResourceObjectFactory;
import bitronix.tm.resource.ResourceRegistrar;
import bitronix.tm.resource.common.RecoveryXAResourceHolder;
import bitronix.tm.resource.common.ResourceBean;
import bitronix.tm.resource.common.XAResourceHolder;
import bitronix.tm.resource.common.XAResourceProducer;
import bitronix.tm.resource.common.XAStatefulHolder;
import bitronix.tm.resource.jdbc.PoolingDataSource;
import bitronix.tm.utils.ClassLoaderUtils;
import bitronix.tm.utils.PropertyUtils;

import java.util.Iterator;
import java.util.Map;

import javax.naming.Reference;
import javax.naming.StringRefAddr;
import javax.transaction.xa.XAResource;

public class GenericXAResourceProducer extends ResourceBean implements
		XAResourceProducer {
	private final static Logger log = LoggerFactory.getLogger(GenericXAResourceProducer.class);
	private static final long serialVersionUID = 1L;

	private XAResource xaResource;
	private GenericXAResourceHolder xaResourceHolder;
	private XAResourceHolderState xaResourceHolderState;
	private RecoveryXAResourceHolder recoveryXAResourceHolder;
	

	public GenericXAResourceProducer() {
    //
	}
	
	public synchronized static void registerXAResource(String uniqueName, XAResource resource) {
	  System.out.println("Enlist with BTM!!!");
		GenericXAResourceProducer producer = new GenericXAResourceProducer();
		producer.setXAResource(resource);
		producer.setUniqueName(uniqueName);
		producer.init();
	}
	
	public void setXAResource(XAResource resource) {
		this.xaResource = resource;
	}

	/* XAResourceProducer implementation */

	/**
	 * Need to instantiate the XAResource.
	 */
	public void init() {
		 
		if(xaResource != null && xaResourceHolder != null) {
			return;
		}
		try {
			xaResource = createXAResource(this);
			xaResourceHolder = (GenericXAResourceHolder)createPooledConnection(xaResource, this);
	        ResourceRegistrar.register(this);
	        xaResourceHolderState =  new XAResourceHolderState(xaResourceHolder, this);
	        xaResourceHolder.setXAResourceHolderState(xaResourceHolderState);
		} catch (Exception e) {
			e.printStackTrace();
			throw new ResourceConfigurationException("cannot create XAResources named " + getUniqueName(), e);
		}
	}

	/**
   * @throws RecoveryException  
   */
	public XAResourceHolderState startRecovery() throws RecoveryException {
		init();
		recoveryXAResourceHolder = xaResourceHolder.createRecoveryXAResourceHolder();
		return new XAResourceHolderState(recoveryXAResourceHolder, this);
	}

	/**
   * @throws RecoveryException  
   */
	public void endRecovery() throws RecoveryException {
//		if(xaResourceHolder == null)
//			return;
//		try {
//			if (recoveryXAResourceHolder != null) {
//				recoveryXAResourceHolder = null;
//			}
//			xaResourceHolder = null;
//		} catch (Exception ex) {
//			throw new RecoveryException("error ending recovery on " + this, ex);
//		}
	}

	public void setFailed(boolean failed) {
		//
	}

	public void close() {
		ResourceRegistrar.unregister(this);
		xaResourceHolder = null;
		recoveryXAResourceHolder = null;
	}

	public XAStatefulHolder createPooledConnection(Object xaFactory,
			ResourceBean bean) throws Exception {
		return new GenericXAResourceHolder((XAResource)xaFactory, this);
	}

	public XAResourceHolder findXAResourceHolder(XAResource aXAResource) {
		return (xaResourceHolder.getXAResource() == aXAResource) ? xaResourceHolder : null;
	}


	/**
	 * {@link PoolingDataSource} must alway have a unique name so this method
	 * builds a reference to this object using the unique name as
	 * {@link javax.naming.RefAddr}.
	 * 
	 * @return a reference to this {@link PoolingDataSource}.
	 */
	public Reference getReference() {
		if (log.isDebugEnabled())
			log.debug("creating new JNDI reference of " + this);
		return new Reference(GenericXAResourceProducer.class.getName(),
				new StringRefAddr("uniqueName", getUniqueName()),
				ResourceObjectFactory.class.getName(), null);
	}

	private XAResource createXAResource(ResourceBean bean) throws Exception {
		if(xaResource != null) return xaResource;
		String className = bean.getClassName();
		if (className == null)
			throw new IllegalArgumentException("className cannot be null");
		Class xaResourceClass = ClassLoaderUtils.loadClass(className);
		XAResource resource = (XAResource)xaResourceClass.newInstance();

		Iterator it = bean.getDriverProperties().entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry entry = (Map.Entry) it.next();
			String name = (String) entry.getKey();
			String value = (String) entry.getValue();
			if (log.isDebugEnabled())
				log.debug("setting vendor property '" + name + "' to '" + value
						+ "'");
			PropertyUtils.setProperty(xaResourceClass, name, value);
		}
		return resource;
	}
}