/*
 * Copyright (c) 2013, 2019 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.language.methods;

import java.util.Set;

import org.truffleruby.Layouts;
import org.truffleruby.RubyContext;
import org.truffleruby.core.proc.RubyProc;
import org.truffleruby.language.LexicalScope;
import org.truffleruby.language.RubyGuards;
import org.truffleruby.language.Visibility;
import org.truffleruby.language.objects.ObjectGraphNode;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.object.DynamicObject;

/** A Ruby method: either a method in a module, a literal module/class body or some meta-information for eval'd code.
 * Blocks capture the method in which they are defined. */
public class InternalMethod implements ObjectGraphNode {

    private final SharedMethodInfo sharedMethodInfo;
    /** Contains the "dynamic" lexical scope in case this method is under a class << expr; HERE; end */
    private final LexicalScope lexicalScope;
    private final DeclarationContext declarationContext;
    /** The active refinements used during lookup and remembered this way on the method we will call */
    private final DeclarationContext activeRefinements;
    private final String name;

    private final DynamicObject declaringModule;
    private final Visibility visibility;
    private final boolean undefined;
    private final boolean unimplemented; // similar to MRI's rb_f_notimplement
    /** True if the method is defined in the core library (in Java or Ruby) */
    private final boolean builtIn;
    private final RubyProc proc; // only if method is created from a Proc

    private final RootCallTarget callTarget;
    private final DynamicObject capturedBlock;

    public static InternalMethod fromProc(
            RubyContext context,
            SharedMethodInfo sharedMethodInfo,
            DeclarationContext declarationContext,
            String name,
            DynamicObject declaringModule,
            Visibility visibility,
            RubyProc proc,
            RootCallTarget callTarget) {
        return new InternalMethod(
                context,
                sharedMethodInfo,
                proc.method.getLexicalScope(),
                declarationContext,
                name,
                declaringModule,
                visibility,
                false,
                proc,
                callTarget,
                proc.block);
    }

    public InternalMethod(
            RubyContext context,
            SharedMethodInfo sharedMethodInfo,
            LexicalScope lexicalScope,
            DeclarationContext declarationContext,
            String name,
            DynamicObject declaringModule,
            Visibility visibility,
            RootCallTarget callTarget) {
        this(
                context,
                sharedMethodInfo,
                lexicalScope,
                declarationContext,
                name,
                declaringModule,
                visibility,
                false,
                null,
                callTarget,
                null);
    }

    public InternalMethod(
            RubyContext context,
            SharedMethodInfo sharedMethodInfo,
            LexicalScope lexicalScope,
            DeclarationContext declarationContext,
            String name,
            DynamicObject declaringModule,
            Visibility visibility,
            boolean undefined,
            RubyProc proc,
            RootCallTarget callTarget,
            DynamicObject capturedBlock) {
        this(
                sharedMethodInfo,
                lexicalScope,
                declarationContext,
                name,
                declaringModule,
                visibility,
                undefined,
                false,
                !context.getCoreLibrary().isLoaded(),
                null,
                proc,
                callTarget,
                capturedBlock);
    }

    private InternalMethod(
            SharedMethodInfo sharedMethodInfo,
            LexicalScope lexicalScope,
            DeclarationContext declarationContext,
            String name,
            DynamicObject declaringModule,
            Visibility visibility,
            boolean undefined,
            boolean unimplemented,
            boolean builtIn,
            DeclarationContext activeRefinements,
            RubyProc proc,
            RootCallTarget callTarget,
            DynamicObject capturedBlock) {
        assert RubyGuards.isRubyModule(declaringModule);
        assert lexicalScope != null;
        this.sharedMethodInfo = sharedMethodInfo;
        this.lexicalScope = lexicalScope;
        this.declarationContext = declarationContext;
        this.declaringModule = declaringModule;
        this.name = name;
        this.visibility = visibility;
        this.undefined = undefined;
        this.unimplemented = unimplemented;
        this.builtIn = builtIn;
        this.activeRefinements = activeRefinements;
        this.proc = proc;
        this.callTarget = callTarget;
        this.capturedBlock = capturedBlock;
    }

    public SharedMethodInfo getSharedMethodInfo() {
        return sharedMethodInfo;
    }

    public DynamicObject getDeclaringModule() {
        return declaringModule;
    }

    public String getName() {
        return name;
    }

    public Visibility getVisibility() {
        return visibility;
    }

    public boolean isUndefined() {
        return undefined;
    }

    public boolean isUnimplemented() {
        return unimplemented;
    }

    public boolean isBuiltIn() {
        return builtIn;
    }

    public RootCallTarget getCallTarget() {
        return callTarget;
    }

    public InternalMethod withDeclaringModule(DynamicObject newDeclaringModule) {
        assert RubyGuards.isRubyModule(newDeclaringModule);

        if (newDeclaringModule == declaringModule) {
            return this;
        } else {
            return new InternalMethod(
                    sharedMethodInfo,
                    lexicalScope,
                    declarationContext,
                    name,
                    newDeclaringModule,
                    visibility,
                    undefined,
                    unimplemented,
                    builtIn,
                    activeRefinements,
                    proc,
                    callTarget,
                    capturedBlock);
        }
    }

    public InternalMethod withName(String newName) {
        if (newName.equals(name)) {
            return this;
        } else {
            return new InternalMethod(
                    sharedMethodInfo,
                    lexicalScope,
                    declarationContext,
                    newName,
                    declaringModule,
                    visibility,
                    undefined,
                    unimplemented,
                    builtIn,
                    activeRefinements,
                    proc,
                    callTarget,
                    capturedBlock);
        }
    }

    public InternalMethod withVisibility(Visibility newVisibility) {
        if (newVisibility == visibility) {
            return this;
        } else {
            return new InternalMethod(
                    sharedMethodInfo,
                    lexicalScope,
                    declarationContext,
                    name,
                    declaringModule,
                    newVisibility,
                    undefined,
                    unimplemented,
                    builtIn,
                    activeRefinements,
                    proc,
                    callTarget,
                    capturedBlock);
        }
    }

    public InternalMethod withActiveRefinements(DeclarationContext context) {
        if (context == activeRefinements) {
            return this;
        } else {
            return new InternalMethod(
                    sharedMethodInfo,
                    lexicalScope,
                    declarationContext,
                    name,
                    declaringModule,
                    visibility,
                    undefined,
                    unimplemented,
                    builtIn,
                    context,
                    proc,
                    callTarget,
                    capturedBlock);
        }
    }

    public InternalMethod withDeclarationContext(DeclarationContext newDeclarationContext) {
        if (newDeclarationContext == declarationContext) {
            return this;
        } else {
            return new InternalMethod(
                    sharedMethodInfo,
                    lexicalScope,
                    newDeclarationContext,
                    name,
                    declaringModule,
                    visibility,
                    undefined,
                    unimplemented,
                    builtIn,
                    activeRefinements,
                    proc,
                    callTarget,
                    capturedBlock);
        }
    }

    public InternalMethod undefined() {
        return new InternalMethod(
                sharedMethodInfo,
                lexicalScope,
                declarationContext,
                name,
                declaringModule,
                visibility,
                true,
                unimplemented,
                builtIn,
                activeRefinements,
                proc,
                callTarget,
                capturedBlock);
    }

    public InternalMethod unimplemented() {
        return new InternalMethod(
                sharedMethodInfo,
                lexicalScope,
                declarationContext,
                name,
                declaringModule,
                visibility,
                undefined,
                true,
                builtIn,
                activeRefinements,
                proc,
                callTarget,
                capturedBlock);
    }

    @TruffleBoundary
    public boolean isVisibleTo(DynamicObject callerClass) {
        assert RubyGuards.isRubyClass(callerClass);

        switch (visibility) {
            case PUBLIC:
                return true;

            case PROTECTED:
                return isProtectedMethodVisibleTo(callerClass);

            case PRIVATE:
                // A private method may only be called with an implicit receiver,
                // in which case the visibility must not be checked.
                return false;

            default:
                throw new UnsupportedOperationException(visibility.name());
        }
    }

    @TruffleBoundary
    public boolean isProtectedMethodVisibleTo(DynamicObject callerClass) {
        assert visibility == Visibility.PROTECTED;

        for (DynamicObject ancestor : Layouts.MODULE.getFields(callerClass).ancestors()) {
            if (ancestor == declaringModule || Layouts.BASIC_OBJECT.getMetaClass(ancestor) == declaringModule) {
                return true;
            }
        }

        return false;
    }

    @Override
    public String toString() {
        return sharedMethodInfo.toString();
    }

    @Override
    public void getAdjacentObjects(Set<Object> adjacent) {
        if (declaringModule != null) {
            adjacent.add(declaringModule);
        }

        if (proc != null) {
            adjacent.add(proc);
        }
    }

    public DynamicObject getCapturedBlock() {
        return capturedBlock;
    }

    public LexicalScope getLexicalScope() {
        return lexicalScope;
    }

    public DeclarationContext getDeclarationContext() {
        return declarationContext;
    }

    public DeclarationContext getActiveRefinements() {
        return activeRefinements;
    }
}
