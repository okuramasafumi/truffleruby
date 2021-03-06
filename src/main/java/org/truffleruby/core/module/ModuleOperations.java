/*
 * Copyright (c) 2014, 2019 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.module;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;

import org.truffleruby.Layouts;
import org.truffleruby.RubyContext;
import org.truffleruby.collections.Memo;
import org.truffleruby.core.array.ArrayUtils;
import org.truffleruby.core.string.StringUtils;
import org.truffleruby.language.LexicalScope;
import org.truffleruby.language.RubyConstant;
import org.truffleruby.language.RubyGuards;
import org.truffleruby.language.Visibility;
import org.truffleruby.language.control.RaiseException;
import org.truffleruby.language.methods.DeclarationContext;
import org.truffleruby.language.methods.InternalMethod;
import org.truffleruby.language.objects.shared.SharedObjects;
import org.truffleruby.parser.Identifiers;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.utilities.NeverValidAssumption;

public abstract class ModuleOperations {

    public static final Assumption[] EMPTY_ASSUMPTION_ARRAY = new Assumption[0];
    public static final DynamicObject[] EMPTY_DYNAMIC_OBJECT_ARRAY = new DynamicObject[0];

    @TruffleBoundary
    public static boolean includesModule(DynamicObject module, DynamicObject other) {
        assert RubyGuards.isRubyModule(module);
        assert RubyGuards.isRubyModule(other);

        for (DynamicObject ancestor : Layouts.MODULE.getFields(module).ancestors()) {
            if (ancestor == other) {
                return true;
            }
        }

        return false;
    }

    public static boolean assignableTo(DynamicObject thisClass, DynamicObject otherClass) {
        return includesModule(thisClass, otherClass);
    }

    public static boolean inAncestorsOf(DynamicObject module, DynamicObject ancestors) {
        return includesModule(ancestors, module);
    }

    @TruffleBoundary
    public static boolean canBindMethodTo(InternalMethod method, DynamicObject module) {
        assert RubyGuards.isRubyModule(module);
        final DynamicObject origin = method.getDeclaringModule();

        if (!RubyGuards.isRubyClass(origin)) { // Module (not Class) methods can always be bound
            return true;
        } else if (Layouts.MODULE.getFields(module).isRefinement()) {
            DynamicObject refinedModule = Layouts.MODULE.getFields(module).getRefinedModule();
            return RubyGuards.isRubyClass(refinedModule) && ModuleOperations.assignableTo(refinedModule, origin);
        } else {
            return RubyGuards.isRubyClass(module) && ModuleOperations.assignableTo(module, origin);
        }
    }

    @TruffleBoundary
    public static String constantName(RubyContext context, DynamicObject module, String name) {
        if (module == context.getCoreLibrary().objectClass) {
            return "::" + name;
        } else {
            return Layouts.MODULE.getFields(module).getName() + "::" + name;
        }
    }

    public static String constantNameNoLeadingColon(RubyContext context, DynamicObject module, String name) {
        if (module == context.getCoreLibrary().objectClass) {
            return name;
        } else {
            return Layouts.MODULE.getFields(module).getName() + "::" + name;
        }
    }

    @TruffleBoundary
    public static Iterable<Entry<String, RubyConstant>> getAllConstants(DynamicObject module) {
        final Map<String, RubyConstant> constants = new HashMap<>();

        // Look in the current module
        for (Map.Entry<String, RubyConstant> constant : Layouts.MODULE.getFields(module).getConstants()) {
            constants.put(constant.getKey(), constant.getValue());
        }

        // Look in ancestors
        for (DynamicObject ancestor : Layouts.MODULE.getFields(module).prependedAndIncludedModules()) {
            for (Map.Entry<String, RubyConstant> constant : Layouts.MODULE.getFields(ancestor).getConstants()) {
                constants.putIfAbsent(constant.getKey(), constant.getValue());
            }
        }

        return constants.entrySet();
    }

    /** NOTE: This method returns false for an undefined RubyConstant */
    public static boolean isConstantDefined(RubyConstant constant) {
        return constant != null && !constant.isUndefined() &&
                !(constant.isAutoload() && constant.getAutoloadConstant().isAutoloadingThread());
    }

    /** NOTE: This method might return an undefined RubyConstant */
    private static boolean constantExists(RubyConstant constant, ArrayList<Assumption> assumptions) {
        if (constant != null) {
            if (constant.isAutoload() && constant.getAutoloadConstant().isAutoloading()) {
                // Cannot cache the lookup of an autoloading constant as the result depends on the calling thread
                assumptions.add(NeverValidAssumption.INSTANCE);
                return !constant.getAutoloadConstant().isAutoloadingThread();
            } else {
                return true;
            }
        } else {
            return false;
        }
    }

    @TruffleBoundary
    public static ConstantLookupResult lookupConstant(RubyContext context, DynamicObject module, String name) {
        return lookupConstant(context, module, name, new ArrayList<>());
    }

    @TruffleBoundary
    private static ConstantLookupResult lookupConstant(RubyContext context, DynamicObject module, String name,
            ArrayList<Assumption> assumptions) {
        // Look in the current module
        ModuleFields fields = Layouts.MODULE.getFields(module);
        assumptions.add(fields.getConstantsUnmodifiedAssumption());
        RubyConstant constant = fields.getConstant(name);
        if (constantExists(constant, assumptions)) {
            return new ConstantLookupResult(constant, toArray(assumptions));
        }

        // Look in ancestors
        for (DynamicObject ancestor : Layouts.MODULE.getFields(module).ancestors()) {
            if (ancestor == module) {
                continue;
            }
            fields = Layouts.MODULE.getFields(ancestor);
            assumptions.add(fields.getConstantsUnmodifiedAssumption());
            constant = fields.getConstant(name);
            if (constantExists(constant, assumptions)) {
                return new ConstantLookupResult(constant, toArray(assumptions));
            }
        }

        // Nothing found
        return new ConstantLookupResult(null, toArray(assumptions));
    }

    @TruffleBoundary
    public static ConstantLookupResult lookupConstantInObject(RubyContext context, String name,
            ArrayList<Assumption> assumptions) {
        final DynamicObject objectClass = context.getCoreLibrary().objectClass;

        ModuleFields fields = Layouts.MODULE.getFields(objectClass);
        assumptions.add(fields.getConstantsUnmodifiedAssumption());
        RubyConstant constant = fields.getConstant(name);
        if (constantExists(constant, assumptions)) {
            return new ConstantLookupResult(constant, toArray(assumptions));
        }

        for (DynamicObject ancestor : Layouts.MODULE.getFields(objectClass).prependedAndIncludedModules()) {
            fields = Layouts.MODULE.getFields(ancestor);
            assumptions.add(fields.getConstantsUnmodifiedAssumption());
            constant = fields.getConstant(name);
            if (constantExists(constant, assumptions)) {
                return new ConstantLookupResult(constant, toArray(assumptions));
            }
        }

        return new ConstantLookupResult(null, toArray(assumptions));
    }

    @TruffleBoundary
    public static ConstantLookupResult lookupConstantAndObject(RubyContext context, DynamicObject module, String name,
            ArrayList<Assumption> assumptions) {
        final ConstantLookupResult constant = lookupConstant(context, module, name, assumptions);
        if (constant.isFound()) {
            return constant;
        }

        // Look in Object and its included modules for modules (not for classes)
        if (!RubyGuards.isRubyClass(module)) {
            return lookupConstantInObject(context, name, assumptions);
        } else {
            return constant;
        }
    }

    @TruffleBoundary
    public static ConstantLookupResult lookupConstantWithLexicalScope(RubyContext context, LexicalScope lexicalScope,
            String name) {
        final DynamicObject module = lexicalScope.getLiveModule();
        final ArrayList<Assumption> assumptions = new ArrayList<>();

        // Look in lexical scope
        while (lexicalScope != context.getRootLexicalScope()) {
            final ModuleFields fields = Layouts.MODULE.getFields(lexicalScope.getLiveModule());
            assumptions.add(fields.getConstantsUnmodifiedAssumption());
            final RubyConstant constant = fields.getConstant(name);
            if (constantExists(constant, assumptions)) {
                return new ConstantLookupResult(constant, toArray(assumptions));
            }

            lexicalScope = lexicalScope.getParent();
        }

        return lookupConstantAndObject(context, module, name, assumptions);
    }

    @TruffleBoundary
    public static ConstantLookupResult lookupScopedConstant(RubyContext context, DynamicObject module, String fullName,
            boolean inherit, Node currentNode) {
        int start = 0, next;
        if (fullName.startsWith("::")) {
            module = context.getCoreLibrary().objectClass;
            start += 2;
        }

        while ((next = fullName.indexOf("::", start)) != -1) {
            final String segment = fullName.substring(start, next);
            final ConstantLookupResult constant = lookupConstantWithInherit(
                    context,
                    module,
                    segment,
                    inherit,
                    currentNode);
            if (!constant.isFound()) {
                return constant;
            } else if (RubyGuards.isRubyModule(constant.getConstant().getValue())) {
                module = (DynamicObject) constant.getConstant().getValue();
            } else {
                throw new RaiseException(
                        context,
                        context.getCoreExceptions().typeError(
                                fullName.substring(0, next) + " does not refer to class/module",
                                currentNode));
            }
            start = next + 2;
        }

        final String lastSegment = fullName.substring(start);
        if (!Identifiers.isValidConstantName(lastSegment)) {
            throw new RaiseException(
                    context,
                    context.getCoreExceptions().nameError(
                            StringUtils.format("wrong constant name %s", fullName),
                            module,
                            fullName,
                            currentNode));
        }

        return lookupConstantWithInherit(context, module, lastSegment, inherit, currentNode);
    }

    @TruffleBoundary
    public static ConstantLookupResult lookupConstantWithInherit(RubyContext context, DynamicObject module, String name,
            boolean inherit, Node currentNode) {
        assert RubyGuards.isRubyModule(module);

        if (!Identifiers.isValidConstantName(name)) {
            throw new RaiseException(
                    context,
                    context.getCoreExceptions().nameError(
                            StringUtils.format("wrong constant name %s", name),
                            module,
                            name,
                            currentNode));
        }

        final ArrayList<Assumption> assumptions = new ArrayList<>();
        if (inherit) {
            return ModuleOperations.lookupConstantAndObject(context, module, name, assumptions);
        } else {
            final ModuleFields fields = Layouts.MODULE.getFields(module);
            assumptions.add(fields.getConstantsUnmodifiedAssumption());
            final RubyConstant constant = fields.getConstant(name);
            if (constantExists(constant, assumptions)) {
                return new ConstantLookupResult(constant, toArray(assumptions));
            } else {
                return new ConstantLookupResult(null, toArray(assumptions));
            }
        }
    }

    @TruffleBoundary
    public static Map<String, InternalMethod> getAllMethods(DynamicObject module) {
        assert RubyGuards.isRubyModule(module);

        final Map<String, InternalMethod> methods = new HashMap<>();

        for (DynamicObject ancestor : Layouts.MODULE.getFields(module).ancestors()) {
            for (InternalMethod method : Layouts.MODULE.getFields(ancestor).getMethods()) {
                methods.putIfAbsent(method.getName(), method);
            }
        }

        if (Layouts.MODULE.getFields(module).isRefinement()) {
            for (DynamicObject ancestor : Layouts.MODULE
                    .getFields(Layouts.MODULE.getFields(module).getRefinedModule())
                    .ancestors()) {
                for (InternalMethod method : Layouts.MODULE.getFields(ancestor).getMethods()) {
                    methods.putIfAbsent(method.getName(), method);
                }
            }
        }

        return methods;
    }

    @TruffleBoundary
    public static Map<String, InternalMethod> getMethodsBeforeLogicalClass(DynamicObject module) {
        assert RubyGuards.isRubyModule(module);

        final Map<String, InternalMethod> methods = new HashMap<>();

        ModuleChain chain = Layouts.MODULE.getFields(module).getFirstModuleChain();

        while (true) {
            if (chain instanceof PrependMarker) {
                // We need to stop if we are entering prepended modules of a class which is not a
                // singleton class. So we look for the PrependMarker explicitly here.
                final DynamicObject origin = ((PrependMarker) chain).getOrigin().getActualModule();
                // When we find a class which is not a singleton class, we are done
                if (RubyGuards.isRubyClass(origin) && !Layouts.CLASS.getIsSingleton(origin)) {
                    break;
                } else {
                    chain = chain.getParentModule();
                }
            }

            final DynamicObject ancestor = chain.getActualModule();

            // When we find a class which is not a singleton class, we are done
            if (RubyGuards.isRubyClass(ancestor) && !Layouts.CLASS.getIsSingleton(ancestor)) {
                break;
            }

            for (InternalMethod method : Layouts.MODULE.getFields(ancestor).getMethods()) {
                methods.putIfAbsent(method.getName(), method);
            }

            chain = chain.getParentModule();
        }

        return methods;
    }

    @TruffleBoundary
    public static Map<String, InternalMethod> getMethodsUntilLogicalClass(DynamicObject module) {
        assert RubyGuards.isRubyModule(module);

        final Map<String, InternalMethod> methods = new HashMap<>();

        for (DynamicObject ancestor : Layouts.MODULE.getFields(module).ancestors()) {
            for (InternalMethod method : Layouts.MODULE.getFields(ancestor).getMethods()) {
                methods.putIfAbsent(method.getName(), method);
            }

            // When we find a class which is not a singleton class, we are done
            if (RubyGuards.isRubyClass(ancestor) && !Layouts.CLASS.getIsSingleton(ancestor)) {
                break;
            }
        }

        return methods;
    }

    @TruffleBoundary
    public static Map<String, InternalMethod> withoutUndefinedMethods(Map<String, InternalMethod> methods) {
        Map<String, InternalMethod> definedMethods = new HashMap<>();
        for (Entry<String, InternalMethod> method : methods.entrySet()) {
            if (!method.getValue().isUndefined()) {
                definedMethods.put(method.getKey(), method.getValue());
            }
        }
        return definedMethods;
    }

    public static MethodLookupResult lookupMethodCached(DynamicObject module, String name,
            DeclarationContext declarationContext) {
        return lookupMethodCached(module, null, name, declarationContext);
    }

    @TruffleBoundary
    private static MethodLookupResult lookupMethodCached(DynamicObject module, DynamicObject lookupTo, String name,
            DeclarationContext declarationContext) {
        final ArrayList<Assumption> assumptions = new ArrayList<>();

        // Look in ancestors
        for (DynamicObject ancestor : Layouts.MODULE.getFields(module).ancestors()) {
            if (ancestor == lookupTo) {
                return new MethodLookupResult(null, toArray(assumptions));
            }
            final DynamicObject[] refinements = getRefinementsFor(declarationContext, ancestor);

            if (refinements != null) {
                for (DynamicObject refinement : refinements) {
                    // If we have more then one active refinement for C (where C is refined module):
                    //     R1.ancestors = [R1, A, C, ...]
                    //     R2.ancestors = [R2, B, C, ...]
                    //     R3.ancestors = [R3, D, C, ...]
                    // we are only looking up to C
                    // R3 -> D -> R2 -> B -> R1 -> A
                    final MethodLookupResult refinedMethod = lookupMethodCached(
                            refinement,
                            ancestor,
                            name,
                            null);
                    for (Assumption assumption : refinedMethod.getAssumptions()) {
                        assumptions.add(assumption);
                    }
                    if (refinedMethod.isDefined()) {
                        InternalMethod method = rememberUsedRefinements(refinedMethod.getMethod(), declarationContext);
                        return new MethodLookupResult(method, toArray(assumptions));
                    }
                }
            }

            final ModuleFields fields = Layouts.MODULE.getFields(ancestor);
            assumptions.add(fields.getMethodsUnmodifiedAssumption());
            final InternalMethod method = fields.getMethod(name);

            if (method != null) {
                return new MethodLookupResult(method, toArray(assumptions));
            }
        }

        // Nothing found
        return new MethodLookupResult(null, toArray(assumptions));
    }

    public static InternalMethod lookupMethodUncached(DynamicObject module, String name,
            DeclarationContext declarationContext) {
        return lookupMethodUncached(module, null, name, declarationContext);
    }

    @TruffleBoundary
    private static InternalMethod lookupMethodUncached(DynamicObject module, DynamicObject lookupTo, String name,
            DeclarationContext declarationContext) {

        for (DynamicObject ancestor : Layouts.MODULE.getFields(module).ancestors()) {
            if (ancestor == lookupTo) {
                return null;
            }
            final DynamicObject[] refinements = getRefinementsFor(declarationContext, ancestor);

            if (refinements != null) {
                for (DynamicObject refinement : refinements) {
                    final InternalMethod refinedMethod = lookupMethodUncached(
                            refinement,
                            ancestor,
                            name,
                            null);
                    if (refinedMethod != null) {
                        return rememberUsedRefinements(refinedMethod, declarationContext);
                    }
                }
            }

            final ModuleFields fields = Layouts.MODULE.getFields(ancestor);
            final InternalMethod method = fields.getMethod(name);

            if (method != null) {
                return method;
            }
        }

        // Nothing found
        return null;
    }

    public static InternalMethod lookupMethod(DynamicObject module, String name, Visibility visibility) {
        final InternalMethod method = lookupMethodUncached(module, name, null);
        if (method == null || method.isUndefined()) {
            return null;
        }
        return method.getVisibility() == visibility ? method : null;
    }

    public static MethodLookupResult lookupSuperMethod(InternalMethod currentMethod, DynamicObject objectMetaClass) {
        assert RubyGuards.isRubyModule(objectMetaClass);
        final String name = currentMethod.getSharedMethodInfo().getName(); // use the original name

        Memo<Boolean> foundDeclaringModule = new Memo<>(false);
        return lookupSuperMethod(
                currentMethod.getDeclaringModule(),
                null,
                name,
                objectMetaClass,
                foundDeclaringModule,
                currentMethod.getDeclarationContext(),
                currentMethod.getActiveRefinements());
    }


    @TruffleBoundary
    private static MethodLookupResult lookupSuperMethod(DynamicObject declaringModule, DynamicObject lookupTo,
            String name, DynamicObject objectMetaClass, Memo<Boolean> foundDeclaringModule,
            DeclarationContext declarationContext, DeclarationContext callerDeclaringContext) {
        assert RubyGuards.isRubyModule(declaringModule);
        assert RubyGuards.isRubyModule(objectMetaClass);

        final ArrayList<Assumption> assumptions = new ArrayList<>();
        final boolean isRefinedMethod = Layouts.MODULE.getFields(declaringModule).isRefinement();

        for (DynamicObject ancestor : Layouts.MODULE.getFields(objectMetaClass).ancestors()) {
            if (ancestor == lookupTo) {
                return new MethodLookupResult(null, toArray(assumptions));
            }

            final DynamicObject[] refinements = getRefinementsFor(declarationContext, callerDeclaringContext, ancestor);

            if (refinements != null) {
                for (DynamicObject refinement : refinements) {
                    final MethodLookupResult superMethodInRefinement = lookupSuperMethod(
                            declaringModule,
                            ancestor,
                            name,
                            refinement,
                            foundDeclaringModule,
                            null,
                            null);
                    for (Assumption assumption : superMethodInRefinement.getAssumptions()) {
                        assumptions.add(assumption);
                    }
                    if (superMethodInRefinement.isDefined()) {
                        InternalMethod method = superMethodInRefinement.getMethod();
                        return new MethodLookupResult(
                                rememberUsedRefinements(method, declarationContext, refinements, ancestor),
                                toArray(assumptions));
                    }
                    if (foundDeclaringModule.get() && isRefinedMethod) {
                        // if method is defined in refinement module (R)
                        // we should lookup only in this active refinement and skip other
                        break;
                    }
                }
            }

            if (!foundDeclaringModule.get()) {
                if (ancestor == declaringModule) {
                    foundDeclaringModule.set(true);
                }
            } else {
                final ModuleFields fields = Layouts.MODULE.getFields(ancestor);
                assumptions.add(fields.getMethodsUnmodifiedAssumption());
                final InternalMethod method = fields.getMethod(name);
                if (method != null) {
                    return new MethodLookupResult(method, toArray(assumptions));
                }
            }
        }

        // Nothing found
        return new MethodLookupResult(null, toArray(assumptions));
    }

    private static InternalMethod rememberUsedRefinements(InternalMethod method,
            DeclarationContext declarationContext) {
        return method.withActiveRefinements(declarationContext);
    }

    private static InternalMethod rememberUsedRefinements(InternalMethod method,
            DeclarationContext declarationContext, DynamicObject[] refinements, DynamicObject ancestor) {
        assert refinements != null;

        final Map<DynamicObject, DynamicObject[]> currentRefinements = new HashMap<>(
                declarationContext.getRefinements());
        currentRefinements.put(ancestor, refinements);

        return method.withActiveRefinements(declarationContext.withRefinements(currentRefinements));
    }

    private static DynamicObject[] getRefinementsFor(DeclarationContext declarationContext,
            DeclarationContext callerDeclaringContext, DynamicObject module) {
        final DynamicObject[] lexicalRefinements = getRefinementsFor(declarationContext, module);
        final DynamicObject[] callerRefinements = getRefinementsFor(callerDeclaringContext, module);

        if (lexicalRefinements == null) {
            return callerRefinements;
        }

        if (callerRefinements == null) {
            return lexicalRefinements;
        }

        final ArrayList<DynamicObject> list = new ArrayList<>(Arrays.asList(callerRefinements));
        for (DynamicObject refinement : lexicalRefinements) {
            if (!ArrayUtils.contains(callerRefinements, refinement)) {
                list.add(refinement);
            }
        }
        return list.toArray(EMPTY_DYNAMIC_OBJECT_ARRAY);
    }

    private static DynamicObject[] getRefinementsFor(DeclarationContext declarationContext, DynamicObject module) {
        if (declarationContext == null) {
            return null;
        }
        return declarationContext.getRefinementsFor(module);
    }

    private static Assumption[] toArray(ArrayList<Assumption> assumptions) {
        return assumptions.toArray(EMPTY_ASSUMPTION_ARRAY);
    }

    @TruffleBoundary
    public static Map<String, Object> getAllClassVariables(DynamicObject module) {
        final Map<String, Object> classVariables = new HashMap<>();

        classVariableLookup(module, module1 -> {
            classVariables.putAll(Layouts.MODULE.getFields(module1).getClassVariables());
            return null;
        });

        return classVariables;
    }

    @TruffleBoundary
    public static Object lookupClassVariable(DynamicObject module, final String name) {
        return classVariableLookup(module, module1 -> Layouts.MODULE.getFields(module1).getClassVariables().get(name));
    }

    @TruffleBoundary
    public static void setClassVariable(final RubyContext context, DynamicObject module, final String name,
            final Object value, final Node currentNode) {
        assert RubyGuards.isRubyModule(module);
        ModuleFields moduleFields = Layouts.MODULE.getFields(module);
        moduleFields.checkFrozen(context, currentNode);
        SharedObjects.propagate(context, module, value);

        // if the cvar is not already defined we need to take lock and ensure there is only one
        // defined in the class tree
        if (!trySetClassVariable(module, name, value)) {
            synchronized (context.getClassVariableDefinitionLock()) {
                if (!trySetClassVariable(module, name, value)) {
                    /* This is double-checked locking, but it is safe because when writing to a ConcurrentHashMap "an
                     * update operation for a given key bears a happens-before relation with any (non-null) retrieval
                     * for that key reporting the updated value" (JavaDoc) so the value is guaranteed to be fully
                     * published before it can be found in the map. */

                    moduleFields.getClassVariables().put(name, value);
                }
            }
        }
    }

    private static boolean trySetClassVariable(DynamicObject topModule, String name, Object value) {
        final DynamicObject found = classVariableLookup(topModule, module -> {
            final ModuleFields moduleFields = Layouts.MODULE.getFields(module);
            if (moduleFields.getClassVariables().replace(name, value) != null) {
                return module;
            } else {
                return null;
            }
        });
        return found != null;
    }

    @TruffleBoundary
    public static Object removeClassVariable(ModuleFields moduleFields, RubyContext context, Node currentNode,
            String name) {
        moduleFields.checkFrozen(context, currentNode);

        final Object found = moduleFields.getClassVariables().remove(name);
        if (found == null) {
            throw new RaiseException(
                    context,
                    context.getCoreExceptions().nameErrorClassVariableNotDefined(
                            name,
                            moduleFields.rubyModuleObject,
                            currentNode));
        }
        return found;
    }

    @TruffleBoundary
    private static <R> R classVariableLookup(DynamicObject module, Function<DynamicObject, R> action) {
        assert RubyGuards.isRubyModule(module);

        // Look in the current module
        R result = action.apply(module);
        if (result != null) {
            return result;
        }

        // If singleton class of a module, check the attached module.
        if (RubyGuards.isRubyClass(module)) {
            DynamicObject klass = module;
            if (Layouts.CLASS.getIsSingleton(klass) && Layouts.MODULE.isModule(Layouts.CLASS.getAttached(klass))) {
                module = Layouts.CLASS.getAttached(klass);

                result = action.apply(module);
                if (result != null) {
                    return result;
                }
            }
        }

        // Look in ancestors
        for (DynamicObject ancestor : Layouts.MODULE.getFields(module).ancestors()) {
            if (ancestor == module) {
                continue;
            }
            result = action.apply(ancestor);
            if (result != null) {
                return result;
            }
        }

        return null;
    }

    @TruffleBoundary
    public static boolean isMethodPrivateFromName(String name) {
        return (name.equals("initialize") || name.equals("initialize_copy") ||
                name.equals("initialize_clone") || name.equals("initialize_dup") ||
                name.equals("respond_to_missing?"));
    }

}
