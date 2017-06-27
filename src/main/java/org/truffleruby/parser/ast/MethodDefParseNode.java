/*
 ***** BEGIN LICENSE BLOCK *****
 * Version: EPL 1.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Eclipse Public
 * License Version 1.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/epl-v10.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2006 Mirko Stocker <me@misto.ch>
 * 
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the EPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the EPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/
package org.truffleruby.parser.ast;

/**
 * Base class for DefnParseNode and DefsParseNode
 */

import org.truffleruby.language.SourceIndexLength;
import org.truffleruby.parser.ast.types.INameNode;
import org.truffleruby.parser.scope.StaticScope;

public abstract class MethodDefParseNode extends ParseNode implements INameNode, DefNode {
    protected final String name;
    protected final ArgsParseNode argsNode;
    protected final StaticScope scope;
    protected final ParseNode bodyNode;

    public MethodDefParseNode(SourceIndexLength position, String name, ArgsParseNode argsNode,
                              StaticScope scope, ParseNode bodyNode) {
        super(position);

        assert bodyNode != null : "bodyNode must not be null";
            
        this.name = name;
        this.argsNode = argsNode;
        this.scope = scope;
        this.bodyNode = bodyNode;
    }


    /**
     * Gets the argsNode.
     * @return Returns a ParseNode
     */
    public ArgsParseNode getArgsNode() {
        return argsNode;
    }

    /**
     * Get the static scoping information.
     *
     * @return the scoping info
     */
    public StaticScope getScope() {
        return scope;
    }

    /**
     * Gets the body of this class.
     *
     * @return the contents
     */
    public ParseNode getBodyNode() {
        return bodyNode;
    }

    /**
     * Gets the name.
     * @return Returns a String
     */
    public String getName() {
        return name;
    }

}
