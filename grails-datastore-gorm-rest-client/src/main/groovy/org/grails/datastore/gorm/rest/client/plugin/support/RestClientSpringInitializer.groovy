package org.grails.datastore.gorm.rest.client.plugin.support

import grails.core.GrailsApplication
import groovy.transform.CompileStatic
import org.grails.datastore.gorm.bootstrap.AbstractDatastoreInitializer
import org.grails.spring.beans.factory.InstanceFactoryBean
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.context.ApplicationContext
import org.springframework.context.support.GenericApplicationContext
import org.grails.datastore.gorm.rest.client.RestClientGormEnhancer

/**
 * Created by hjeong on 10/28/15.
 */
class RestClientSpringInitializer extends AbstractDatastoreInitializer{


    protected Closure defaultMapping

    public static final String SETTING_DEFAULT_MAPPING = 'grails.rest.default.mapping'
    public static final String SETTING_REST_CONNECTION = 'grails.gorm.restClient'

    RestClientSpringInitializer() {
    }

    RestClientSpringInitializer(ClassLoader classLoader, String... packages) {
        super(classLoader, packages)
    }

    RestClientSpringInitializer(String... packages) {
        super(packages)
    }

    RestClientSpringInitializer(Collection<Class> persistentClasses) {
        super(persistentClasses)
    }

    RestClientSpringInitializer(Class... persistentClasses) {
        super(persistentClasses)
    }

    RestClientSpringInitializer(Map configuration, Collection<Class> persistentClasses) {
        super(configuration, persistentClasses)
    }

    RestClientSpringInitializer(Map configuration, Class... persistentClasses) {
        super(configuration, persistentClasses)
    }

    @CompileStatic
    ApplicationContext configure() {
        ExpandoMetaClass.enableGlobally()
        GenericApplicationContext applicationContext = new GenericApplicationContext()
        configureForBeanDefinitionRegistry(applicationContext)
        applicationContext.refresh()
        return applicationContext
    }

    @Override
    Closure getBeanDefinitions(BeanDefinitionRegistry beanDefinitionRegistry) {
        return {
            final config = configurationObject

            Closure defaultMapping = config.getProperty(SETTING_DEFAULT_MAPPING,Closure, this.defaultMapping)
            def restClientConfig = config.getProperty(SETTING_REST_CONNECTION, Map)

            def callable = getCommonConfiguration(beanDefinitionRegistry)
            callable.delegate = delegate
            callable.call()

            def grailsApplication =ref(GrailsApplication.APPLICATION_ID)

            restclientMappingContext(RestClientMappingContextFactoryBean) {
                grailsApplication =ref(GrailsApplication.APPLICATION_ID)
                if (defaultMapping) {
                    delegate.defaultMapping = new DefaultMappingHolder(defaultMapping)
                }
            }

            restclientDatastore(RestClientDatastoreFactoryBean) {
                mappingContext = restclientMappingContext
                connectionDetails = restClientConfig
            }

            callable = getAdditionalBeansConfiguration(beanDefinitionRegistry, "restclient")
            callable.delegate = delegate
            callable.call()

            "org.grails.gorm.rest.internal.GORM_ENHANCER_BEAN-restclient"(RestClientGormEnhancer, ref("restclientDatastore"), ref("restclientTransactionManager")) { bean ->
                bean.initMethod = 'enhance'
                bean.lazyInit = false
                includeExternal = !secondaryDatastore
            }
        }
    }


    void setDefaultMapping(Closure defaultMapping) {
        this.defaultMapping = defaultMapping
    }
}
