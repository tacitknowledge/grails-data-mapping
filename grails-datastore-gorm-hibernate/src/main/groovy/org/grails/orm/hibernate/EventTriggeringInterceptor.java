/*
 * Copyright 2011 SpringSource.
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
package org.grails.orm.hibernate;

import grails.core.GrailsDomainClass;
import groovy.lang.GroovySystem;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.grails.orm.hibernate.cfg.GrailsDomainBinder;
import org.grails.orm.hibernate.cfg.GrailsHibernateUtil;
import org.grails.orm.hibernate.support.ClosureEventListener;
import org.grails.orm.hibernate.support.SoftKey;
import org.codehaus.groovy.runtime.typehandling.DefaultTypeTransformation;
import org.grails.core.artefact.AnnotationDomainClassArtefactHandler;
import org.grails.core.artefact.DomainClassArtefactHandler;
import org.grails.datastore.gorm.timestamp.DefaultTimestampProvider;
import org.grails.datastore.gorm.timestamp.TimestampProvider;
import org.grails.datastore.mapping.engine.event.AbstractPersistenceEvent;
import org.grails.datastore.mapping.engine.event.ValidationEvent;
import org.hibernate.HibernateException;
import org.hibernate.event.PostDeleteEvent;
import org.hibernate.event.PostInsertEvent;
import org.hibernate.event.PostLoadEvent;
import org.hibernate.event.PostUpdateEvent;
import org.hibernate.event.PreDeleteEvent;
import org.hibernate.event.PreInsertEvent;
import org.hibernate.event.PreLoadEvent;
import org.hibernate.event.PreUpdateEvent;
import org.hibernate.event.SaveOrUpdateEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.core.env.PropertyResolver;

/**
 * <p>Invokes closure events on domain entities such as beforeInsert, beforeUpdate and beforeDelete.
 *
 * @author Graeme Rocher
 * @author Lari Hotari
 * @author Burt Beckwith
 * @since 2.0
 */
public class EventTriggeringInterceptor extends AbstractEventTriggeringInterceptor {

    protected transient ConcurrentMap<SoftKey<Class<?>>, ClosureEventListener> eventListeners =
            new ConcurrentHashMap<SoftKey<Class<?>>, ClosureEventListener>();

    protected final GrailsDomainBinder domainBinder = GrailsHibernateUtil.getDomainBinder();
    
    private TimestampProvider timestampProvider = new DefaultTimestampProvider();

    public EventTriggeringInterceptor(HibernateDatastore datastore, PropertyResolver co) {
        super(datastore);

        Object failOnErrorConfig = co.getProperty("grails.gorm.failOnError", Object.class);
        if (failOnErrorConfig instanceof List) {
            failOnError = true;
            failOnErrorPackages = (List<?>)failOnErrorConfig;
        }
        else {
            failOnError = DefaultTypeTransformation.castToBoolean(failOnErrorConfig);
        }
    }

    @Override
    protected void onPersistenceEvent(final AbstractPersistenceEvent event) {
        switch (event.getEventType()) {
            case PreInsert:
                if (onPreInsert((PreInsertEvent)event.getNativeEvent())) {
                    event.cancel();
                }
                break;
            case PostInsert:
                onPostInsert((PostInsertEvent)event.getNativeEvent());
                break;
            case PreUpdate:
                if (onPreUpdate((PreUpdateEvent)event.getNativeEvent())) {
                    event.cancel();
                }
                break;
            case PostUpdate:
                onPostUpdate((PostUpdateEvent)event.getNativeEvent());
                break;
            case PreDelete:
                if (onPreDelete((PreDeleteEvent)event.getNativeEvent())) {
                    event.cancel();
                }
                break;
            case PostDelete:
                onPostDelete((PostDeleteEvent)event.getNativeEvent());
                break;
            case PreLoad:
                onPreLoad((PreLoadEvent)event.getNativeEvent());
                break;
            case PostLoad:
                onPostLoad((PostLoadEvent)event.getNativeEvent());
                break;
            case SaveOrUpdate:
                onSaveOrUpdate((SaveOrUpdateEvent)event.getNativeEvent());
                break;
            case Validation:
                onValidate((ValidationEvent)event);
                break;
            default:
                throw new IllegalStateException("Unexpected EventType: " + event.getEventType());
        }
    }

    public void onSaveOrUpdate(SaveOrUpdateEvent event) throws HibernateException {
        ClosureEventListener eventListener = findEventListener(event.getObject());
        if (eventListener != null) {
            eventListener.onSaveOrUpdate(event);
        }
    }

    public void onPreLoad(PreLoadEvent event) {
        Object entity = event.getEntity();
        GrailsHibernateUtil.ensureCorrectGroovyMetaClass(entity, entity.getClass());
        ClosureEventListener eventListener = findEventListener(entity);
        if (eventListener != null) {
            eventListener.onPreLoad(event);
        }
    }

    public void onPostLoad(PostLoadEvent event) {
        ClosureEventListener eventListener = findEventListener(event.getEntity());
        if (eventListener != null) {
            eventListener.onPostLoad(event);
        }
    }

    public void onPostInsert(PostInsertEvent event) {
        ClosureEventListener eventListener = findEventListener(event.getEntity());
        if (eventListener != null) {
            eventListener.onPostInsert(event);
        }
    }

    public boolean onPreInsert(PreInsertEvent event) {
        boolean evict = false;
        ClosureEventListener eventListener = findEventListener(event.getEntity());
        if (eventListener != null) {
            evict = eventListener.onPreInsert(event);
        }
        return evict;
    }

    public boolean onPreUpdate(PreUpdateEvent event) {
        boolean evict = false;
        ClosureEventListener eventListener = findEventListener(event.getEntity());
        if (eventListener != null) {
            evict = eventListener.onPreUpdate(event);
        }
        return evict;
    }

    public void onPostUpdate(PostUpdateEvent event) {
        ClosureEventListener eventListener = findEventListener(event.getEntity());
        if (eventListener != null) {
            eventListener.onPostUpdate(event);
        }
    }

    public boolean onPreDelete(PreDeleteEvent event) {
        boolean evict = false;
        ClosureEventListener eventListener = findEventListener(event.getEntity());
        if (eventListener != null) {
            evict = eventListener.onPreDelete(event);
        }
        return evict;
    }

    public void onPostDelete(PostDeleteEvent event) {
        ClosureEventListener eventListener = findEventListener(event.getEntity());
        if (eventListener != null) {
            eventListener.onPostDelete(event);
        }
    }

    public void onValidate(ValidationEvent event) {
        ClosureEventListener eventListener = findEventListener(event.getEntityObject());
        if (eventListener != null) {
            eventListener.onValidate(event);
        }
    }

    protected ClosureEventListener findEventListener(Object entity) {
        if (entity == null) return null;
        Class<?> clazz = entity.getClass();

        SoftKey<Class<?>> key = new SoftKey<Class<?>>(clazz);
        ClosureEventListener eventListener = eventListeners.get(key);
        if (eventListener != null) {
            return eventListener;
        }

        Boolean shouldTrigger = cachedShouldTrigger.get(key);
        if (shouldTrigger == null || shouldTrigger) {
            synchronized(clazz) {
                eventListener = eventListeners.get(key);
                if (eventListener == null) {
                    shouldTrigger = (GroovySystem.getMetaClassRegistry().getMetaClass(entity.getClass()) != null &&
                            (DomainClassArtefactHandler.isDomainClass(clazz) || AnnotationDomainClassArtefactHandler.isJPADomainClass(clazz)) &&
                            isDefinedByCurrentDataStore(entity, domainBinder));
                    if (shouldTrigger) {
                        eventListener = new ClosureEventListener(clazz, failOnError, failOnErrorPackages, timestampProvider);
                        ClosureEventListener previous = eventListeners.putIfAbsent(key, eventListener);
                        if (previous != null) {
                            eventListener = previous;
                        }
                    }
                    cachedShouldTrigger.put(key, shouldTrigger);
                }
            }
        }
        return eventListener;
    }

    /**
     * {@inheritDoc}
     * @see org.springframework.context.event.SmartApplicationListener#supportsEventType(java.lang.Class)
     */
    public boolean supportsEventType(Class<? extends ApplicationEvent> eventType) {
        return AbstractPersistenceEvent.class.isAssignableFrom(eventType);
    }

    protected List<String> getDatasourceNames(GrailsDomainClass dc) {
        return GrailsHibernateUtil.getDatasourceNames(dc);
    }
    
    public TimestampProvider getTimestampProvider() {
        return timestampProvider;
    }

    public void setTimestampProvider(TimestampProvider timestampProvider) {
        this.timestampProvider = timestampProvider;
    }
}
