/*
 * Copyright (c) 2015, 2019 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.language.literal;

import org.truffleruby.RubyContext;
import org.truffleruby.language.ContextSourceRubyNode;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeCost;
import com.oracle.truffle.api.nodes.NodeInfo;

@NodeInfo(cost = NodeCost.NONE)
public class NilLiteralNode extends ContextSourceRubyNode {

    private final boolean isImplicit;

    public NilLiteralNode(boolean isImplicit) {
        this.isImplicit = isImplicit;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return nil();
    }

    @Override
    public Object isDefined(VirtualFrame frame, RubyContext context) {
        return coreStrings().NIL.createInstance();
    }

    public boolean isImplicit() {
        return isImplicit;
    }

}
