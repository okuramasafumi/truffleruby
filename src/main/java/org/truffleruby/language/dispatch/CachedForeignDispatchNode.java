/*
 * Copyright (c) 2014, 2019 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.language.dispatch;

import org.truffleruby.RubyContext;
import org.truffleruby.core.proc.RubyProc;
import org.truffleruby.interop.OutgoingForeignCallNode;
import org.truffleruby.interop.OutgoingForeignCallNodeGen;
import org.truffleruby.language.RubyGuards;
import org.truffleruby.language.methods.TranslateExceptionNode;
import org.truffleruby.language.methods.UnsupportedOperationBehavior;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;

public final class CachedForeignDispatchNode extends CachedDispatchNode {

    @Child private OutgoingForeignCallNode outgoingForeignCallNode;
    @Child private TranslateExceptionNode exceptionTranslatingNode;
    final private String methodName;
    final private ConditionProfile blockProfile = ConditionProfile.create();
    final private BranchProfile errorProfile = BranchProfile.create();

    public CachedForeignDispatchNode(RubyContext context, DispatchNode next, String methodName) {
        super(context, methodName, next, DispatchAction.CALL_METHOD);
        this.methodName = methodName;
        outgoingForeignCallNode = OutgoingForeignCallNodeGen.create();
    }

    @Override
    protected void reassessSplittingInliningStrategy() {
        // Do nothing, this node doesn't split or inline.
    }

    @Override
    protected boolean guard(Object methodName, Object receiver) {
        return guardName(methodName) && RubyGuards.isForeignObject(receiver);
    }

    @Override
    public Object executeDispatch(
            VirtualFrame frame,
            Object receiverObject,
            Object methodName,
            RubyProc blockObject,
            Object[] argumentsObjects) {
        if (guard(methodName, receiverObject)) {
            if (blockProfile.profile(blockObject == null)) {
                return doDispatch(frame, receiverObject, argumentsObjects);
            } else {
                Object[] newArgs = new Object[argumentsObjects.length + 1];
                System.arraycopy(argumentsObjects, 0, newArgs, 0, argumentsObjects.length);
                newArgs[argumentsObjects.length] = blockObject;
                return doDispatch(frame, receiverObject, newArgs);
            }
        } else {
            return next.executeDispatch(frame, receiverObject, methodName, blockObject, argumentsObjects);
        }
    }

    private Object doDispatch(VirtualFrame frame, Object receiverObject, Object[] arguments) {
        try {
            return outgoingForeignCallNode.executeCall(receiverObject, methodName, arguments);
        } catch (Throwable t) {
            errorProfile.enter();
            throw getExceptionTranslatingNode().executeTranslation(t, UnsupportedOperationBehavior.TYPE_ERROR);
        }
    }

    private TranslateExceptionNode getExceptionTranslatingNode() {
        if (exceptionTranslatingNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            exceptionTranslatingNode = insert(TranslateExceptionNode.create());
        }
        return exceptionTranslatingNode;
    }

}
