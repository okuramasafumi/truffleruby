/*
 * Copyright (c) 2017, 2019 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.hash;

import org.truffleruby.core.symbol.RubySymbol;
import org.truffleruby.language.RubyContextSourceNode;
import org.truffleruby.language.RubyNode;
import org.truffleruby.language.control.RaiseException;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.profiles.BranchProfile;

public class EnsureSymbolKeysNode extends RubyContextSourceNode {

    @Child private RubyNode child;

    private final BranchProfile errorProfile = BranchProfile.create();

    public EnsureSymbolKeysNode(RubyNode child) {
        this.child = child;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        final Object hash = child.execute(frame);
        for (KeyValue keyValue : HashOperations.iterableKeyValues((RubyHash) hash)) {
            if (!(keyValue.getKey() instanceof RubySymbol)) {
                errorProfile.enter();
                throw new RaiseException(
                        getContext(),
                        coreExceptions().typeErrorWrongArgumentType(keyValue.getKey(), "Symbol", this));
            }
        }
        return hash;
    }
}
