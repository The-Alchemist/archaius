/**
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.config;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.configuration2.AbstractConfiguration;
import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.convert.DefaultListDelimiterHandler;
import org.apache.commons.configuration2.convert.DisabledListDelimiterHandler;
import org.apache.commons.configuration2.event.ConfigurationEvent;
import org.apache.commons.configuration2.event.Event;
import org.apache.commons.configuration2.event.EventListener;
import org.apache.commons.configuration2.event.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.config.validation.ValidationException;

/**
 * This class uses a ConcurrentHashMap for reading/writing a property to achieve high
 * throughput and thread safety. The implementation is lock free for {@link #getProperty(String)}
 * and {@link #setProperty(String, Object)}, but has some synchronization cost for 
 * {@link #addProperty(String, Object)} if the object to add is not a String or the key already exists.
 * <p> 
 * The methods from AbstractConfiguration related to listeners and event generation are overridden
 * so that adding/deleting listeners and firing events are no longer synchronized.
 * Also, it catches Throwable when it invokes the listeners, making
 * it more robust.
 * <p>
 * This configuration does not allow null as key or value and will throw NullPointerException
 * when trying to add or set properties with empty key or value.
 *
 * @author awang
 *
 */
public class ConcurrentMapConfiguration extends AbstractConfiguration {
    protected ConcurrentHashMap<String,Object> map;
    private static final Logger logger = LoggerFactory.getLogger(ConcurrentMapConfiguration.class);
    private static final int NUM_LOCKS = 32;
    private ReentrantLock[] locks = new ReentrantLock[NUM_LOCKS];

    /**
     * System property to disable delimiter parsing Apache Commons configurations
     */
    public static final String DISABLE_DELIMITER_PARSING = "archaius.configuration.disableDelimiterParsing";

    /**
     * Create an instance with an empty map.
     */
    public ConcurrentMapConfiguration() {
        map = new ConcurrentHashMap<String,Object>();
        for (int i = 0; i < NUM_LOCKS; i++) {
            locks[i] = new ReentrantLock();
        }
        String disableDelimiterParsing = System.getProperty(DISABLE_DELIMITER_PARSING, "false");
        Boolean disableDelimiterParsingAsBool = Boolean.valueOf(disableDelimiterParsing);
        setDelimiterParsingDisabledInternal(disableDelimiterParsingAsBool);

        // since this class does its own synchronization, set apache commons synchronizer to null so it uses a noop one
        setSynchronizer(null);
    }
    
    public ConcurrentMapConfiguration(Map<String, Object> mapToCopy) {
        this();
        map = new ConcurrentHashMap<String, Object>(mapToCopy);
    }

    /**
     * Create an instance by copying the properties from an existing Configuration.
     * Future changes to the Configuration passed in will not be reflected in this
     * object.
     * 
     * @param config Configuration to be copied
     */
    public ConcurrentMapConfiguration(Configuration config) {
        this();
        for (Iterator i = config.getKeys(); i.hasNext();) {
            String name = (String) i.next();
            Object value = config.getProperty(name);
            map.put(name, value);
        }
    }

    @Override
    public Object getPropertyInternal(String key)
    {
        return map.get(key);
    }

    @Override
    protected void addPropertyDirect(String key, Object value)
    {
        ReentrantLock lock = locks[Math.abs(key.hashCode()) % NUM_LOCKS];
        lock.lock();
        try {
            Object previousValue = map.putIfAbsent(key, value);
            if (previousValue == null) {
                return;
            }   
            if (previousValue instanceof List)
            {
                // the value is added to the existing list
                ((List) previousValue).add(value);
            }
            else
            {
                // the previous value is replaced by a list containing the previous value and the new value
                List<Object> list = new CopyOnWriteArrayList<Object>();
                list.add(previousValue);
                list.add(value);
                map.put(key, list);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isEmptyInternal()
    {
        return map.isEmpty();
    }

    @Override
    public boolean containsKeyInternal(String key)
    {
        return map.containsKey(key);
    }

    protected void clearPropertyDirect(String key)
    {
        map.remove(key);
    }

    @Override
    public Iterator<String> getKeysInternal()
    {
        return map.keySet().iterator();
    }

    /**
     * Load properties into the configuration. This method iterates through
     * the entries of the properties and call {@link #setProperty(String, Object)} for 
     * non-null key/value.
     */
    public void loadProperties(Properties props) {
        for (Map.Entry<Object, Object> entry: props.entrySet()) {
            String key = (String) entry.getKey();
            Object value = entry.getValue();
            if (key != null && value != null) {
                setProperty(key, value);
            }
        }
    }
    
    /**
     * Copy properties of a configuration into this configuration. This method simply
     * iterates the keys of the configuration to be copied and call {@link #setProperty(String, Object)}
     * for non-null key/value.
     */
    @Override
    public void copy(Configuration c)
    {
        if (c != null)
        {
            for (Iterator it = c.getKeys(); it.hasNext();)
            {
                String key = (String) it.next();
                Object value = c.getProperty(key);
                if (key != null && value != null) {
                    setProperty(key, value);
                }
            }
        }
    }

    /**
     * Clear the map and fire corresonding events.
     */
    @Override
    public void clearInternal()
    {
        map.clear();
    }

    /**
     * Utility method to get a Properties object from this Configuration
     */
    public Properties getProperties() {
        Properties p = new Properties();
        for (Iterator i = getKeys(); i.hasNext();) {
            String name = (String) i.next();
            String value = getString(name);
            p.put(name, value);
        }
        return p;
    }
    
    /**
     * Creates an event and calls {@link EventListener#onEvent(Event)}
     * for all listeners while catching Throwable.
     */
    @Override
    protected <T extends ConfigurationEvent> void fireEvent(EventType<T> type,
                                                            String propName, Object propValue, boolean before)
    {
        Collection<EventListener<? super ConfigurationEvent>> listeners = getEventListeners(ConfigurationEvent.ANY);
        if (listeners == null || listeners.size() == 0) {
            return;
        }        
        ConfigurationEvent event = createEvent(type, propName, propValue, before);
        for (EventListener l: listeners)
        {
            try {
                l.onEvent(event);
            } catch (ValidationException e) {
                if (before) {
                    throw e;
                } else {
                    logger.error("Unexpected exception", e);                    
                }
            } catch (Throwable e) {
                logger.error("Error firing configuration event", e);
            }
        }
    }
    
    public void addConfigurationListener(EventListener l) {
        addEventListener(ConfigurationEvent.ANY, l);
    }


    public boolean isDelimiterParsingDisabled() {
        return getListDelimiterHandler() == DisabledListDelimiterHandler.INSTANCE;
    }

    public char getListDelimiter() {
        return ((DefaultListDelimiterHandler) getListDelimiterHandler()).getDelimiter();
    }

    public void setDelimiterParsingDisabled(boolean delimiterParsingDisabled) {
        setDelimiterParsingDisabledInternal(delimiterParsingDisabled);
    }

    /**
     * Small helper that's called by the constructor to avoid the subclass's {@link #setDelimiterParsingDisabled(boolean)} being called
     * @param delimiterParsingDisabled
     */
    private void setDelimiterParsingDisabledInternal(boolean delimiterParsingDisabled) {
        if(delimiterParsingDisabled) {
            this.setListDelimiterHandler(DisabledListDelimiterHandler.INSTANCE);
        } else {
            this.setListDelimiterHandler(new DefaultListDelimiterHandler(','));
        }
    }

    public void setListDelimiter(char listDelimiter) {
        this.setListDelimiterHandler(new DefaultListDelimiterHandler(listDelimiter));
    }
}
