/* Copyright (C) 2011 SpringSource
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.grails.orm.hibernate;

import grails.core.GrailsApplication;
import grails.core.support.GrailsApplicationAware;

import java.util.Map;

import org.grails.datastore.mapping.core.Session;
import org.grails.datastore.mapping.model.MappingContext;
import org.hibernate.SessionFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.PropertyResolver;

/**
 * Datastore implementation that uses a Hibernate SessionFactory underneath.
 *
 * @author Graeme Rocher
 * @since 2.0
 */
public class HibernateDatastore extends AbstractHibernateDatastore implements GrailsApplicationAware {

    private GrailsApplication grailsApplication;
    private int defaultFlushMode;

    public HibernateDatastore(MappingContext mappingContext, SessionFactory sessionFactory, PropertyResolver config) {
        super(mappingContext, sessionFactory, config);
    }

    public HibernateDatastore(MappingContext mappingContext, SessionFactory sessionFactory, PropertyResolver config, ApplicationContext applicationContext) {
        this(mappingContext, sessionFactory, config, applicationContext, GrailsHibernateTemplate.FLUSH_AUTO);
    }

    public HibernateDatastore(MappingContext mappingContext, SessionFactory sessionFactory, PropertyResolver config, ApplicationContext applicationContext, int defaultFlushMode) {
        super(mappingContext, sessionFactory, config, applicationContext);
        this.defaultFlushMode = defaultFlushMode;
    }

    @Override
    protected Session createSession(Map<String, String> connectionDetails) {
        return new HibernateSession(this, sessionFactory);
    }

    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        super.setApplicationContext(applicationContext);

        if (applicationContext != null) {
            // support for callbacks in domain classes
            eventTriggeringInterceptor = new EventTriggeringInterceptor(this, config);
            ((ConfigurableApplicationContext)applicationContext).addApplicationListener(eventTriggeringInterceptor);
        }
    }

    @Override
    public void setGrailsApplication(GrailsApplication grailsApplication) {
        this.grailsApplication = grailsApplication;
    }

    public GrailsApplication getGrailsApplication() {
        if(grailsApplication == null) {
            Map<String, GrailsApplication> grailsApplicationBeans = getApplicationContext().getBeansOfType(GrailsApplication.class);
            if(!grailsApplicationBeans.isEmpty()) {
                grailsApplication = grailsApplicationBeans.values().iterator().next();
            }
        }
        return grailsApplication;
    }

    public int getDefaultFlushMode() {
        return defaultFlushMode;
    }
}
