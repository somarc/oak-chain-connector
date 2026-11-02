package com.oakchain.connector.mount;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.plugins.memory.MemoryChildNodeEntry;
import org.apache.jackrabbit.oak.plugins.memory.PropertyStates;
import org.apache.jackrabbit.oak.spi.state.AbstractNodeState;
import org.apache.jackrabbit.oak.spi.state.ChildNodeEntry;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.state.NodeStateDiff;
import org.apache.jackrabbit.oak.spi.state.ReadOnlyBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The mounted validator tree as valid JCR: a node without {@code jcr:primaryType} reads as
 * {@code oak:Unstructured}.
 *
 * <p>JCR requires a primary type on every node, and Oak's JCR layer throws for a node without
 * one. Validators before genesis 2.2.0 wrote shard, wallet {@code content} and organization
 * folders without it, and that history is immutable, so the mount supplies the type genesis
 * 2.2.0 gives those folders instead of AEM logging an ERROR with a stack trace for every
 * ancestor of every read. On a 2.2.0 chain every node is typed and this changes nothing.
 *
 * <p>Diffs between two decorated states delegate to the segment store, so unchanged subtrees
 * keep Oak's record-id fast path; the synthesized property never shows up as a change.
 */
final class TypedNodeState extends AbstractNodeState {

    static final String JCR_PRIMARYTYPE = "jcr:primaryType";
    static final PropertyState DEFAULT_TYPE = PropertyStates.createProperty(JCR_PRIMARYTYPE, "oak:Unstructured", Type.NAME);

    private final NodeState delegate;

    private TypedNodeState(NodeState delegate) {
        this.delegate = delegate;
    }

    static NodeState wrap(NodeState state) {
        return state instanceof TypedNodeState || !state.exists() ? state : new TypedNodeState(state);
    }

    /**
     * Whether the node has its own primary type. Not {@code hasProperty}: Oak 2.4.0's
     * SegmentNodeState answers that only for a NAME-typed primary type, while validators write
     * a STRING one, which {@code getProperty} does find.
     */
    private boolean typed() {
        return delegate.getProperty(JCR_PRIMARYTYPE) != null;
    }

    @Override
    public boolean exists() {
        return delegate.exists();
    }

    @Override
    public boolean hasProperty(@NotNull String name) {
        return JCR_PRIMARYTYPE.equals(name) || delegate.hasProperty(name);
    }

    @Override
    public @Nullable PropertyState getProperty(@NotNull String name) {
        PropertyState p = delegate.getProperty(name);
        return p == null && JCR_PRIMARYTYPE.equals(name) ? DEFAULT_TYPE : p;
    }

    @Override
    public long getPropertyCount() {
        return delegate.getPropertyCount() + (typed() ? 0 : 1);
    }

    @Override
    public @NotNull Iterable<? extends PropertyState> getProperties() {
        if (typed()) {
            return delegate.getProperties();
        }
        List<PropertyState> all = new ArrayList<>();
        delegate.getProperties().forEach(all::add);
        all.add(DEFAULT_TYPE);
        return all;
    }

    @Override
    public boolean hasChildNode(@NotNull String name) {
        return delegate.hasChildNode(name);
    }

    @Override
    public @NotNull NodeState getChildNode(@NotNull String name) {
        return wrap(delegate.getChildNode(name));
    }

    @Override
    public long getChildNodeCount(long max) {
        return delegate.getChildNodeCount(max);
    }

    @Override
    public @NotNull Iterable<String> getChildNodeNames() {
        return delegate.getChildNodeNames();
    }

    @Override
    public @NotNull Iterable<? extends ChildNodeEntry> getChildNodeEntries() {
        return () -> {
            Iterator<? extends ChildNodeEntry> it = delegate.getChildNodeEntries().iterator();
            return new Iterator<ChildNodeEntry>() {
                @Override
                public boolean hasNext() {
                    return it.hasNext();
                }

                @Override
                public ChildNodeEntry next() {
                    ChildNodeEntry e = it.next();
                    return new MemoryChildNodeEntry(e.getName(), wrap(e.getNodeState()));
                }
            };
        };
    }

    @Override
    public @NotNull NodeBuilder builder() {
        return new ReadOnlyBuilder(this);
    }

    @Override
    public boolean compareAgainstBaseState(NodeState base, NodeStateDiff diff) {
        if (base instanceof TypedNodeState) {
            return delegate.compareAgainstBaseState(((TypedNodeState) base).delegate, new TypingDiff(diff));
        }
        return AbstractNodeState.compareAgainstBaseState(this, base, diff);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other instanceof TypedNodeState) {
            return delegate.equals(((TypedNodeState) other).delegate);
        }
        return super.equals(other);
    }

    @Override
    public int hashCode() {
        return 0; // like AbstractNodeState: node states are compared by content
    }

    /** Re-applies the decoration to a delegate diff: children wrapped, the default type invisible. */
    private static final class TypingDiff implements NodeStateDiff {

        private final NodeStateDiff diff;

        TypingDiff(NodeStateDiff diff) {
            this.diff = diff;
        }

        @Override
        public boolean propertyAdded(PropertyState after) {
            if (JCR_PRIMARYTYPE.equals(after.getName())) { // before: the default
                return after.equals(DEFAULT_TYPE) || diff.propertyChanged(DEFAULT_TYPE, after);
            }
            return diff.propertyAdded(after);
        }

        @Override
        public boolean propertyChanged(PropertyState before, PropertyState after) {
            return diff.propertyChanged(before, after);
        }

        @Override
        public boolean propertyDeleted(PropertyState before) {
            if (JCR_PRIMARYTYPE.equals(before.getName())) { // after: the default
                return before.equals(DEFAULT_TYPE) || diff.propertyChanged(before, DEFAULT_TYPE);
            }
            return diff.propertyDeleted(before);
        }

        @Override
        public boolean childNodeAdded(String name, NodeState after) {
            return diff.childNodeAdded(name, wrap(after));
        }

        @Override
        public boolean childNodeChanged(String name, NodeState before, NodeState after) {
            return diff.childNodeChanged(name, wrap(before), wrap(after));
        }

        @Override
        public boolean childNodeDeleted(String name, NodeState before) {
            return diff.childNodeDeleted(name, wrap(before));
        }
    }
}
