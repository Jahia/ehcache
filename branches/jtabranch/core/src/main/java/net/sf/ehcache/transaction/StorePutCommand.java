package net.sf.ehcache.transaction;

import net.sf.ehcache.Element;
import net.sf.ehcache.store.Store;

/**
 * @author Alex Snaps
 */
public class StorePutCommand implements StoreWriteCommand {

    private final Element element;
    public StorePutCommand(final Element element) {
 
        this.element = element;
    }

    public boolean execute(final Store store) {
        store.put(element);
        return true;
    }

    public boolean isPut(Object key) {
        return element.getKey().equals(key);
    }

    public boolean isRemove(Object key) {
        return false;
    }

    public Element getElement() {
        return element;
    }

    public String getCommandName() {
        return Command.PUT;
    }
}
