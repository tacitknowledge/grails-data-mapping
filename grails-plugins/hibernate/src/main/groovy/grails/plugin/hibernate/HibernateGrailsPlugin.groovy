package grails.plugin.hibernate

import grails.config.Config
import grails.core.GrailsApplication
import grails.core.GrailsClass
import grails.core.GrailsDomainClass
import grails.orm.bootstrap.HibernateDatastoreSpringInitializer
import grails.plugins.Plugin
import grails.validation.ConstrainedProperty
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import org.grails.core.artefact.DomainClassArtefactHandler
import org.grails.datastore.gorm.config.GrailsDomainClassMappingContext
import org.grails.orm.hibernate.SessionFactoryHolder
import org.grails.orm.hibernate.cfg.GrailsDomainBinder
import org.grails.orm.hibernate.cfg.GrailsHibernateUtil
import org.grails.orm.hibernate.cfg.Mapping
import org.grails.orm.hibernate.support.AbstractMultipleDataSourceAggregatePersistenceContextInterceptor
import org.grails.orm.hibernate.validation.HibernateDomainClassValidator
import org.grails.orm.hibernate.validation.UniqueConstraint
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.context.ApplicationContext
import org.springframework.validation.Validator
/**
 * Plugin that integrates Hibernate into a Grails application
 *
 * @author Graeme Rocher
 * @since 3.0
 */
@CompileStatic
class HibernateGrailsPlugin extends Plugin {

    public static final String DEFAULT_DATA_SOURCE_NAME = HibernateDatastoreSpringInitializer.DEFAULT_DATA_SOURCE_NAME

    def grailsVersion = '3.0.0 > *'

    def author = 'Grails Core Team'
    def title = 'Hibernate 4 for Grails'
    def description = 'Provides integration between Grails and Hibernate 4 through GORM'
    def documentation = 'http://grails.github.io/grails-data-mapping/latest/'

    def observe = ['domainClass']
    def loadAfter = ['controllers', 'domainClass']
    def watchedResources = ['file:./grails-app/conf/hibernate/**.xml']
    def pluginExcludes = ['src/templates/**']

    def license = 'APACHE'
    def organization = [name: 'Grails', url: 'http://grails.org']
    def issueManagement = [system: 'JIRA', url: 'https://github.com/grails/grails-data-mapping/issues']
    def scm = [url: 'https://github.com/grails/grails-data-mapping']

    Set<String> dataSourceNames

    Closure doWithSpring() {{->
        GrailsApplication grailsApplication = grailsApplication
        Config config = grailsApplication.config
        dataSourceNames = AbstractMultipleDataSourceAggregatePersistenceContextInterceptor.calculateDataSourceNames(grailsApplication)


        def springInitializer = new HibernateDatastoreSpringInitializer(config, grailsApplication.getArtefacts(DomainClassArtefactHandler.TYPE).collect() { GrailsClass cls -> cls.clazz })
        springInitializer.registerApplicationIfNotPresent = false
        springInitializer.dataSources = dataSourceNames
        def beans = springInitializer.getBeanDefinitions((BeanDefinitionRegistry)applicationContext)

        beans.delegate = delegate
        beans.call()
    }}

    @Override
    void onShutdown(Map<String, Object> event) {
        ConstrainedProperty.removeConstraint(UniqueConstraint.UNIQUE_CONSTRAINT, UniqueConstraint)
    }

    @Override
    void onChange(Map<String, Object> event) {

        if(event.source instanceof Class) {
            Class cls = (Class)event.source
            GrailsDomainClass dc = (GrailsDomainClass)grailsApplication.getArtefact(DomainClassArtefactHandler.TYPE, cls.name)

            if(!dc || !GrailsHibernateUtil.isMappedWithHibernate(dc)) {
                return
            }

            GrailsDomainBinder.clearMappingCache(cls)

            ApplicationContext applicationContext = applicationContext
            applicationContext.getBean(GrailsDomainClassMappingContext).addPersistentEntity(cls, true)
            for(String dataSourceName in dataSourceNames) {
                boolean isDefault = dataSourceName == Mapping.DEFAULT_DATA_SOURCE
                String suffix = isDefault ? '' : '_' + dataSourceName
                String sessionFactoryName = isDefault ? HibernateDatastoreSpringInitializer.SESSION_FACTORY_BEAN_NAME : "sessionFactory$suffix"


                if(applicationContext instanceof BeanDefinitionRegistry) {
                    BeanDefinitionRegistry beanDefinitionRegistry = (BeanDefinitionRegistry) applicationContext

                    def holder = applicationContext.getBean("${SessionFactoryHolder.BEAN_ID}${suffix}", SessionFactoryHolder)
                    holder.sessionFactory.close()

                    def sessionFactoryBeanDefinition = beanDefinitionRegistry.getBeanDefinition(sessionFactoryName)
                    sessionFactoryBeanDefinition.propertyValues.add("proxyIfReloadEnabled", false)


                    def newHolder = new RootBeanDefinition(SessionFactoryHolder)
                    newHolder.propertyValues.add("sessionFactory", sessionFactoryBeanDefinition)

                    def reloadedHolderBeanName = "${SessionFactoryHolder.BEAN_ID}${suffix}-reloaded"
                    beanDefinitionRegistry.registerBeanDefinition(reloadedHolderBeanName,
                            newHolder
                    )

                    def newSessionFactory = applicationContext.getBean(reloadedHolderBeanName, SessionFactoryHolder).getSessionFactory()

                    holder.setSessionFactory(
                            newSessionFactory
                    )

                    registerNewBeans(dc, suffix, sessionFactoryName)

                    def validatorName = "${cls.name}Validator$suffix"

                    def validator = applicationContext.getBean(validatorName, Validator)
                    dc.validator = validator
                }
            }

            def postInit = new HibernateDatastoreSpringInitializer.PostInitializationHandling()
            postInit.applicationContext = applicationContext
            postInit.grailsApplication = grailsApplication
            postInit.afterPropertiesSet()
        }
    }

    @CompileDynamic
    protected registerNewBeans(GrailsDomainClass cls, String suffix, String sessionFactoryName) {
        beans {
            "${cls.fullName}Validator$suffix"(HibernateDomainClassValidator) {
                messageSource = ref("messageSource")
                domainClass = ref("${cls.fullName}DomainClass")
                delegate.grailsApplication = ref(GrailsApplication.APPLICATION_ID)
                sessionFactory = ref(sessionFactoryName)
            }
        }
    }
}
