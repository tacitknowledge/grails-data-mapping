/*
 * Copyright 2013 SpringSource.
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
package grails.plugin.hibernate4

import grails.core.GrailsApplication
import grails.core.GrailsDomainClass
import grails.core.GrailsDomainClassProperty
import grails.util.Environment
import grails.validation.ConstrainedProperty
import grails.validation.ConstraintsEvaluator
import org.grails.orm.hibernate.*
import org.grails.orm.hibernate.cfg.GrailsDomainBinder
import org.grails.orm.hibernate.cfg.GrailsHibernateUtil
import org.grails.orm.hibernate.cfg.HibernateUtils
import org.grails.orm.hibernate.cfg.Mapping
import org.grails.orm.hibernate.proxy.HibernateProxyHandler
import org.grails.orm.hibernate.support.*
import org.grails.orm.hibernate.validation.HibernateConstraintsEvaluator
import org.grails.orm.hibernate.validation.HibernateDomainClassValidator
import org.grails.orm.hibernate.validation.PersistentConstraintFactory
import org.grails.orm.hibernate.validation.UniqueConstraint
import org.grails.datastore.mapping.model.MappingContext
import org.grails.spring.DefaultRuntimeSpringConfiguration
import org.grails.spring.RuntimeSpringConfiguration
import org.grails.web.servlet.context.support.GrailsRuntimeConfigurator
import org.hibernate.EmptyInterceptor
import org.hibernate.cfg.ImprovedNamingStrategy
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.config.PropertiesFactoryBean
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.support.nativejdbc.CommonsDbcpNativeJdbcExtractor

/**
 * Used by HibernateGrailsPlugin to implement the core parts of GORM.
 *
 * @author Graeme Rocher
 * @since 1.1
 */
class HibernatePluginSupport {

    static final Logger LOG = LoggerFactory.getLogger(this)
    static final int RELOAD_RETRY_LIMIT = 3

    static GrailsDomainBinder grailsDomainBinder = new GrailsDomainBinder()

    static doWithSpring = {

        if (getSpringConfig().containsBean(ConstraintsEvaluator.BEAN_NAME)) {
            delegate."${ConstraintsEvaluator.BEAN_NAME}".constraintsEvaluatorClass = HibernateConstraintsEvaluator
        }

        def vendorToDialect = new Properties()
        def hibernateDialects = application.classLoader.getResource("hibernate-dialects.properties")
        if (hibernateDialects) {
            def p = new Properties()
            p.load(hibernateDialects.openStream())
            for (entry in p) {
                vendorToDialect[entry.value] = "org.hibernate.dialect.${entry.key}".toString()
            }
        }

        def datasourceNames = []
        if (getSpringConfig().containsBean('dataSource')) {
            datasourceNames << Mapping.DEFAULT_DATA_SOURCE
        }

        for (name in application.config.keySet()) {
            if (name.startsWith('dataSource_')) {
                datasourceNames << name - 'dataSource_'
            }
        }

        ConstrainedProperty.registerNewConstraint(UniqueConstraint.UNIQUE_CONSTRAINT,
                new PersistentConstraintFactory(getSpringConfig().getUnrefreshedApplicationContext(),
                        UniqueConstraint))

        proxyHandler(HibernateProxyHandler)

        eventTriggeringInterceptor(ClosureEventTriggeringInterceptor)

        nativeJdbcExtractor(CommonsDbcpNativeJdbcExtractor)

        hibernateEventListeners(HibernateEventListeners)

        persistenceInterceptor(AggregatePersistenceContextInterceptor)

        for (String datasourceName in datasourceNames) {
            LOG.debug "processing DataSource $datasourceName"
            boolean isDefault = datasourceName == Mapping.DEFAULT_DATA_SOURCE
            String suffix = isDefault ? '' : '_' + datasourceName
            String prefix = isDefault ? '' : datasourceName + '_'

            for (GrailsDomainClass dc in application.domainClasses) {

                if (!dc.abstract && GrailsHibernateUtil.isMappedWithHibernate(dc) && GrailsHibernateUtil.usesDatasource(dc, datasourceName)) {
                    "${dc.fullName}Validator$suffix"(HibernateDomainClassValidator) {
                        messageSource = ref("messageSource")
                        domainClass = ref("${dc.fullName}DomainClass")
                        grailsApplication = ref("grailsApplication", true)
                        sessionFactory = ref("sessionFactory$suffix")
                    }
                }
            }

            def ds = application.config["dataSource$suffix"]
            if (isDefault) {
                BeanDefinition externalDefinition = checkExternalBeans(application)
                if (externalDefinition && !ds) {
                    ds = new ConfigObject()
                    application.config.dataSource = ds
                }
            }

            def hibConfig = application.config["hibernate$suffix"] ?: application.config.hibernate

            def hibConfigClass = ds?.configClass
            def hibProps = [:]

            if (ds.loggingSql || ds.logSql) {
                hibProps."hibernate.show_sql" = "true"
            }
            if (ds.formatSql) {
                hibProps."hibernate.format_sql" = "true"
            }

            if (ds.dialect) {
                if (ds.dialect instanceof Class) {
                    hibProps."hibernate.dialect" = ds.dialect.name
                }
                else {
                    hibProps."hibernate.dialect" = ds.dialect.toString()
                }
            }
            else {
                "dialectDetector$suffix"(HibernateDialectDetectorFactoryBean) {
                    dataSource = ref("dataSource$suffix")
                    vendorNameDialectMappings = vendorToDialect
                }
                hibProps."hibernate.dialect" = ref("dialectDetector$suffix")
            }

            hibProps."hibernate.hbm2ddl.auto" = ds.dbCreate ?: ''

            LOG.info "Set db generation strategy to '${hibProps.'hibernate.hbm2ddl.auto'}' for datasource $datasourceName"

            if (hibConfig) {
                def cacheProvider = hibConfig.cache?.provider_class
                if (cacheProvider) {
                    if (cacheProvider.contains('OSCacheProvider')) {
                        try {
                            def cacheClass = getClass().classLoader.loadClass(cacheProvider)
                        }
                        catch (Throwable t) {
                            hibConfig.cache.region.factory_class = 'org.hibernate.cache.ehcache.EhCacheRegionFactory'
                            log.error """WARNING: Your cache provider is set to '${cacheProvider}' in DataSource.groovy, however the class for this provider cannot be found.
Using Grails' default cache region factory: 'org.hibernate.cache.ehcache.EhCacheRegionFactory'""", t
                        }
                    }
                    else if (!(hibConfig.cache.useCacheProvider) && (cacheProvider == 'org.hibernate.cache.EhCacheProvider' || cacheProvider == 'net.sf.ehcache.hibernate.EhCacheProvider')) {
                        hibConfig.cache.region.factory_class = 'org.hibernate.cache.ehcache.EhCacheRegionFactory'
                        hibConfig.cache.remove('provider_class')
                        if (hibConfig.cache.provider_configuration_file_resource_path) {
                            hibProps.'net.sf.ehcache.configurationResourceName' = hibConfig.cache.provider_configuration_file_resource_path
                            hibConfig.cache.remove('provider_configuration_file_resource_path')
                        }
                    }
                }

                cacheProvider = hibConfig.cache?.region?.factory_class
                boolean problem = false
                if (cacheProvider == 'net.sf.ehcache.hibernate.EhCacheRegionFactory') {
                    problem = true
                    log.error """WARNING: Your cache provider is set to '${cacheProvider}' in DataSource.groovy, however the class for this provider cannot be found.
Using Grails' default cache region factory: 'org.hibernate.cache.ehcache.EhCacheRegionFactory'"""
                }
                else if (cacheProvider) {
                    try {
                        def cacheClass = getClass().classLoader.loadClass(cacheProvider)
                    }
                    catch (Throwable t) {
                        problem = true
                        log.error """WARNING: Your cache provider is set to '${cacheProvider}' in DataSource.groovy, however the class for this provider cannot be found.
Using Grails' default cache region factory: 'org.hibernate.cache.ehcache.EhCacheRegionFactory'""", t
                    }
                }
                if (problem) {
                    hibConfig.cache.region.factory_class = 'org.hibernate.cache.ehcache.EhCacheRegionFactory'
                }

                def namingStrategy = hibConfig.naming_strategy ?: ImprovedNamingStrategy
                try {
                    grailsDomainBinder.configureNamingStrategy datasourceName, namingStrategy
                }
                catch (Throwable t) {
                    log.error """WARNING: You've configured a custom Hibernate naming strategy '$namingStrategy' in DataSource.groovy, however the class cannot be found.
Using Grails' default naming strategy: '${ImprovedNamingStrategy.name}'""",t
                    grailsDomainBinder.configureNamingStrategy datasourceName, ImprovedNamingStrategy
                }

                // allow adding hibernate properties that don't start with "hibernate."
                if (hibConfig.get('properties') instanceof ConfigObject) {
                    def hibernateProperties = hibConfig.remove('properties')
                    hibProps.putAll(hibernateProperties.flatten().toProperties())
                }

                hibProps.putAll(hibConfig.flatten().toProperties('hibernate'))
                hibProps.remove('hibernate.reload')
                hibProps.remove('hibernate.singleSession')

                // move net.sf.ehcache.configurationResourceName to "top level"	if it exists
                if (hibProps.'hibernate.net.sf.ehcache.configurationResourceName') {
                    hibProps.'net.sf.ehcache.configurationResourceName' = hibProps.remove('hibernate.net.sf.ehcache.configurationResourceName')
                }
            }

            "hibernateProperties$suffix"(PropertiesFactoryBean) { bean ->
                bean.scope = "prototype"
                properties = hibProps
            }

            "entityInterceptor$suffix"(EmptyInterceptor)

            "abstractSessionFactoryBeanConfig$suffix" {
                dataSource = ref("dataSource$suffix")
                dataSourceName = datasourceName
                sessionFactoryBeanName = "sessionFactory$suffix"

                List hibConfigLocations = []
                if (application.classLoader.getResource(prefix + 'hibernate.cfg.xml')) {
                    hibConfigLocations << 'classpath:' + prefix + 'hibernate.cfg.xml'
                }
                def explicitLocations = hibConfig?.config?.location
                if (explicitLocations) {
                    if (explicitLocations instanceof Collection) {
                        hibConfigLocations.addAll(explicitLocations.collect { it.toString() })
                    }
                    else {
                        hibConfigLocations << hibConfig.config.location.toString()
                    }
                }
                configLocations = hibConfigLocations

                if (hibConfigClass) {
                    configClass = ds.configClass
                }

                hibernateProperties = ref("hibernateProperties$suffix")

                grailsApplication = ref("grailsApplication")

                entityInterceptor = ref("entityInterceptor$suffix")

                eventListeners = [
                        'save':        eventTriggeringInterceptor,
                        'save-update': eventTriggeringInterceptor,
                        'pre-load':    eventTriggeringInterceptor,
                        'post-load':   eventTriggeringInterceptor,
                        'pre-insert':  eventTriggeringInterceptor,
                        'post-insert': eventTriggeringInterceptor,
                        'pre-update':  eventTriggeringInterceptor,
                        'post-update': eventTriggeringInterceptor,
                        'pre-delete':  eventTriggeringInterceptor,
                        'post-delete': eventTriggeringInterceptor]

                hibernateEventListeners = ref('hibernateEventListeners')
            }

            if (grails.util.Environment.current.isReloadEnabled()) {
                "${SessionFactoryHolder.BEAN_ID}$suffix"(SessionFactoryHolder)
            }
            "sessionFactory$suffix"(ConfigurableLocalSessionFactoryBean) { bean ->
                bean.parent = 'abstractSessionFactoryBeanConfig' + suffix
            }

            "transactionManager$suffix"(GrailsHibernateTransactionManager) {
                sessionFactory = ref("sessionFactory$suffix")
                dataSource = ref("dataSource$suffix")
            }
            
            int defaultFlushMode = resolveDefaultFlushMode(hibConfig, ds.readOnly)

            "hibernateDatastore$suffix"(HibernateDatastore, ref('grailsDomainClassMappingContext'), ref("sessionFactory$suffix"), application.config, null, defaultFlushMode)

            if (manager?.hasGrailsPlugin("controllers")) {
                "flushingRedirectEventListener$suffix"(FlushOnRedirectEventListener, ref("sessionFactory$suffix"))

                "openSessionInViewInterceptor$suffix"(GrailsOpenSessionInViewInterceptor) {

                    flushMode = defaultFlushMode

                    sessionFactory = ref("sessionFactory$suffix")

                    if(hibConfig?.containsKey('singleSession')) {
                        if(!hibConfig.singleSession) {
                            // Spring's hibernate4 OSIV doesn't have singleSession property like the hibernate3 specific version
                            def errorMessage = "hibernate4 plugin doesn't have support for singleSession=false OSIV mode. use hibernate plugin instead or set singleSession=true."
                            LOG.error errorMessage
                            throw new IllegalArgumentException(errorMessage)
                        }
                    }
                }

                if (getSpringConfig().containsBean("controllerHandlerMappings")) {
                    controllerHandlerMappings.interceptors << ref("openSessionInViewInterceptor$suffix")
                }
                if (getSpringConfig().containsBean("annotationHandlerMapping")) {
                    if (annotationHandlerMapping.interceptors) {
                        annotationHandlerMapping.interceptors << ref("openSessionInViewInterceptor$suffix")
                    }
                    else {
                        annotationHandlerMapping.interceptors = [ref("openSessionInViewInterceptor$suffix")]
                    }
                }
            }
        }
    }

    private static int resolveDefaultFlushMode(hibConfig, readOnly) {
        int flushMode
        if (Boolean.TRUE.equals(readOnly)) {
            flushMode = GrailsHibernateTemplate.FLUSH_NEVER
        }
        else if (hibConfig.flush.mode instanceof CharSequence) {
            switch(hibConfig.flush.mode.toString().toLowerCase()) {
                case "manual": 
                case "never":
                    flushMode = GrailsHibernateTemplate.FLUSH_NEVER  
                    break
                case "always": 
                    flushMode = GrailsHibernateTemplate.FLUSH_ALWAYS
                    break
                case "commit": 
                    flushMode = GrailsHibernateTemplate.FLUSH_COMMIT
                    break
                default:
                    flushMode = GrailsHibernateTemplate.FLUSH_AUTO
            }
        }
        else {
            flushMode = GrailsHibernateTemplate.FLUSH_AUTO
        }
        return flushMode
    }

    static final onChange = { event ->
        LOG.debug "onChange() started"

        def allDatasourceNames = [Mapping.DEFAULT_DATA_SOURCE] as Set
        for (name in application.config.keySet()) {
            if (name.startsWith('dataSource_')) {
                allDatasourceNames << name - 'dataSource_'
            }
        }

        def datasourceNames
        if (event.source instanceof Class) {
            GrailsDomainClass dc = application.getDomainClass(event.source.name)
            if (!dc || !GrailsHibernateUtil.isMappedWithHibernate(dc)) {
                return
            }
            grailsDomainBinder.clearMappingCache(event.source)
            def dcMappingDsNames = GrailsHibernateUtil.getDatasourceNames(dc) as Set
            datasourceNames = [] as Set
            for(name in allDatasourceNames) {
                if (name in dcMappingDsNames || dcMappingDsNames.contains(Mapping.ALL_DATA_SOURCES)) {
                    datasourceNames << name
                }
            }
        } else {
            grailsDomainBinder.clearMappingCache()
            datasourceNames = allDatasourceNames
        }

        def beans = beans {
            for (String datasourceName in datasourceNames) {
                LOG.debug "processing DataSource $datasourceName"
                boolean isDefault = datasourceName == Mapping.DEFAULT_DATA_SOURCE
                String suffix = isDefault ? '' : '_' + datasourceName
                def hibConfig = application.config["hibernate$suffix"]
                def sessionFactoryReload = hibConfig?.containsKey('reload') ? hibConfig.reload : true

                if (sessionFactoryReload && Environment.current.isReloadEnabled()) {
                    "${SessionFactoryHolder.BEAN_ID}$suffix"(SessionFactoryHolder) {
                        sessionFactory = bean(ConfigurableLocalSessionFactoryBean) { bean ->
                            bean.parent = ref("abstractSessionFactoryBeanConfig$suffix")
                            proxyIfReloadEnabled = false
                        }
                    }
                }
                if (event.source instanceof Class) {
                    GrailsDomainClass dc = application.getDomainClass(event.source.name)
                    if (!dc.abstract && GrailsHibernateUtil.usesDatasource(dc, datasourceName)) {
                        "${dc.fullName}Validator$suffix"(HibernateDomainClassValidator) {
                            messageSource = ref("messageSource")
                            domainClass = ref("${dc.fullName}DomainClass")
                            sessionFactory = ref("sessionFactory$suffix")
                            grailsApplication = ref("grailsApplication", true)
                        }
                    }
                }
            }
        }

        ApplicationContext ctx = event.ctx
        beans.registerBeans(ctx)

        if (event.source instanceof Class) {
            def mappingContext = ctx.getBean("grailsDomainClassMappingContext", MappingContext)
            def entity = mappingContext.addPersistentEntity(event.source, true)
        }

        int retryCount = 0

        def enhanceAndTest = {
            // Re-enhance the given class
            HibernateUtils.enhanceSessionFactories(ctx, application, event.source)

            // Due to quantum tunneling and other class loader race conditions, attempts to
            // enhance the entities may not work. Check a few static and non-static methods to see if it worked.
            boolean hasMethods = event.source.metaClass.methods.any { MetaMethod method ->
                method.name.startsWith("addTo") ||
                        method.name.startsWith("list") ||
                        method.name.startsWith("get") ||
                        method.name.startsWith("count")
            }

            if (!hasMethods) {
                if (++retryCount < RELOAD_RETRY_LIMIT) {
                    LOG.debug("Attempt ${retryCount} at enhancing ${event.source.name} failed, waiting and trying again")
                    sleep(retryCount * 1000)
                    enhanceAndTest()
                }
            }
        }

        // Enhance the reloaded GORM objects
        enhanceAndTest()

        LOG.info "onChange() complete"
    }

    static final doWithDynamicMethods = { ApplicationContext ctx ->
        def grailsApplication = application
        HibernateUtils.enhanceSessionFactories(ctx, grailsApplication)
    }

    private static checkExternalBeans(GrailsApplication application) {
        ApplicationContext parent = application.parentContext
        try {
            def resourcesXml = parent?.getResource(GrailsRuntimeConfigurator.SPRING_RESOURCES_XML)
            if (resourcesXml && resourcesXml.exists()) {
                def xmlBeans = new DefaultListableBeanFactory()
                new XmlBeanDefinitionReader(xmlBeans).loadBeanDefinitions(resourcesXml)
                if (xmlBeans.containsBean("dataSource")) {
                    LOG.info("Using dataSource bean definition from ${GrailsRuntimeConfigurator.SPRING_RESOURCES_XML}")
                    return xmlBeans.getMergedBeanDefinition("dataSource")
                }
            }
        } catch (FileNotFoundException fnfe) {
            // that's ok external resources file not required
        }

        // Check resources.groovy
        RuntimeSpringConfiguration springConfig = new DefaultRuntimeSpringConfiguration(parent,application.classLoader)
        GrailsRuntimeConfigurator.loadExternalSpringConfig(springConfig, application)
        if (springConfig.containsBean("dataSource")) {
            LOG.info("Using dataSource bean definition from ${GrailsRuntimeConfigurator.SPRING_RESOURCES_GROOVY}")
            return springConfig.getBeanDefinition("dataSource")
        }
        return null
    }
}
