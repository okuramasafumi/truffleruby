/*
 * Copyright (c) 2015, 2019 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.exception;

import java.util.Set;

import org.truffleruby.interop.messages.NameErrorMessages;
import org.truffleruby.language.backtrace.Backtrace;
import org.truffleruby.language.objects.ObjectGraph;
import org.truffleruby.language.objects.ObjectGraphNode;

import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.object.Shape;


public class RubyNameError extends RubyException implements ObjectGraphNode {

    public Object receiver;
    public Object name;

    public RubyNameError(Shape shape, Object message, Backtrace backtrace, Object cause, Object receiver, Object name) {
        super(shape, message, backtrace, cause);
        assert name != null;
        this.receiver = receiver;
        this.name = name;
    }

    @Override
    public void getAdjacentObjects(Set<Object> reachable) {
        super.getAdjacentObjects(reachable);

        if (ObjectGraph.isSymbolOrDynamicObject(receiver)) {
            reachable.add(receiver);
        }

        if (ObjectGraph.isSymbolOrDynamicObject(name)) {
            reachable.add(name);
        }
    }

    @Override
    @ExportMessage
    public Class<?> dispatch() {
        return NameErrorMessages.class;
    }

}
