package org.grails.datastore.gorm.neo4j

import grails.core.GrailsClass
import grails.neo4j.bootstrap.Neo4jDataStoreSpringInitializer
import grails.plugins.Plugin
import groovy.transform.CompileStatic
import org.grails.core.artefact.DomainClassArtefactHandler
import org.springframework.beans.factory.support.BeanDefinitionRegistry

class Neo4jGrailsPlugin extends Plugin {

    def license = "Apache 2.0 License"
    def organization = [ name: "Stefan Armbruster", url: "http://blog.armbruster-it.de/" ]
    def developers = [
        [ name: "Stefan Armbruster", email: "stefan@armbruster-it.de" ] ]
    def issueManagement = [ system: "JIRA", url: "https://github.com/grails/grails-data-mapping/issues" ]
    def scm = [ url: "https://github.com/grails/grails-data-mapping" ]

    def grailsVersion = "3.0.0 > *"
    def loadAfter = ['domainClass', 'hibernate', 'services', 'converters']
    //def loadBefore = ['dataSource']
    def observe = ['services', 'domainClass']
        
    def author = "Stefan Armbruster"
    def authorEmail = "stefan@armbruster-it.de"
    def title = "Neo4j GORM"    
    def description = 'A plugin that integrates the Neo4j graph database into Grails, providing a GORM API onto it'

    def documentation = "http://grails.github.io/grails-data-mapping/latest/neo4j"

    def dependsOn = [:]
    // resources that are excluded from plugin packaging

    def pluginExcludes = [
            "grails-app/views/error.gsp"
    ]

    @Override
    @CompileStatic
    Closure doWithSpring() {
        def initializer = new Neo4jDataStoreSpringInitializer(config, grailsApplication.getArtefacts(DomainClassArtefactHandler.TYPE).collect() { GrailsClass cls -> cls.clazz })
        initializer.registerApplicationIfNotPresent = false
        initializer.setSecondaryDatastore( manager.hasGrailsPlugin("hibernate")  )
        return initializer.getBeanDefinitions((BeanDefinitionRegistry)applicationContext)
    }

}
