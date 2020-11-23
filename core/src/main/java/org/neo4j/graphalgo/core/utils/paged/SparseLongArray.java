/*
 * Copyright (c) 2017-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.graphalgo.core.utils.paged;

import com.carrotsearch.hppc.sorting.IndirectSort;
import org.jetbrains.annotations.TestOnly;
import org.neo4j.graphalgo.core.utils.ArrayUtil;
import org.neo4j.graphalgo.core.utils.AscendingLongComparator;
import org.neo4j.graphalgo.core.utils.BitUtil;
import org.neo4j.graphalgo.utils.AutoCloseableThreadLocal;

import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Consumer;

public final class SparseLongArray {

    public static final long NOT_FOUND = -1;

    public static final int BLOCK_SIZE = 64;
    private static final int SUPER_BLOCK_SIZE = BLOCK_SIZE * Long.SIZE;
    private static final int BLOCK_SHIFT = Integer.numberOfTrailingZeros(BLOCK_SIZE);
    private static final int BLOCK_MASK = BLOCK_SIZE - 1;

    // Number of mapped ids.
    private final long idCount;

    // Each element (long) represents a page.
    // Each page represents 64 possible ids.
    private final long[] array;


    // Each block represents BLOCK_SIZE pages.
    // Each value represents the id offset for all ids stored in the block.
    // Id ranges within a block to not overlap with id ranges in other blocks.
    // Block offsets are unordered as their insertion depends on the user of
    // the SLA (e.g. node loading does not insert blocks in a sequential order).
    private final long[] blockOffsets;
    // Sorted representation of block offsets to speed up the lookup for the
    // correct block using binary search.
    private final long[] sortedBlockOffsets;
    // Maps block indices from the sorted offsets to the unsorted offsets.
    private final int[] blockMapping;

    public static int toValidBatchSize(int batchSize) {
        // We need to make sure that we scan aligned to the super block size, as we are not
        // allowed to write into the same block multiple times.
        return (int) BitUtil.align(batchSize, SUPER_BLOCK_SIZE);
    }

    public static Builder builder(long capacity) {
        return new Builder(capacity);
    }

    public static SequentialBuilder sequentialBuilder(long capacity) {
        return new SequentialBuilder(capacity);
    }

    public static FromExistingBuilder fromExistingBuilder(long[] array) {
        return new FromExistingBuilder(array);
    }

    private SparseLongArray(
        long idCount,
        long[] array,
        long[] blockOffsets,
        long[] sortedBlockOffsets,
        int[] blockMapping
    ) {
        this.idCount = idCount;
        this.array = array;
        this.blockOffsets = blockOffsets;
        this.sortedBlockOffsets = sortedBlockOffsets;
        this.blockMapping = blockMapping;
    }

    public long idCount() {
        return idCount;
    }

    public long toMappedNodeId(long originalId) {
        var page = (int) (originalId >>> BLOCK_SHIFT);
        var indexInPage = originalId & BLOCK_MASK;

        // Check if original id is contained
        long mask = 1L << indexInPage;
        if ((mask & array[page]) != mask) {
            return NOT_FOUND;
        }

        var block = page >>> BLOCK_SHIFT;
        // Get the id offset for that block
        var mappedId = blockOffsets[block];
        // Count set bits up to original id
        var a = array;
        // Get count within current block
        for (int pageIdx = page & ~BLOCK_MASK; pageIdx < page; pageIdx++) {
            mappedId += Long.bitCount(a[pageIdx]);
        }
        // tail (long at offset)
        var shift = Long.SIZE - indexInPage - 1;
        mappedId += Long.bitCount(a[page] << shift);

        return mappedId - 1;
    }

    public boolean contains(long originalId) {
        var page = (int) (originalId >>> BLOCK_SHIFT);
        var indexInPage = originalId & BLOCK_MASK;
        // Check if original id is contained
        long mask = 1L << indexInPage;
        return (mask & array[page]) == mask;
    }

    public long toOriginalNodeId(long mappedId) {
        var startBlockIndex = ArrayUtil.binaryLookup(mappedId, sortedBlockOffsets);
        startBlockIndex = blockMapping[startBlockIndex];
        var array = this.array;
        var blockStart = startBlockIndex << BLOCK_SHIFT;
        var blockEnd = Math.min((startBlockIndex + 1) << BLOCK_SHIFT, array.length);
        var originalId = blockOffsets[startBlockIndex];
        for (int blockIndex = blockStart; blockIndex < blockEnd; blockIndex++) {
            var page = array[blockIndex];
            var idsInPage = Long.bitCount(page);
            if (originalId + idsInPage > mappedId) {
                // Perform binary search within the page
                // to find the correct original id.
                var pos = 0;
                long mask = 0xFFFF_FFFFL;
                int shift = 32;

                while (shift > 0) {
                    var idsInLowerPage = Long.bitCount(page & mask);

                    if (originalId + idsInLowerPage > mappedId) {
                        page = page & mask;
                    } else {
                        pos += shift;
                        originalId += idsInLowerPage;
                        page = page >>> shift;
                    }

                    shift >>= 1;
                    mask >>= shift;
                }

                return (((long) blockIndex) << BLOCK_SHIFT) + pos;
            }
            originalId += idsInPage;
        }
        // Returning 0, since this is what the current
        // IdMap implementation returns in that case.
        return 0;
    }

    @TestOnly
    MethodHandles.Lookup lookup() {
        return MethodHandles.lookup();
    }

    public static class Builder {

        private final long[] array;
        private final long[] blockOffsets;

        Builder(long capacity) {
            var size = (int) BitUtil.ceilDiv(capacity, Long.SIZE);
            this.array = new long[size];
            this.blockOffsets = new long[(size >>> BLOCK_SHIFT) + 1];
            // The blocks are initialized with Long.MAX_VALUE to identify
            // which blocks have been written and move unwritten ones to
            // the end during sorting.
            Arrays.fill(blockOffsets, Long.MAX_VALUE);
        }

        @TestOnly
        void set(long allocationIndex, long... originalIds) {
            set(allocationIndex, originalIds, 0, originalIds.length);
        }

        public void set(long allocationIndex, long[] originalIds, int offset, int length) {
            var array = this;
            var prevBlock = -1;
            var prevCount = 0;

            // TODO: Can we find something better than checking at every value?
            for (int i = 0; i < length; i++) {
                var originalId = originalIds[i + offset];
                var block = (int) (originalId >>> 12); // BLOCK_SIZE * Long.SIZE
                if (block != prevBlock) {
                    if (prevBlock != -1) {
                        assert array.blockOffsets[prevBlock] == Long.MAX_VALUE;
                        array.blockOffsets[prevBlock] = prevCount + allocationIndex;
                    }
                    prevBlock = block;
                    prevCount = i;
                }
                array.set(originalId);
            }
            assert array.blockOffsets[prevBlock] == Long.MAX_VALUE;
            array.blockOffsets[prevBlock] = prevCount + allocationIndex;
        }

        public SparseLongArray build() {
            return computeCounts();
        }

        private void set(long originalId) {
            var page = (int) (originalId >>> BLOCK_SHIFT);
            var indexInPage = originalId & BLOCK_MASK;
            var mask = 1L << indexInPage;
            array[page] |= mask;
        }

        private SparseLongArray computeCounts() {
            long[] array = this.array;
            int size = array.length;

            var blockOffsets = this.blockOffsets;
            var blockMapping = IndirectSort.mergesort(
                0,
                blockOffsets.length,
                new AscendingLongComparator(blockOffsets)
            );
            var sortedBlockOffsets = new long[blockOffsets.length];
            Arrays.setAll(sortedBlockOffsets, i -> blockOffsets[blockMapping[i]]);

            int lastSortedBlockOffset = sortedBlockOffsets.length - 1;
            long idCount = 0;
            while (lastSortedBlockOffset > 0) {
                var lastCount = sortedBlockOffsets[lastSortedBlockOffset];
                if (lastCount != Long.MAX_VALUE) {
                    idCount = lastCount;
                    break;
                }
                --lastSortedBlockOffset;
            }

            var lastSortedBlock = blockMapping[lastSortedBlockOffset];
            // Count the remaining ids in the last block.
            var lastBlockBegin = lastSortedBlock << BLOCK_SHIFT;
            var lastBlockEnd = Math.min(size, lastBlockBegin + BLOCK_SIZE);
            for (int page = lastBlockBegin; page < lastBlockEnd; page++) {
                idCount += Long.bitCount(array[page]);
            }

            return new SparseLongArray(idCount, array, blockOffsets, sortedBlockOffsets, blockMapping);
        }
    }

    public static class SequentialBuilder {

        private final AutoCloseableThreadLocal<ThreadLocalBuilder> localBuilders;
        private final SparseLongArrayCombiner combiner;

        SequentialBuilder(long capacity) {
            this.combiner = new SparseLongArrayCombiner(capacity);
            this.localBuilders = new AutoCloseableThreadLocal<>(
                () -> new ThreadLocalBuilder(capacity),
                Optional.of(combiner)
            );
        }

        public void set(long originalId) {
            localBuilders.get().set(originalId);
        }

        public void set(long[] originalIds, int offset, int length) {
            var array = localBuilders.get();
            for (int i = offset; i < offset + length; i++) {
                array.set(originalIds[i]);
            }
        }

        public SparseLongArray build() {
            localBuilders.close();
            return computeCounts(combiner.build().array);
        }

        protected SparseLongArray computeCounts(long[] array) {
            int size = array.length;
            int cappedSize = size - BLOCK_SIZE;
            // blockOffsets[0] is always 0, hence + 1
            long[] blockOffsets = new long[(size >>> BLOCK_SHIFT) + 1];

            long count = 0;
            int block;
            for (block = 0; block < cappedSize; block += BLOCK_SIZE) {
                for (int page = block; page < block + BLOCK_SIZE; page++) {
                    count += Long.bitCount(array[page]);
                }
                blockOffsets[(block >>> BLOCK_SHIFT) + 1] = count;
            }

            // Count the remaining ids in the tail.
            var lastBlockBegin = size - (size & BLOCK_MASK);
            for (int page = lastBlockBegin; page < size; page++) {
                count += Long.bitCount(array[page]);
            }

            // No need to sort as the blocks are already sorted.
            var blockMapping = new int[blockOffsets.length];
            Arrays.setAll(blockMapping, i -> i);

            return new SparseLongArray(count, array, blockOffsets, blockOffsets, blockMapping);
        }

        private static class ThreadLocalBuilder implements AutoCloseable {

            private final long[] array;

            ThreadLocalBuilder(long capacity) {
                var size = (int) BitUtil.ceilDiv(capacity, Long.SIZE);
                this.array = new long[size];
            }

            @Override
            public void close() {
            }

            private void set(long originalId) {
                var page = (int) (originalId >>> BLOCK_SHIFT);
                var indexInPage = originalId & BLOCK_MASK;
                var mask = 1L << indexInPage;
                array[page] |= mask;
            }
        }

        static class SparseLongArrayCombiner implements Consumer<ThreadLocalBuilder> {

            private final long capacity;
            private ThreadLocalBuilder result;

            SparseLongArrayCombiner(long capacity) {
                this.capacity = capacity;
            }

            @Override
            public void accept(ThreadLocalBuilder other) {
                if (result == null) {
                    result = other;
                } else {
                    int size = result.array.length;
                    for (int page = 0; page < size; page++) {
                        result.array[page] |= other.array[page];
                    }
                }
            }

            ThreadLocalBuilder build() {
                return result != null ? result : new ThreadLocalBuilder(capacity);
            }
        }
    }

    public static class FromExistingBuilder extends SequentialBuilder {

        private final long[] array;

        FromExistingBuilder(long[] array) {
            super(array.length);
            this.array = array;
        }

        @Override
        public SparseLongArray build() {
            return computeCounts(array);
        }
    }

}