/*
 * Copyright (c) 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.encoding;

import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.Shape;
import org.jcodings.Encoding;
import org.truffleruby.interop.messages.RubyEncodingMessages;
import org.truffleruby.language.RubyDynamicObject;

public class RubyEncoding extends RubyDynamicObject {

    public final Encoding encoding;
    public final DynamicObject name;

    public RubyEncoding(Shape shape, Encoding encoding, DynamicObject name) {
        super(shape);
        this.encoding = encoding;
        this.name = name;
    }

    @Override
    @ExportMessage
    public Class<?> dispatch() {
        return RubyEncodingMessages.class;
    }

}
