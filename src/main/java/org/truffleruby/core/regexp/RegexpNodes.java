/*
 * Copyright (c) 2016, 2019 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
/*
 * Copyright (c) 2013, 2015, 2016 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.regexp;

import java.util.Arrays;
import java.util.Iterator;

import org.jcodings.Encoding;
import org.jcodings.specific.USASCIIEncoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.Matcher;
import org.joni.NameEntry;
import org.joni.Regex;
import org.joni.Region;
import org.truffleruby.RubyContext;
import org.truffleruby.builtins.CoreMethod;
import org.truffleruby.builtins.CoreMethodArrayArgumentsNode;
import org.truffleruby.builtins.CoreModule;
import org.truffleruby.builtins.NonStandard;
import org.truffleruby.builtins.Primitive;
import org.truffleruby.builtins.PrimitiveArrayArgumentsNode;
import org.truffleruby.core.array.ArrayHelpers;
import org.truffleruby.core.array.RubyArray;
import org.truffleruby.core.cast.ToStrNode;
import org.truffleruby.core.encoding.EncodingNodes;
import org.truffleruby.core.regexp.RegexpNodesFactory.ToSNodeFactory;
import org.truffleruby.core.rope.CodeRange;
import org.truffleruby.core.rope.Rope;
import org.truffleruby.core.rope.RopeBuilder;
import org.truffleruby.core.rope.RopeNodes;
import org.truffleruby.core.string.RubyString;
import org.truffleruby.core.string.StringNodes;
import org.truffleruby.core.string.StringUtils;
import org.truffleruby.core.symbol.RubySymbol;
import org.truffleruby.language.RubyGuards;
import org.truffleruby.language.Visibility;
import org.truffleruby.language.control.RaiseException;
import org.truffleruby.language.objects.AllocateHelperNode;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.api.profiles.ConditionProfile;

@CoreModule(value = "Regexp", isClass = true)
public abstract class RegexpNodes {

    @TruffleBoundary
    public static Matcher createMatcher(RubyContext context, RubyRegexp regexp, Rope stringRope, byte[] stringBytes,
            boolean encodingConversion, int start) {
        final Encoding enc = checkEncoding(regexp, stringRope.getEncoding(), stringRope.getCodeRange(), true);
        Regex regex = regexp.regex;

        if (encodingConversion && regex.getEncoding() != enc) {
            EncodingCache encodingCache = regexp.cachedEncodings;
            regex = encodingCache.getOrCreate(enc, e -> makeRegexpForEncoding(context, regexp, e));
        }

        return regex.matcher(stringBytes, start, stringBytes.length);
    }

    private static Regex makeRegexpForEncoding(RubyContext context, RubyRegexp regexp, final Encoding enc) {
        Regex regex;
        final Encoding[] fixedEnc = new Encoding[]{ null };
        final Rope sourceRope = regexp.source;
        final RopeBuilder preprocessed = ClassicRegexp
                .preprocess(context, sourceRope, enc, fixedEnc, RegexpSupport.ErrorMode.RAISE);
        final RegexpOptions options = regexp.options;
        regex = new Regex(
                preprocessed.getUnsafeBytes(),
                0,
                preprocessed.getLength(),
                options.toJoniOptions(),
                enc,
                new RegexWarnCallback(context));
        return regex;
    }

    @TruffleBoundary
    public static Encoding checkEncoding(RubyRegexp regexp, Encoding strEnc, CodeRange codeRange, boolean warn) {
        assert RubyGuards.isRubyRegexp(regexp);

        final Encoding regexEnc = regexp.regex.getEncoding();

        if (strEnc == regexEnc) {
            return regexEnc;
        } else if (regexEnc == USASCIIEncoding.INSTANCE && codeRange == CodeRange.CR_7BIT) {
            return regexEnc;
        } else if (strEnc.isAsciiCompatible() && regexp.options.isFixed()) {
            return regexEnc;
        }
        return strEnc;
    }

    public static void initialize(RubyContext context, RubyRegexp regexp, Rope setSource, int options,
            Node currentNode) {
        final RegexpOptions regexpOptions = RegexpOptions.fromEmbeddedOptions(options);
        final Regex regex = TruffleRegexpNodes.compile(context, setSource, regexpOptions, currentNode);

        // The RegexpNodes.compile operation may modify the encoding of the source rope. This modified copy is stored
        // in the Regex object as the "user object". Since ropes are immutable, we need to take this updated copy when
        // constructing the final regexp.
        regexp.source = (Rope) regex.getUserObject();
        regexp.options = regexpOptions;
        regexp.regex = regex;
        regexp.cachedEncodings = new EncodingCache();
    }

    public static RubyRegexp createRubyRegexp(Shape shape, Regex regex, Rope source, RegexpOptions options,
            EncodingCache cache) {
        return new RubyRegexp(shape, regex, source, options, cache);
    }

    @CoreMethod(names = "hash")
    public abstract static class HashNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        protected int hash(RubyRegexp regexp) {
            int options = regexp.regex.getOptions() &
                    ~32 /* option n, NO_ENCODING in common/regexp.rb */;
            return options ^ regexp.source.hashCode();
        }

    }

    @NonStandard
    @CoreMethod(names = "match_onwards", required = 3, lowerFixnum = 2)
    public abstract static class MatchOnwardsNode extends CoreMethodArrayArgumentsNode {

        @Child private TruffleRegexpNodes.MatchNode matchNode = TruffleRegexpNodes.MatchNode.create();
        @Child private RopeNodes.BytesNode bytesNode = RopeNodes.BytesNode.create();

        @Specialization
        protected Object matchOnwards(RubyRegexp regexp, RubyString string, int startPos, boolean atStart) {
            final Rope rope = string.rope;
            final Matcher matcher = createMatcher(getContext(), regexp, rope, bytesNode.execute(rope), true, startPos);
            int range = rope.byteLength();
            Object result = matchNode.execute(regexp, string, matcher, startPos, range, atStart);
            if (result != nil) {
                fixupMatchDataForStart((RubyMatchData) result, startPos);
            }
            return result;
        }
    }

    @CoreMethod(names = { "quote", "escape" }, onSingleton = true, required = 1)
    public abstract static class QuoteNode extends CoreMethodArrayArgumentsNode {

        @Child private StringNodes.MakeStringNode makeStringNode;
        @Child private ToStrNode toStrNode;

        @TruffleBoundary
        @Specialization
        protected DynamicObject quoteString(RubyString raw) {
            final Rope rope = raw.rope;
            return getMakeStringNode().fromRope(ClassicRegexp.quote19(rope));
        }

        @Specialization
        protected DynamicObject quoteSymbol(RubySymbol raw) {
            return quoteString(
                    getMakeStringNode()
                            .executeMake(raw.getString(), UTF8Encoding.INSTANCE, CodeRange.CR_UNKNOWN));
        }

        @Fallback
        protected DynamicObject quote(VirtualFrame frame, Object raw) {
            if (toStrNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                toStrNode = insert(ToStrNode.create());
            }

            return quoteString(toStrNode.executeToStr(frame, raw));
        }

        private StringNodes.MakeStringNode getMakeStringNode() {
            if (makeStringNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                makeStringNode = insert(StringNodes.MakeStringNode.create());
            }

            return makeStringNode;
        }
    }

    @TruffleBoundary
    private static DynamicObject fixupMatchDataForStart(RubyMatchData matchData, int startPos) {
        Region regs = matchData.region;
        if (startPos != 0) {
            for (int i = 0; i < regs.beg.length; i++) {
                if (regs.beg[i] != -1) {
                    regs.beg[i] += startPos;
                    regs.end[i] += startPos;
                }
            }
        }
        return matchData;
    }

    @Primitive(name = "regexp_search_from_binary", lowerFixnum = 2)
    public abstract static class SearchFromBinaryNode extends CoreMethodArrayArgumentsNode {

        @Child private TruffleRegexpNodes.MatchNode matchNode = TruffleRegexpNodes.MatchNode.create();
        @Child private RopeNodes.BytesNode bytesNode = RopeNodes.BytesNode.create();

        @Specialization
        protected Object searchFrom(RubyRegexp regexp, RubyString string, int startPos) {
            final Rope rope = string.rope;
            final Matcher matcher = createMatcher(getContext(), regexp, rope, bytesNode.execute(rope), false, startPos);
            final int endPos = rope.byteLength();
            Object result = matchNode.execute(regexp, string, matcher, startPos, endPos, false);
            if (result != nil) {
                fixupMatchDataForStart((RubyMatchData) result, startPos);
            }
            return result;
        }
    }

    @CoreMethod(names = "source")
    public abstract static class SourceNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        protected DynamicObject source(RubyRegexp regexp,
                @Cached StringNodes.MakeStringNode makeStringNode) {
            return makeStringNode.fromRope(regexp.source);
        }

    }

    @CoreMethod(names = "to_s")
    @ImportStatic(RegexpGuards.class)
    public abstract static class ToSNode extends CoreMethodArrayArgumentsNode {

        @Child private StringNodes.MakeStringNode makeStringNode = StringNodes.MakeStringNode.create();

        public static ToSNode create() {
            return ToSNodeFactory.create(null);
        }

        public abstract DynamicObject execute(RubyRegexp regexp);

        @Specialization(guards = "isSameRegexp(regexp, cachedRegexp)")
        protected DynamicObject toSCached(RubyRegexp regexp,
                @Cached("regexp") RubyRegexp cachedRegexp,
                @Cached("createRope(cachedRegexp)") Rope rope) {
            return makeStringNode.fromRope(rope);
        }

        @Specialization
        protected DynamicObject toS(RubyRegexp regexp) {
            final Rope rope = createRope(regexp);
            return makeStringNode.fromRope(rope);
        }

        @TruffleBoundary
        protected Rope createRope(RubyRegexp regexp) {
            final ClassicRegexp classicRegexp = new ClassicRegexp(
                    getContext(),
                    regexp.source,
                    RegexpOptions.fromEmbeddedOptions(regexp.regex.getOptions()));
            return classicRegexp.toRopeBuilder().toRope();
        }
    }

    @Primitive(name = "regexp_names")
    public abstract static class RegexpNamesNode extends PrimitiveArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        protected RubyArray regexpNames(RubyRegexp regexp) {
            final int size = regexp.regex.numberOfNames();
            if (size == 0) {
                return ArrayHelpers.createEmptyArray(getContext());
            }

            final Object[] names = new Object[size];
            int i = 0;
            for (final Iterator<NameEntry> iter = regexp.regex.namedBackrefIterator(); iter
                    .hasNext();) {
                final NameEntry e = iter.next();
                final byte[] bytes = Arrays.copyOfRange(e.name, e.nameP, e.nameEnd);

                final Rope rope = getContext()
                        .getRopeCache()
                        .getRope(bytes, UTF8Encoding.INSTANCE, CodeRange.CR_UNKNOWN);
                final RubySymbol name = getContext().getSymbol(rope);

                final int[] backrefs = e.getBackRefs();
                final DynamicObject backrefsRubyArray = createArray(backrefs);
                names[i++] = createArray(new Object[]{ name, backrefsRubyArray });
            }

            return createArray(names);
        }

    }

    @CoreMethod(names = { "__allocate__", "__layout_allocate__" }, constructor = true, visibility = Visibility.PRIVATE)
    public abstract static class AllocateNode extends CoreMethodArrayArgumentsNode {

        @Child private AllocateHelperNode allocateNode = AllocateHelperNode.create();

        @Specialization
        protected DynamicObject allocate(DynamicObject rubyClass) {
            RubyRegexp regexp = new RubyRegexp(
                    allocateNode.getCachedShape(rubyClass),
                    null,
                    null,
                    RegexpOptions.NULL_OPTIONS,
                    null);
            allocateNode.trace(regexp, this);
            return regexp;
        }

    }

    @CoreMethod(names = "fixed_encoding?")
    public static abstract class RegexpIsFixedEncodingNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        protected boolean fixedEncoding(RubyRegexp regexp) {
            return regexp.options.isFixed();
        }

    }

    @CoreMethod(names = "compile", required = 2, lowerFixnum = 2, visibility = Visibility.PRIVATE)
    @ImportStatic(RegexpGuards.class)
    public static abstract class RegexpCompileNode extends CoreMethodArrayArgumentsNode {

        @Specialization(guards = { "isRegexpLiteral(regexp)" })
        protected DynamicObject initializeRegexpLiteral(RubyRegexp regexp, RubyString pattern, int options) {
            throw new RaiseException(getContext(), coreExceptions().securityError("can't modify literal regexp", this));
        }

        @Specialization(guards = { "!isRegexpLiteral(regexp)", "isInitialized(regexp)" })
        protected DynamicObject initializeAlreadyInitialized(RubyRegexp regexp, RubyString pattern, int options) {
            throw new RaiseException(getContext(), coreExceptions().typeError("already initialized regexp", this));
        }

        @Specialization(guards = { "!isRegexpLiteral(regexp)", "!isInitialized(regexp)" })
        protected DynamicObject initialize(RubyRegexp regexp, RubyString pattern, int options) {
            RegexpNodes.initialize(getContext(), regexp, pattern.rope, options, this);
            return regexp;
        }
    }

    @CoreMethod(names = "initialize_copy", required = 1, needsSelf = true)
    @ImportStatic(RegexpGuards.class)
    public static abstract class RegexpInitializeCopyNode extends CoreMethodArrayArgumentsNode {

        @Specialization(guards = "isRegexpLiteral(regexp)")
        protected DynamicObject initializeRegexpLiteral(RubyRegexp regexp, RubyRegexp other) {
            throw new RaiseException(getContext(), coreExceptions().securityError("can't modify literal regexp", this));
        }

        @Specialization(guards = { "!isRegexpLiteral(regexp)", "isInitialized(regexp)" })
        protected DynamicObject initializeAlreadyInitialized(RubyRegexp regexp, RubyRegexp other) {
            throw new RaiseException(getContext(), coreExceptions().typeError("already initialized regexp", this));
        }

        @Specialization(guards = { "!isRegexpLiteral(regexp)", "!isInitialized(regexp)" })
        protected DynamicObject initialize(RubyRegexp regexp, RubyRegexp other) {
            regexp.regex = other.regex;
            regexp.source = other.source;
            regexp.options = other.options;
            regexp.cachedEncodings = other.cachedEncodings;
            return regexp;
        }
    }

    @CoreMethod(names = "options")
    @ImportStatic(RegexpGuards.class)
    public static abstract class RegexpOptionsNode extends CoreMethodArrayArgumentsNode {

        @Specialization(guards = "isInitialized(regexp)")
        protected int options(RubyRegexp regexp) {
            return regexp.options.toOptions();
        }

        @Specialization(guards = "!isInitialized(regexp)")
        protected int optionsNotInitialized(RubyRegexp regexp) {
            throw new RaiseException(getContext(), coreExceptions().typeError("uninitialized Regexp", this));
        }

    }

    @CoreMethod(names = "search_region", required = 4, lowerFixnum = { 2, 3 })
    @ImportStatic(RegexpGuards.class)
    public static abstract class RegexpSearchRegionNode extends CoreMethodArrayArgumentsNode {

        @Child RopeNodes.CodeRangeNode rangeNode = RopeNodes.CodeRangeNode.create();

        @Specialization(guards = { "!isInitialized(regexp)" })
        protected Object notInitialized(RubyRegexp regexp, RubyString string, int start, int end, boolean forward) {
            throw new RaiseException(getContext(), coreExceptions().typeError("uninitialized Regexp", this));
        }

        @Specialization(guards = { "!isValidEncoding(string, rangeNode)" })
        protected Object invalidEncoding(RubyRegexp regexp, RubyString string, int start, int end, boolean forward) {
            throw new RaiseException(getContext(), coreExceptions().argumentError(formatError(string), this));
        }

        @TruffleBoundary
        private String formatError(RubyString string) {
            return StringUtils.format("invalid byte sequence in %s", string.rope.getEncoding());
        }

        @Specialization(
                guards = { "isInitialized(regexp)", "isValidEncoding(string, rangeNode)" })
        protected Object searchRegion(RubyRegexp regexp, RubyString string, int start, int end, boolean forward,
                @Cached ConditionProfile forwardSearchProfile,
                @Cached RopeNodes.BytesNode bytesNode,
                @Cached TruffleRegexpNodes.MatchNode matchNode,
                @Cached EncodingNodes.CheckEncodingNode checkEncodingNode) {
            checkEncodingNode.executeCheckEncoding(regexp, string);

            final Rope rope = string.rope;
            final Matcher matcher = RegexpNodes
                    .createMatcher(getContext(), regexp, rope, bytesNode.execute(rope), true, 0);

            if (forwardSearchProfile.profile(forward)) {
                // Search forward through the string.
                return matchNode.execute(regexp, string, matcher, start, end, false);
            } else {
                // Search backward through the string.
                return matchNode.execute(regexp, string, matcher, end, start, false);
            }
        }

    }
}
