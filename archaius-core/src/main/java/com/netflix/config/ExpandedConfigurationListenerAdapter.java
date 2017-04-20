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

import org.apache.commons.configuration2.AbstractConfiguration;
import org.apache.commons.configuration2.CombinedConfiguration;
import org.apache.commons.configuration2.HierarchicalConfiguration;
import org.apache.commons.configuration2.event.ConfigurationEvent;
import org.apache.commons.configuration2.event.Event;
import org.apache.commons.configuration2.event.EventListener;
import org.apache.commons.configuration2.event.EventType;

/**
 * <p>An <code>ExpandedConfigurationListenerAdapter</code> wraps an instance
 * of {@link PropertyListener}.  When it receives the
 * property modification notification from the Apache Configuration, 
 * it translates the {@link ConfigurationEvent} into the corresponding 
 * events for {@link PropertyListener}.
 * <p>
 * It also has the capability to pause the event delivery through the {@link #setPauseListener(boolean)} API.
 * <p> This class is used as an adapter to attach a {@link PropertyListener} to a Configuration so that
 * methods in the {@link PropertyListener} will be called when there is a change in the configuration.
 *  
 */
public class ExpandedConfigurationListenerAdapter implements EventListener<ConfigurationEvent>
{
    /** The wrapped PropertyListener. */
    private PropertyListener expandedListener;

    static volatile boolean pauseListener;

    public static boolean isListenerPaused() {
        return pauseListener;
    }

    public static void setPauseListener(boolean pauseListener) {
        ExpandedConfigurationListenerAdapter.pauseListener = pauseListener;
    }

    /**
     * <p>Create a new <code>ExpandedConfigurationListenerAdapter</code>
     * that wraps the provided
     * {@link PropertyListener}.</p>
     *
     *
     * @param listener to
     *        wrap.
     * @throws NullPointerException if the configuration or listener is
     *         <code>null</code>.
     */
    public ExpandedConfigurationListenerAdapter(PropertyListener listener) {
        if (listener == null) {
            throw new NullPointerException("The listener cannot be null.");
        }
        this.expandedListener = listener;
    }

    /**
     * Returns the wrapped <code>PropertyListener</code>.
     *
     * @return the wrapped <code>PropertyListener</code>.
     */
    public PropertyListener getListener() {
        return expandedListener;
    }

    /**
     * {@inheritDoc}
     * @see EventListener#onEvent(Event)
     */
    @SuppressWarnings("unchecked")
    @Override
    public void onEvent(final ConfigurationEvent event) {

        if (pauseListener) {
            return;
        }

        // Grab the event information. Some values may be null.
        final Object source = event.getSource();
        final String name = event.getPropertyName();
        final Object value = event.getPropertyValue();
        final boolean beforeUpdate = event.isBeforeUpdate();


        // Handle the different types.
        EventType<? extends Event> eventType = event.getEventType();

        // Key identifies node where the Collection of nodes was added, or
        // null if it was at the root.
        if (EventType.isInstanceOf(eventType, ConfigurationEvent.ADD_NODES) ||
                EventType.isInstanceOf(eventType, ConfigurationEvent.SUBNODE_CHANGED)) {

            expandedListener.configSourceLoaded(source);
        } else if (EventType.isInstanceOf(eventType, ConfigurationEvent.ADD_PROPERTY)) {
            expandedListener.addProperty(source, name, value, beforeUpdate);


            // Entire configuration was cleared.
        } else if (EventType.isInstanceOf(eventType, ConfigurationEvent.CLEAR)) {
            expandedListener.clear(source, beforeUpdate);

            // Key identifies the single property that was cleared.
        } else if (EventType.isInstanceOf(eventType, ConfigurationEvent.CLEAR_PROPERTY)) {
            expandedListener.clearProperty(source, name, value, beforeUpdate);

            // Key identifies the nodes that were removed, along with all its
            // children. Value after update is the List of nodes that were
            // cleared. Before the update, the value is null.
        } else if (EventType.isInstanceOf(eventType, ConfigurationEvent.CLEAR_TREE)) {
            // Ignore this. We rewrote the clearTree() method below.

        } else if(EventType.isInstanceOf(eventType, ConfigurationEvent.SET_PROPERTY)) {
            // Key identifies the property that was set.
            expandedListener.setProperty(source, name, value, beforeUpdate);
        }
    }

    /**
     * {@inheritDoc}
     * @see Object#equals(Object)
     */
    @Override
    public boolean equals(final Object obj) {
        if (this == obj) return true;
        if (obj instanceof ExpandedConfigurationListenerAdapter ) {
            final ExpandedConfigurationListenerAdapter that = (ExpandedConfigurationListenerAdapter)obj;
            return this.expandedListener.equals(that.expandedListener);
        }
        return false;
    }

    /**
     * {@inheritDoc}
     * @see Object#hashCode()
     */
    @Override
    public int hashCode() {
        return expandedListener.hashCode();
    }
}
