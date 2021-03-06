/*
 * Copyright (c) 2013, 2019 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.proc;

import org.jcodings.specific.UTF8Encoding;
import org.truffleruby.Layouts;
import org.truffleruby.RubyContext;
import org.truffleruby.builtins.CoreMethod;
import org.truffleruby.builtins.CoreMethodArrayArgumentsNode;
import org.truffleruby.builtins.CoreModule;
import org.truffleruby.builtins.Primitive;
import org.truffleruby.builtins.PrimitiveArrayArgumentsNode;
import org.truffleruby.builtins.PrimitiveNode;
import org.truffleruby.builtins.UnaryCoreMethodNode;
import org.truffleruby.core.array.RubyArray;
import org.truffleruby.core.binding.BindingNodes;
import org.truffleruby.core.binding.RubyBinding;
import org.truffleruby.core.rope.CodeRange;
import org.truffleruby.core.string.StringNodes;
import org.truffleruby.core.symbol.SymbolNodes;
import org.truffleruby.language.NotProvided;
import org.truffleruby.language.Visibility;
import org.truffleruby.language.arguments.ArgumentDescriptorUtils;
import org.truffleruby.language.arguments.ReadCallerFrameNode;
import org.truffleruby.language.arguments.RubyArguments;
import org.truffleruby.language.control.RaiseException;
import org.truffleruby.language.dispatch.CallDispatchHeadNode;
import org.truffleruby.language.locals.FindDeclarationVariableNodes.FindAndReadDeclarationVariableNode;
import org.truffleruby.language.objects.AllocateHelperNode;
import org.truffleruby.language.yield.CallBlockNode;
import org.truffleruby.parser.ArgumentDescriptor;
import org.truffleruby.parser.TranslatorEnvironment;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.api.source.SourceSection;

@CoreModule(value = "Proc", isClass = true)
public abstract class ProcNodes {

    @CoreMethod(names = { "__allocate__", "__layout_allocate__" }, constructor = true, visibility = Visibility.PRIVATE)
    public abstract static class AllocateNode extends UnaryCoreMethodNode {

        @Specialization
        protected RubyProc allocate(DynamicObject rubyClass) {
            throw new RaiseException(getContext(), coreExceptions().typeErrorAllocatorUndefinedFor(rubyClass, this));
        }
    }

    @CoreMethod(names = "new", constructor = true, needsBlock = true, rest = true)
    public abstract static class ProcNewNode extends CoreMethodArrayArgumentsNode {

        @Child private CallDispatchHeadNode initializeNode;
        @Child private AllocateHelperNode allocateHelper;

        public abstract RubyProc executeProcNew(
                VirtualFrame frame,
                DynamicObject procClass,
                Object[] args,
                Object block);

        @Specialization
        protected RubyProc proc(VirtualFrame frame, DynamicObject procClass, Object[] args, NotProvided block,
                @Cached("create(nil)") FindAndReadDeclarationVariableNode readNode,
                @Cached ReadCallerFrameNode readCaller) {
            final MaterializedFrame parentFrame = readCaller.execute(frame);

            Object parentBlock = readNode
                    .execute(parentFrame, TranslatorEnvironment.METHOD_BLOCK_NAME);

            if (parentBlock == nil) {
                parentBlock = tryParentBlockForCExts();
            }

            if (parentBlock == nil) {
                throw new RaiseException(getContext(), coreExceptions().argumentErrorProcWithoutBlock(this));
            }

            return executeProcNew(frame, procClass, args, parentBlock);
        }

        @TruffleBoundary
        protected Object tryParentBlockForCExts() {
            /* TODO CS 11-Mar-17 to pass the remaining cext proc specs we need to determine here if Proc.new has been
             * called from a cext from rb_funcall, and then reach down the stack to the Ruby method that originally went
             * into C and get the block from there. */

            return nil;
        }

        @Specialization(guards = { "procClass == getProcClass()", "block.getShape() == getProcShape()" })
        protected RubyProc procNormalOptimized(DynamicObject procClass, Object[] args, RubyProc block) {
            return block;
        }

        @Specialization(guards = "procClass == metaClass(block)")
        protected RubyProc procNormal(DynamicObject procClass, Object[] args, RubyProc block) {
            return block;
        }

        @Specialization(guards = "procClass != metaClass(block)")
        protected RubyProc procSpecial(VirtualFrame frame, DynamicObject procClass, Object[] args, RubyProc block,
                @Cached AllocateHelperNode allocateHelper,
                @Cached CallDispatchHeadNode initialize) {
            // Instantiate a new instance of procClass as classes do not correspond

            final RubyProc proc = new RubyProc(
                    allocateHelper.getCachedShape(procClass),
                    block.type,
                    block.sharedMethodInfo,
                    block.callTargetForType,
                    block.callTargetForLambdas,
                    block.declarationFrame,
                    block.method,
                    block.block,
                    block.frameOnStackMarker,
                    block.declarationContext);

            allocateHelper.trace(proc, this);
            initialize.callWithBlock(proc, "initialize", block, args);
            return proc;
        }

        protected DynamicObject getProcClass() {
            return coreLibrary().procClass;
        }

        protected Shape getProcShape() {
            return coreLibrary().procShape;
        }

        protected DynamicObject metaClass(DynamicObject object) {
            return Layouts.BASIC_OBJECT.getMetaClass(object);
        }
    }

    @CoreMethod(names = { "dup", "clone" })
    public abstract static class DupNode extends UnaryCoreMethodNode {

        @Specialization
        protected RubyProc dup(RubyProc proc,
                @Cached AllocateHelperNode allocateHelper) {
            final RubyProc copy = new RubyProc(
                    allocateHelper.getCachedShape(Layouts.BASIC_OBJECT.getLogicalClass(proc)),
                    proc.type,
                    proc.sharedMethodInfo,
                    proc.callTargetForType,
                    proc.callTargetForLambdas,
                    proc.declarationFrame,
                    proc.method,
                    proc.block,
                    proc.frameOnStackMarker,
                    proc.declarationContext);

            allocateHelper.trace(copy, this);
            return copy;
        }
    }

    @CoreMethod(names = "arity")
    public abstract static class ArityNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        protected int arity(RubyProc proc) {
            return proc.sharedMethodInfo.getArity().getArityNumber();
        }

    }

    @CoreMethod(names = "binding")
    public abstract static class BindingNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        protected RubyBinding binding(RubyProc proc) {
            final MaterializedFrame frame = proc.declarationFrame;
            final SourceSection sourceSection = proc.sharedMethodInfo.getSourceSection();
            return BindingNodes.createBinding(getContext(), frame, sourceSection);
        }
    }

    @CoreMethod(names = { "call", "[]", "yield" }, rest = true, needsBlock = true)
    public abstract static class CallNode extends CoreMethodArrayArgumentsNode {

        @Child private CallBlockNode callBlockNode = CallBlockNode.create();

        @Specialization
        protected Object call(RubyProc proc, Object[] args, NotProvided block) {
            return callBlockNode.executeCallBlock(
                    proc.declarationContext,
                    proc,
                    ProcOperations.getSelf(proc),
                    null,
                    args);
        }

        @Specialization
        protected Object call(RubyProc proc, Object[] args, RubyProc block) {
            return callBlockNode.executeCallBlock(
                    proc.declarationContext,
                    proc,
                    ProcOperations.getSelf(proc),
                    block,
                    args);
        }

    }

    @CoreMethod(names = "lambda?")
    public abstract static class LambdaNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        protected boolean lambda(RubyProc proc) {
            return proc.type == ProcType.LAMBDA;
        }

    }

    @CoreMethod(names = "parameters")
    public abstract static class ParametersNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        protected RubyArray parameters(RubyProc proc) {
            final ArgumentDescriptor[] argsDesc = proc.sharedMethodInfo.getArgumentDescriptors();
            final boolean isLambda = proc.type == ProcType.LAMBDA;
            return ArgumentDescriptorUtils.argumentDescriptorsToParameters(getContext(), argsDesc, isLambda);
        }

    }

    @CoreMethod(names = "source_location")
    public abstract static class SourceLocationNode extends CoreMethodArrayArgumentsNode {

        @Child private StringNodes.MakeStringNode makeStringNode = StringNodes.MakeStringNode.create();

        @TruffleBoundary
        @Specialization
        protected Object sourceLocation(RubyProc proc) {
            SourceSection sourceSection = proc.sharedMethodInfo.getSourceSection();

            if (!sourceSection.isAvailable() ||
                    RubyContext.getPath(sourceSection.getSource()).endsWith("/lib/truffle/truffle/cext.rb")) {
                return nil;
            } else {
                final DynamicObject file = makeStringNode.executeMake(
                        RubyContext.getPath(sourceSection.getSource()),
                        UTF8Encoding.INSTANCE,
                        CodeRange.CR_UNKNOWN);

                return createArray(new Object[]{ file, sourceSection.getStartLine() });
            }
        }

    }


    @Primitive(name = "proc_create_same_arity")
    public abstract static class ProcCreateSameArityNode extends PrimitiveArrayArgumentsNode {

        @Specialization
        protected RubyProc createSameArityProc(RubyProc userProc, RubyProc block,
                @Cached AllocateHelperNode allocateHelper) {
            final RubyProc composedProc = new RubyProc(
                    coreLibrary().procShape,
                    block.type,
                    userProc.sharedMethodInfo,
                    block.callTargetForType,
                    block.callTargetForLambdas,
                    block.declarationFrame,
                    block.method,
                    block.block,
                    block.frameOnStackMarker,
                    block.declarationContext);
            allocateHelper.trace(composedProc, this);
            return composedProc;
        }
    }

    @Primitive(name = "proc_symbol_to_proc_symbol")
    public abstract static class ProcSymbolToProcSymbolNode extends PrimitiveArrayArgumentsNode {

        @Specialization
        protected Object symbolToProcSymbol(RubyProc proc) {
            if (proc.sharedMethodInfo.getArity() == SymbolNodes.ToProcNode.ARITY) {
                return getSymbol(proc.sharedMethodInfo.getName());
            } else {
                return nil;
            }
        }

    }

    @Primitive(name = "single_block_arg")
    public static abstract class SingleBlockArgNode extends PrimitiveNode {

        @Specialization
        protected Object singleBlockArg(VirtualFrame frame,
                @Cached ConditionProfile emptyArgsProfile,
                @Cached ConditionProfile singleArgProfile) {

            /* In Rubinius, this method inspects the values yielded to the block, regardless of whether the block
             * captures the values, and returns the first value in the list of values yielded to the block.
             *
             * NB: In our case the arguments have already been destructured by the time this node is encountered. Thus,
             * we don't need to do the destructuring work that Rubinius would do and in the case that we receive
             * multiple arguments we need to reverse the destructuring by collecting the values into an array. */
            int userArgumentCount = RubyArguments.getArgumentsCount(frame);

            if (emptyArgsProfile.profile(userArgumentCount == 0)) {
                return nil;
            } else {
                if (singleArgProfile.profile(userArgumentCount == 1)) {
                    return RubyArguments.getArgument(frame, 0);
                } else {
                    Object[] extractedArguments = RubyArguments.getArguments(frame);
                    return createArray(extractedArguments, userArgumentCount);
                }
            }
        }
    }
}
