/*
 * Copyright 2015 original authors
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
package org.grails.compiler.gorm

import grails.gorm.annotation.Entity
import groovy.transform.CompilationUnitAware
import groovy.transform.CompileStatic
import groovy.transform.Memoized
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.GenericsType
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.BooleanExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MapEntryExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.TernaryExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.ReturnStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.transform.ASTTransformation
import org.codehaus.groovy.transform.GroovyASTTransformation
import org.grails.datastore.gorm.GormEntity
import org.grails.datastore.gorm.query.GormQueryOperations
import org.grails.datastore.mapping.model.config.GormProperties
import org.grails.datastore.mapping.reflect.AstUtils
import org.grails.datastore.mapping.reflect.NameUtils

import java.lang.annotation.Annotation
import java.lang.reflect.Modifier


/**
 * An AST transformation that adds the following features:<br><br>
 *
 * - An id and version (if not already present)<br>
 * - A toString() method (if not already present)
 * - Associations and association methods (addTo*, removeFrom*) etc.<br>
 * - Association id getter methods ('userId' for 'user' association)<br>
 * - Adds the GormEntity and GormValidateable traits<br>
 * - Named query methods<br>
 *
 * @author Graeme Rocher
 * @since 5.0
 */
@CompileStatic
@GroovyASTTransformation(phase = CompilePhase.CANONICALIZATION)
class GormEntityTransformation implements CompilationUnitAware,ASTTransformation {
    private static final ClassNode MY_TYPE = new ClassNode(Entity.class);

    private static final String GET_NAMED_QUERY = "getNamedQuery"
    private static ClassNode GORM_ENTITY_CLASS_NODE = ClassHelper.make(GormEntity)
    private static MethodNode ADD_TO_METHOD_NODE =  GORM_ENTITY_CLASS_NODE.getMethods("addTo").get(0)
    private static MethodNode REMOVE_FROM_METHOD_NODE =  GORM_ENTITY_CLASS_NODE.getMethods("removeFrom").get(0)
    private static MethodNode GET_ASSOCIATION_ID_METHOD_NODE =  GORM_ENTITY_CLASS_NODE.getMethods("getAssociationId").get(0)
    public static final Parameter[] ADD_TO_PARAMETERS = [new Parameter(AstUtils.OBJECT_CLASS_NODE, "obj")] as Parameter[]
    public static final ClassNode SERIALIZABLE_CLASS_NODE = ClassHelper.make(Serializable).getPlainNodeReference()

    protected CompilationUnit compilationUnit

    void setCompilationUnit(CompilationUnit compilationUnit) {
        this.compilationUnit = compilationUnit
    }

    @Override
    void visit(ASTNode[] astNodes, SourceUnit sourceUnit) {
        AnnotatedNode parent = (AnnotatedNode) astNodes[1];
        AnnotationNode node = (AnnotationNode) astNodes[0];

        if (!(astNodes[0] instanceof AnnotationNode) || !(astNodes[1] instanceof AnnotatedNode)) {
            throw new RuntimeException("Internal error: wrong types: ${node.getClass()} / ${parent.getClass()}");
        }

        if (!MY_TYPE.equals(node.getClassNode()) || !(parent instanceof ClassNode)) {
            return;
        }

        ClassNode cNode = (ClassNode) parent;

        visit(cNode, sourceUnit)
    }

    void visit(ClassNode classNode, SourceUnit sourceUnit) {
        if(AstUtils.getKnownEntityNames().contains(classNode.name)) {
            return
        }

        AstUtils.addTransformedEntityName(classNode.name)
        // Add the entity annotation and enable generic replacement
        classNode.setUsingGenerics(true);
        AstUtils.addAnnotationIfNecessary(classNode, Entity.class);

        try {
            def cl = Thread.currentThread().contextClassLoader
            AstUtils.addAnnotationIfNecessary(classNode, (Class<? extends Annotation>)Class.forName('grails.persistence.Entity', true, cl))
        } catch (Throwable e) {
            // Only GORM classes on the classpath continue
        }

        // first apply dirty checking behavior
        def dirtyCheckTransformer = new DirtyCheckingTransformer()
        dirtyCheckTransformer.performInjectionOnAnnotatedClass(sourceUnit, classNode)

        // now enhance with id and version
        injectIdProperty(classNode)
        injectVersionProperty(classNode)

        // inject toString()
        injectToStringMethod(classNode)

        // inject associations
        injectAssociations(classNode)

        // inject the GORM entity trait
        def classGormEntityTrait = pickGormEntityTrait(classNode, sourceUnit)
        AstUtils.injectTrait(classNode, classGormEntityTrait)

        // convert the methodMissing and propertyMissing implementations to $static_methodMissing and $static_propertyMissing for the static versions
        def methodMissingBody = new BlockStatement()


        def methodNameParam = new Parameter(ClassHelper.make(String), "name")
        def methodArgsParam = new Parameter(AstUtils.OBJECT_CLASS_NODE, "args")
        def methodMissingArgs = new ArgumentListExpression(methodNameParam, methodArgsParam)
        def methodMissingMethodCall = new MethodCallExpression(new VariableExpression("this"), "staticMethodMissing", methodMissingArgs)
        methodMissingBody.addStatement(
                new ExpressionStatement(methodMissingMethodCall)
        )

        def methodMissingParameters = [methodNameParam, methodArgsParam] as Parameter[]
        classNode.addMethod('$static_methodMissing', Modifier.PUBLIC | Modifier.STATIC, AstUtils.OBJECT_CLASS_NODE, methodMissingParameters, null, methodMissingBody)


        // now process named query associations
        // see https://grails.github.io/grails-doc/latest/ref/Domain%20Classes/namedQueries.html

        // for each method call create a named query proxy lookup
        def thisClassNode = classNode
        def namedQueriesProp = thisClassNode.getProperty(GormProperties.NAMED_QUERIES)
        def currentClassNode = classNode
        while(namedQueriesProp != null) {
            def expression = namedQueriesProp.getInitialExpression()
            if(expression instanceof ClosureExpression) {
                ClosureExpression ce = (ClosureExpression)expression
                def statement = ce.code
                if(statement instanceof BlockStatement) {
                    BlockStatement body = (BlockStatement)statement
                    def allStatements = body.statements
                    for(s in allStatements) {
                        if(s instanceof ExpressionStatement) {
                            ExpressionStatement es = (ExpressionStatement)s
                            if(es.expression instanceof MethodCallExpression) {
                                MethodCallExpression mce = (MethodCallExpression) es.expression
                                def methodName = mce.getMethodAsString()


                                def namedQueryGetter = NameUtils.getGetterName(methodName)
                                def existing = thisClassNode.getMethod(namedQueryGetter, AstUtils.ZERO_PARAMETERS)

                                if(existing == null || !existing.getDeclaringClass().equals(thisClassNode)) {
                                    def queryOperationsClassNode = AstUtils.nonGeneric( ClassHelper.make(GormQueryOperations) )
                                    final GenericsType[] genericsTypes = queryOperationsClassNode.getGenericsTypes()
                                    final Map<String, ClassNode> parameterNameToParameterValue = new LinkedHashMap<String, ClassNode>()
                                    if(genericsTypes != null) {
                                        for(GenericsType gt : genericsTypes) {
                                            parameterNameToParameterValue.put(gt.getName(), thisClassNode.getPlainNodeReference());
                                        }
                                    }
                                    AstUtils.replaceGenericsPlaceholders(queryOperationsClassNode, parameterNameToParameterValue)

                                    def methodBody = new BlockStatement()

                                    def getNamedQueryMethodCall = new MethodCallExpression(new VariableExpression("this"), GET_NAMED_QUERY, new ArgumentListExpression(new ConstantExpression(methodName)))
                                    methodBody.addStatement(new ReturnStatement(getNamedQueryMethodCall))
                                    thisClassNode.addMethod( new MethodNode(namedQueryGetter, Modifier.PUBLIC | Modifier.STATIC, queryOperationsClassNode, AstUtils.ZERO_PARAMETERS, null, methodBody))
                                }

                            }
                        }
                    }
                }
                currentClassNode = currentClassNode.getSuperClass()
                namedQueriesProp = currentClassNode.getProperty(GormProperties.NAMED_QUERIES)
            }

        }


        if(compilationUnit != null) {
            org.codehaus.groovy.transform.trait.TraitComposer.doExtendTraits(classNode, sourceUnit, compilationUnit);
        }

    }

    protected Class pickGormEntityTrait(ClassNode classNode, SourceUnit source) {
        def classLoader = getClass().classLoader


        // first try the `mapWithValue`
        def mapWith = classNode.getProperty(GormProperties.MAPPING_STRATEGY)
        String mapWithValue = mapWith?.initialExpression?.text
        Class gormEntityTrait = null
        if(mapWithValue) {
            gormEntityTrait = findGormEntityTraitForValue(mapWithValue, classLoader)
        }

        if(gormEntityTrait == null) {
            boolean isHibernatePresent = isHibernatePresent(classLoader)
            if(isHibernatePresent) {
                gormEntityTrait = GormEntity
            }
            else {
                def traitProviderInterface = GormEntityTraitProvider
                List<GormEntityTraitProvider> allTraitProviders = findTraitProviders(traitProviderInterface, classLoader)
                if(allTraitProviders.isEmpty()) {
                    gormEntityTrait = GormEntity
                }
                else if(allTraitProviders.size() > 1) {
                    AstUtils.warning(source, classNode, "There are multiple GORM implementations on the classpath. GORM cannot choose automatically which implementation to use. Please use 'mapWith' on your entity to avoid this conflict and warning.")
                    gormEntityTrait = GormEntity
                }
                else {
                    gormEntityTrait = allTraitProviders.get(0).entityTrait
                }
            }
        }
        return gormEntityTrait
    }

    @Memoized
    private List<GormEntityTraitProvider> findTraitProviders(Class<GormEntityTraitProvider> traitProviderInterface, ClassLoader classLoader) {
        def traitProviders = ServiceLoader.load(traitProviderInterface, classLoader)
        def allTraitProviders = traitProviders.toList()
        if(allTraitProviders.isEmpty()) {
            traitProviders = ServiceLoader.load(traitProviderInterface, Thread.currentThread().contextClassLoader)
            allTraitProviders = traitProviders.toList()
        }
        return allTraitProviders
    }

    @Memoized
    private Class findGormEntityTraitForValue(String mapWithValue, ClassLoader classLoader) {
        try {
            return Class.forName("grails.gorm.${mapWithValue}.${NameUtils.capitalize(mapWithValue)}Entity", true, classLoader)
        } catch (Throwable e) {
            // ignore
        }
        return null
    }

    @Memoized
    private boolean isHibernatePresent(ClassLoader classLoader) {
        try {
            return Class.forName("org.hibernate.Hibernate", false, classLoader) != null
        } catch (Throwable e) {
            return false
        }
    }

    protected void injectVersionProperty(ClassNode classNode) {
        final boolean hasVersion = AstUtils.hasOrInheritsProperty(classNode, GormProperties.VERSION)

        if (!hasVersion) {
            ClassNode parent = AstUtils.getFurthestUnresolvedParent(classNode);
            parent.addProperty(GormProperties.VERSION, Modifier.PUBLIC, new ClassNode(Long.class), null, null, null);
        }
    }

    protected void injectIdProperty(ClassNode classNode) {
        final boolean hasId = AstUtils.hasOrInheritsProperty(classNode, GormProperties.IDENTITY);

        if (!hasId) {
            // inject into furthest relative
            ClassNode parent = AstUtils.getFurthestUnresolvedParent(classNode);

            parent.addProperty(GormProperties.IDENTITY, Modifier.PUBLIC, new ClassNode(Long.class), null, null, null);
        }
    }

    private void injectAssociations(ClassNode classNode) {

        List<PropertyNode> propertiesToAdd = []
        for (PropertyNode propertyNode in classNode.getProperties()) {
            final String name = propertyNode.name
            final boolean isHasManyProperty = name.equals(GormProperties.HAS_MANY)
            if (isHasManyProperty) {
                Expression e = propertyNode.initialExpression
                propertiesToAdd.addAll(createPropertiesForHasManyExpression(e, classNode))
            }
            final boolean isBelongsToOrHasOne = name.equals(GormProperties.BELONGS_TO) || name.equals(GormProperties.HAS_ONE);
            if (isBelongsToOrHasOne) {
                Expression initialExpression = propertyNode.getInitialExpression()
                if ((!(initialExpression instanceof MapExpression)) &&
                        (!(initialExpression instanceof ClassExpression))) {
                    if (name.equals(GormProperties.HAS_ONE)) {
                        final String message = "WARNING: The hasOne property in class [" + classNode.getName() + "] should have an initial expression of type Map or Class.";
                        System.err.println(message);
                    } else if (!(initialExpression instanceof ListExpression)) {
                        final String message = "WARNING: The belongsTo property in class [" + classNode.getName() + "] should have an initial expression of type List, Map or Class.";
                        System.err.println(message);
                    }
                }
                propertiesToAdd.addAll(createPropertiesForBelongsToOrHasOneExpression(initialExpression, classNode))
            }
        }
        injectAssociationProperties(classNode, propertiesToAdd)


        ListExpression listExpression = getOrCreateListProperty(classNode, GormProperties.TRANSIENT)
        for(PropertyNode pn in classNode.getProperties()) {
            def type = pn.getType()
            if(!Modifier.isPublic(pn.getModifiers()) || Modifier.isStatic(pn.getModifiers())) {
                continue
            }
            if(AstUtils.isDomainClass(type)) {
                addToOneIdProperty(pn.getName(), classNode, listExpression)
            }
            else if(AstUtils.isSubclassOfOrImplementsInterface(type, Iterable.name)) {
                addRelationshipManagementMethods(pn.name, classNode)
            }
        }
    }

    ListExpression getOrCreateListProperty(ClassNode classNode, String name) {
        def transientProperty = classNode.getProperty(name)
        ListExpression listExpression = null
        if(transientProperty != null && Modifier.isStatic(transientProperty.modifiers)) {
            def expression = transientProperty.getInitialExpression()
            if(expression instanceof ListExpression) {
                listExpression = (ListExpression)expression
            }
            else {
                listExpression = new ListExpression()
                listExpression.addExpression(expression)
                transientProperty.getField().setInitialValueExpression(listExpression)
            }
        }
        else if(transientProperty == null) {
            listExpression = new ListExpression()
            classNode.addProperty(GormProperties.TRANSIENT, Modifier.PUBLIC | Modifier.STATIC, AstUtils.OBJECT_CLASS_NODE, listExpression, null, null)
        }

        if(listExpression == null) {
            listExpression = new ListExpression()
        }
        return listExpression
    }

    private Collection<PropertyNode> createPropertiesForBelongsToOrHasOneExpression(Expression e, ClassNode classNode) {
        List<PropertyNode> properties = []
        if (e instanceof MapExpression) {
            MapExpression me = (MapExpression) e;
            for (MapEntryExpression mme in me.mapEntryExpressions) {
                String propertyName = mme.keyExpression.text
                final Expression expression = mme.valueExpression
                ClassNode type
                if (expression instanceof ClassExpression) {
                    type = expression.type
                }
                else {
                    type = ClassHelper.make(expression.text)
                }

                properties.add(new PropertyNode(propertyName, Modifier.PUBLIC, type.getPlainNodeReference(), classNode, null, null, null));
            }
        }
        return properties
    }

    private void addToOneIdProperty(String propertyName, ClassNode classNode, ListExpression listExpression) {
        String idProperty = "get${NameUtils.capitalize(propertyName)}Id"
        String idPropertyName = "${propertyName}Id"
        if (!AstUtils.hasOrInheritsProperty(classNode, idPropertyName)) {
            def methodBody = new BlockStatement()
            listExpression.addExpression(new ConstantExpression(idPropertyName))
            def args = new ArgumentListExpression()
            args.addExpression(new ConstantExpression(propertyName))

            def methodCall = new MethodCallExpression(new VariableExpression("this"), "getAssociationId", args)
            methodCall.setMethodTarget(
                    GET_ASSOCIATION_ID_METHOD_NODE
            )
            methodBody.addStatement(
                    new ExpressionStatement(methodCall)
            )

            def mn = new MethodNode(idProperty, Modifier.PUBLIC, AstUtils.OBJECT_CLASS_NODE, AstUtils.ZERO_PARAMETERS, null, methodBody)
            classNode.addMethod(mn)
        }
    }

    private void injectAssociationProperties(ClassNode classNode, List<PropertyNode> propertiesToAdd) {
        for (PropertyNode pn : propertiesToAdd) {
            if (!AstUtils.hasProperty(classNode, pn.getName())) {
                classNode.addProperty(pn);
            }

        }
    }

    private List<PropertyNode> createPropertiesForHasManyExpression(Expression e, ClassNode classNode) {
        List<PropertyNode> properties = []
        if (e instanceof MapExpression) {
            MapExpression me = (MapExpression) e;
            for (MapEntryExpression mee in me.mapEntryExpressions) {
                String propertyName = mee.keyExpression.text
                addAssociationForKey(propertyName, properties, classNode, findPropertyType(mee.valueExpression))
            }


        }
        return properties;
    }

    private void addRelationshipManagementMethods(String propertyName, ClassNode classNode) {
        def addToMethod = "addTo${NameUtils.capitalize(propertyName)}"
        def existing = classNode.getMethod(addToMethod, ADD_TO_PARAMETERS)
        if (existing == null) {
            def methodBody = new BlockStatement()

            def args = new ArgumentListExpression()
            args.addExpression(new ConstantExpression(propertyName))
            args.addExpression(new VariableExpression("obj"))

            def methodCall = new MethodCallExpression(new VariableExpression("this"), "addTo", args)
            methodCall.setMethodTarget(
                    ADD_TO_METHOD_NODE
            )
            methodBody.addStatement(
                    new ExpressionStatement(methodCall)
            )

            def mn = new MethodNode(addToMethod, Modifier.PUBLIC, classNode.getPlainNodeReference(), ADD_TO_PARAMETERS, null, methodBody)
            classNode.addMethod(mn)
        }

        def removeFromMethod = "removeFrom${NameUtils.capitalize(propertyName)}"
        existing = classNode.getMethod(removeFromMethod, ADD_TO_PARAMETERS)
        if (existing == null) {
            def methodBody = new BlockStatement()

            def args = new ArgumentListExpression()
            args.addExpression(new ConstantExpression(propertyName))
            args.addExpression(new VariableExpression("obj"))

            def methodCall = new MethodCallExpression(new VariableExpression("this"), "removeFrom", args)
            methodCall.setMethodTarget(
                    REMOVE_FROM_METHOD_NODE
            )
            methodBody.addStatement(
                    new ExpressionStatement(methodCall)
            )

            def mn = new MethodNode(removeFromMethod, Modifier.PUBLIC, classNode.getPlainNodeReference(), ADD_TO_PARAMETERS, null, methodBody)
            classNode.addMethod(mn)
        }
    }

    /**
     * Finds the type of the generated property.  The type will be a {@link Set} that is parameterized
     * by the type of the expression passed in.
     * @param expression the expression used to parameterize the {@link Set}.  Only used if a {@link ClassExpression}.  Otherwise ignored.
     * @return A {@link ClassNode} of type {@link Set} that is possibly parameterized by the expression that is passed in.
     */
    private ClassNode findPropertyType(Expression expression) {
        ClassNode setNode = ClassHelper.make(Set.class).getPlainNodeReference()
        if (expression instanceof ClassExpression) {
            setNode.setGenericsTypes([new GenericsType(AstUtils.nonGeneric(expression.type))] as GenericsType[]);
        }
        return setNode
    }

    private void addAssociationForKey(String key, List<PropertyNode> properties, ClassNode declaringType, ClassNode propertyType) {
        properties.add(new PropertyNode(key, Modifier.PUBLIC, propertyType, declaringType, null, null, null));
    }

    private void injectToStringMethod(ClassNode classNode) {
        final boolean hasToString = AstUtils.implementsOrInheritsZeroArgMethod(classNode, "toString")

        if (!hasToString) {
            GStringExpression ge = new GStringExpression(classNode.getName() + ' : ${id != null ? id : \'(unsaved)\'}');
            ge.addString(new ConstantExpression(classNode.getName() + " : "));
            VariableExpression idVariable = new VariableExpression("id");
            ge.addValue(new TernaryExpression(new BooleanExpression(idVariable), idVariable, new ConstantExpression("(unsaved)")));
            Statement s = new ReturnStatement(ge);
            MethodNode mn = new MethodNode("toString", Modifier.PUBLIC, new ClassNode(String.class), new Parameter[0], new ClassNode[0], s);
            classNode.addMethod(mn)
        }
    }

}
