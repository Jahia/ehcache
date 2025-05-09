/* ====================================================================
 * The Apache Software License, Version 1.1
 *
 * Copyright (c) 2003 - 2004 Greg Luck.  All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * 3. The end-user documentation included with the redistribution, if
 *    any, must include the following acknowlegement:
 *       "This product includes software developed by Greg Luck
 *       (http://sourceforge.net/users/gregluck) and contributors.
 *       See http://sourceforge.net/project/memberlist.php?group_id=93232
 *       for a list of contributors"
 *    Alternately, this acknowledgement may appear in the software itself,
 *    if and wherever such third-party acknowlegements normally appear.
 *
 * 4. The names "EHCache" must not be used to endorse or promote products
 *    derived from this software without prior written permission. For written
 *    permission, please contact Greg Luck (gregluck at users.sourceforge.net).
 *
 * 5. Products derived from this software may not be called "EHCache"
 *    nor may "EHCache" appear in their names without prior written
 *    permission of Greg Luck.
 *
 * THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED.  IN NO EVENT SHALL GREG LUCK OR OTHER
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF
 * USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by contributors
 * individuals on behalf of the EHCache project.  For more
 * information on EHCache, please see <http://ehcache.sourceforge.net/>.
 *
 */


package net.sf.ehcache.config;

import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;

/**
 * A SAX handler that configures a bean.
 *
 * @version $Id: BeanHandler.java,v 1.1 2006/03/09 06:38:19 gregluck Exp $
 * @author Adam Murdoch
 * @author Greg Luck
 */
public class BeanHandler extends DefaultHandler {
    private final Object bean;
    private ElementInfo element;
    private Locator locator;


    /**
     * Constructor
     */
    public BeanHandler(final Object bean) {
        this.bean = bean;
    }

    /**
     * Receive a Locator object for document events.
     */
    public void setDocumentLocator(Locator locator) {
        this.locator = locator;
    }

    /**
     * Receive notification of the start of an element.
     */
    public void startElement(final String uri,
                             final String localName,
                             final String qName,
                             final Attributes attributes)
            throws SAXException {
        // Create the child object
        if (element == null) {
            element = new ElementInfo(qName, bean);
        } else {
            final Object child = createChild(element, qName);
            element = new ElementInfo(element, qName, child);
        }

        // Set the attributes
        for (int i = 0; i < attributes.getLength(); i++) {
            final String attrName = attributes.getQName(i);
            final String attrValue = attributes.getValue(i);
            setAttribute(element, attrName, attrValue);
        }
    }

    /**
     * Receive notification of the end of an element.
     */
    public void endElement(final String uri,
                           final String localName,
                           final String qName)
            throws SAXException {
        if (element.parent != null) {
            addChild(element.parent.bean, element.bean, qName);
        }
        element = element.parent;
    }

    /**
     * Creates a child element of an object.
     */
    private Object createChild(final ElementInfo parent, final String name)
            throws SAXException {

        try {
            // Look for a create<name> method
            final Class parentClass = parent.bean.getClass();
            Method method = findCreateMethod(parentClass, name);
            if (method != null) {
                return method.invoke(parent.bean, new Object[] {});
            }

            // Look for an add<name> method
            method = findSetMethod(parentClass, "add", name);
            if (method != null) {
                return createInstance(parent.bean, method.getParameterTypes()[0]);
            }
        } catch (final Exception e) {
            throw new SAXException(getLocation() + ": Could not create nested element <" + name + ">.");
        }

        throw new SAXException(getLocation()
                + ": Element <"
                + parent.elementName
                + "> does not allow nested <"
                + name
                + "> elements.");
    }

    /**
     * Creates a child object.
     */
    private Object createInstance(Object parent, Class childClass)
            throws Exception {
        final Constructor[] constructors = childClass.getConstructors();
        ArrayList candidates = new ArrayList();
        for (int i = 0; i < constructors.length; i++) {
            final Constructor constructor = constructors[i];
            final Class[] params = constructor.getParameterTypes();
            if (params.length == 0) {
                candidates.add(constructor);
            } else if (params.length == 1 && params[0].isInstance(parent)) {
                candidates.add(constructor);
            }
        }
        switch (candidates.size()) {
            case 0:
                throw new Exception("No constructor for class " + childClass.getName());
            case 1:
                break;
            default:
                throw new Exception("Multiple constructors for class " + childClass.getName());
        }

        final Constructor constructor = (Constructor) candidates.remove(0);
        if (constructor.getParameterTypes().length == 0) {
            return constructor.newInstance(new Object[] {});
        } else {
            return constructor.newInstance(new Object[]{parent});
        }
    }

    /**
     * Finds a creator method.
     */
    private Method findCreateMethod(Class objClass, String name) {
        final String methodName = makeMethodName("create", name);
        final Method[] methods = objClass.getMethods();
        for (int i = 0; i < methods.length; i++) {
            final Method method = methods[i];
            if (!method.getName().equals(methodName)) {
                continue;
            }
            if (Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (method.getParameterTypes().length != 0) {
                continue;
            }
            if (method.getReturnType().isPrimitive() || method.getReturnType().isArray()) {
                continue;
            }
            return method;
        }

        return null;
    }

    /**
     * Builds a method name from an element or attribute name.
     */
    private String makeMethodName(final String prefix, final String name) {
        return prefix + Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * Sets an attribute.
     */
    private void setAttribute(final ElementInfo element,
                              final String attrName,
                              final String attrValue)
            throws SAXException {
        try {
            // Look for a set<name> method
            final Class objClass = element.bean.getClass();
            final Method method = findSetMethod(objClass, "set", attrName);
            if (method != null) {
                final Object realValue = convert(method.getParameterTypes()[0], attrValue);
                method.invoke(element.bean, new Object[]{realValue});
                return;
            }
        } catch (final InvocationTargetException e) {
            throw (SAXException) new SAXException(getLocation()
                    + ": Could not set attribute \"" + attrName + "\".").initCause(e.getTargetException());
        } catch (final Exception e) {
            throw new SAXException(getLocation() + ": Could not set attribute \"" + attrName + "\".");
        }

        throw new SAXException(getLocation()
                + ": Element <"
                + element.elementName
                + "> does not allow attribute \""
                + attrName
                + "\".");
    }

    /**
     * Converts a string to an object of a particular class.
     */
    private Object convert(final Class toClass, final String value)
            throws Exception {
        if (value == null) {
            return null;
        }
        if (toClass.isInstance(value)) {
            return value;
        }
        if (toClass == Long.class || toClass == Long.TYPE) {
            return Long.decode(value);
        }
        if (toClass == Integer.class || toClass == Integer.TYPE) {
            return Integer.decode(value);
        }
        if (toClass == Boolean.class || toClass == Boolean.TYPE) {
            return Boolean.valueOf(value);
        }
        throw new Exception("Cannot convert attribute value to class " + toClass.getName());
    }

    /**
     * Finds a setter method.
     */
    private Method findSetMethod(final Class objClass,
                                 final String prefix,
                                 final String name)
            throws Exception {
        final String methodName = makeMethodName(prefix, name);
        final Method[] methods = objClass.getMethods();
        Method candidate = null;
        for (int i = 0; i < methods.length; i++) {
            final Method method = methods[i];
            if (!method.getName().equals(methodName)) {
                continue;
            }
            if (Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (method.getParameterTypes().length != 1) {
                continue;
            }
            if (!method.getReturnType().equals(Void.TYPE)) {
                continue;
            }
            if (candidate != null) {
                throw new Exception("Multiple " + methodName + "() methods in class " + objClass.getName() + ".");
            }
            candidate = method;
        }

        return candidate;
    }

    /**
     * Attaches a child element to its parent.
     */
    private void addChild(final Object parent,
                          final Object child,
                          final String name)
            throws SAXException {
        try {
            // Look for an add<name> method on the parent
            final Method method = findSetMethod(parent.getClass(), "add", name);
            if (method != null) {
                method.invoke(parent, new Object[]{child});
            }
        } catch (final InvocationTargetException e) {
            final SAXException exc = new SAXException(getLocation() + ": Could not finish element <" + name + ">.");
            exc.initCause(e.getTargetException());
            throw exc;
        } catch (final Exception e) {
            throw new SAXException(getLocation() + ": Could not finish element <" + name + ">.");
        }
    }

    /**
     * Formats the current document location.
     */
    private String getLocation() {
        return locator.getSystemId() + ':' + locator.getLineNumber();
    }

    /**
     * Element info class
     */
    private static class ElementInfo {
        private final ElementInfo parent;
        private final String elementName;
        private final Object bean;

        public ElementInfo(final String elementName, final Object bean) {
            parent = null;
            this.elementName = elementName;
            this.bean = bean;
        }

        public ElementInfo(final ElementInfo parent, final String elementName, final Object bean) {
            this.parent = parent;
            this.elementName = elementName;
            this.bean = bean;
        }
    }
}
