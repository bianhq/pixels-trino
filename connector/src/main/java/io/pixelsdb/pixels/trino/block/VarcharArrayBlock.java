/*
 * Copyright 2022 PixelsDB.
 *
 * This file is part of Pixels.
 *
 * Pixels is free software: you can redistribute it and/or modify
 * it under the terms of the Affero GNU General Public License as
 * published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * Pixels is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Affero GNU General Public License for more details.
 *
 * You should have received a copy of the Affero GNU General Public
 * License along with Pixels.  If not, see
 * <https://www.gnu.org/licenses/>.
 */
package io.pixelsdb.pixels.trino.block;

import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.trino.spi.block.Block;
import io.trino.spi.block.ByteArrayBlock;
import io.trino.spi.block.ValueBlock;
import org.openjdk.jol.info.ClassLayout;

import java.util.*;
import java.util.function.ObjLongConsumer;

import static io.airlift.slice.SizeOf.sizeOf;
import static io.pixelsdb.pixels.trino.block.BlockUtil.copyIsNullAndAppendNull;
import static io.pixelsdb.pixels.trino.block.BlockUtil.copyOffsetsAndAppendNull;

/**
 * This class is derived from io.trino.spi.block.VariableWidthBlock and AbstractVariableWidthBlock.
 * <p>
 * Our main modifications:
 * 1. we use a byte[][] instead of Slice as the backing storage
 * and replaced the implementation of each method;
 * 2. add some other methods.
 * <p>
 *
 * @author hank
 * @create 2019-05
 * @update 2024-12-01 adapt to with Trino 465 and add hasNull argument to the constructor.
 */
public class VarcharArrayBlock implements ValueBlock
{
    private static final long INSTANCE_SIZE = ClassLayout.parseClass(VarcharArrayBlock.class).instanceSize();

    private final int arrayOffset; // start index of the valid items in offsets and length, usually 0.
    private final int positionCount; // number of items in this block.
    private final byte[][] values; // values of the items.
    private final int[] offsets; // start byte offset of the item in each value, \
    // always 0 if this block is deserialized by VarcharArrayBlockEncoding.readBlock.
    private final int[] lengths; // byte length of each item.
    private final boolean[] valueIsNull; // isNull flag of each item.
    private final boolean hasNull;

    private final long retainedSizeInBytes;
    /**
     * PIXELS-167:
     * The actual memory footprint of the member values.
     */
    private final long retainedSizeOfValues;
    private final long sizeInBytes;

    public VarcharArrayBlock(int positionCount, byte[][] values, int[] offsets, int[] lengths, boolean hasNull, boolean[] valueIsNull)
    {
        this(0, positionCount, values, offsets, lengths, hasNull, valueIsNull);
    }

    VarcharArrayBlock(int arrayOffset, int positionCount, byte[][] values, int[] offsets, int[] lengths, boolean hasNull, boolean[] valueIsNull)
    {
        if (arrayOffset < 0)
        {
            throw new IllegalArgumentException("arrayOffset is negative");
        }
        this.arrayOffset = arrayOffset;
        if (positionCount < 0)
        {
            throw new IllegalArgumentException("positionCount is negative");
        }
        this.positionCount = positionCount;

        if (values == null || values.length - arrayOffset < (positionCount))
        {
            throw new IllegalArgumentException("values is null or its length is less than positionCount");
        }
        this.values = values;

        if (offsets == null || offsets.length - arrayOffset < (positionCount))
        {
            throw new IllegalArgumentException("offsets is null or its length is less than positionCount");
        }
        this.offsets = offsets;

        if (lengths == null || lengths.length - arrayOffset < (positionCount))
        {
            throw new IllegalArgumentException("lengths is null or its length is less than positionCount");
        }
        this.lengths = lengths;

        this.hasNull = hasNull;
        // Issue #123: in Pixels, the isNull bitmap from column vectors always presents even if there is no nulls.
        if (valueIsNull == null || valueIsNull.length - arrayOffset < positionCount)
        {
            throw new IllegalArgumentException("valueIsNull is null or its length is less than positionCount");
        }
        this.valueIsNull = valueIsNull;

        long size = 0L, retainedSize = 0L;
        Set<byte[]> existingValues = new HashSet<>(2);
        for (int i = 0; i < positionCount; ++i)
        {
            size += lengths[arrayOffset + i];
            // retainedSize should count the physical footprint of the values.
            if (!valueIsNull[arrayOffset + i])
            {
                if (!existingValues.contains(values[arrayOffset + i]))
                {
                    existingValues.add(values[arrayOffset + i]);
                    retainedSize += values[arrayOffset + i].length;
                }
            }
        }
        existingValues.clear();
        sizeInBytes = size;
        retainedSizeOfValues = retainedSize + sizeOf(values);
        retainedSizeInBytes = INSTANCE_SIZE + retainedSizeOfValues +
                sizeOf(valueIsNull) + sizeOf(offsets) + sizeOf(lengths);
    }

    /**
     * Gets the start offset of the value at the {@code position}.
     */
    protected final int getPositionOffset(int position)
    {
        /**
         * PIXELS-132:
         * FIX: null must be checked here as offsets (i.e. starts) in column vector
         * may be reused in vectorized row batch and is not reset.
         */
        if (hasNull && valueIsNull[position + arrayOffset])
        {
            return 0;
        }
        return offsets[position + arrayOffset];
    }

    /**
     * Gets the length of the value at the {@code position}.
     * This method must be implemented if @{code getSlice} is implemented.
     */
    protected int getSliceLength(int position)
    {
        checkReadablePosition(position);
        /**
         * PIXELS-132:
         * FIX: null must be checked here as lengths (i.e. lens) in column vector
         * may be reused in vectorized row batch and is not reset.
         */
        if (hasNull && valueIsNull[position + arrayOffset])
        {
            return 0;
        }
        return lengths[position + arrayOffset];
    }

    @Override
    public int getPositionCount()
    {
        return positionCount;
    }

    @Override
    public long getSizeInBytes()
    {
        return sizeInBytes;
    }

    /**
     * Returns the logical size of {@code block.getRegion(position, length)} in memory.
     * The method can be expensive. Do not use it outside an implementation of Block.
     */
    @Override
    public long getRegionSizeInBytes(int position, int length)
    {
        BlockUtil.checkValidRegion(getPositionCount(), position, length);
        long size = 0L;
        for (int i = 0; i < length; ++i)
        {
            // lengths[i] is zero if valueIsNull[i] is true, no need to check.
            size += lengths[position + arrayOffset + i];
        }
        return size + ((Integer.BYTES * 2 + Byte.BYTES) * (long) length);
    }

    @Override
    public OptionalInt fixedSizeInBytesPerPosition()
    {
        return OptionalInt.empty(); // size varies per element and is not fixed
    }

    /**
     * Returns the size of all positions marked true in the positions array.
     * This is equivalent to multiple calls of {@code block.getRegionSizeInBytes(position, length)}
     * where you mark all positions for the regions first.
     *
     * @param positions
     */
    @Override
    public long getPositionsSizeInBytes(boolean[] positions, int _selectedPositionsCount)
    {
        long sizeInBytes = 0;
        int usedPositionCount = 0;
        for (int i = 0; i < positions.length; ++i)
        {
            if (positions[i])
            {
                usedPositionCount++;
                sizeInBytes += lengths[arrayOffset+i];
            }
        }
        return sizeInBytes + (Integer.BYTES * 2 + Byte.BYTES) * (long) usedPositionCount;
    }

    /**
     * Returns the retained size of this block in memory.
     * This method is called from the innermost execution loop and must be fast.
     */
    @Override
    public long getRetainedSizeInBytes()
    {
        return retainedSizeInBytes;
    }

    /**
     * Returns the estimated in memory data size for stats of position.
     * Do not use it for other purpose.
     *
     * @param position
     */
    @Override
    public long getEstimatedDataSizeForStats(int position)
    {
        return isNull(position) ? 0 : getSliceLength(position);
    }

    /**
     * {@code consumer} visits each of the internal data container and accepts the size for it.
     * This method can be helpful in cases such as memory counting for internal data structure.
     * Also, the method should be non-recursive, only visit the elements at the top level,
     * and specifically should not call retainedBytesForEachPart on nested blocks
     * {@code consumer} should be called at least once with the current block and
     * must include the instance size of the current block
     */
    @Override
    public void retainedBytesForEachPart(ObjLongConsumer<Object> consumer)
    {
        /**
         * PIXELS-167:
         * DO NOT calculate the retained size of values by adding up values[i].length.
         */
        consumer.accept(values, retainedSizeOfValues);
        consumer.accept(offsets, sizeOf(offsets));
        consumer.accept(lengths, sizeOf(lengths));
        consumer.accept(valueIsNull, sizeOf(valueIsNull));
        consumer.accept(this, INSTANCE_SIZE);
    }

    /**
     * Returns a block containing the specified positions.
     * Positions to copy are stored in a subarray within {@code positions} array
     * that starts at {@code offset} and has length of {@code length}.
     * All specified positions must be valid for this block.
     * <p>
     * The returned block must be a compact representation of the original block.
     */
    @Override
    public VarcharArrayBlock copyPositions(int[] positions, int offset, int length)
    {
        BlockUtil.checkArrayRange(positions, offset, length);
        byte[][] newValues = new byte[length][];
        int[] newStarts = new int[length];
        int[] newLengths = new int[length];
        boolean newHasNull = false;
        boolean[] newValueIsNull = new boolean[length];

        for (int i = 0; i < length; i++)
        {
            int position = positions[offset + i];
            if (hasNull && valueIsNull[position + arrayOffset])
            {
                newValueIsNull[i] = true;
                newHasNull = true;
            }
            else
            {
                // we only copy the valid part of each value.
                int from = offsets[position + arrayOffset];
                newLengths[i] = lengths[position + arrayOffset];
                newValues[i] = Arrays.copyOfRange(values[position + arrayOffset],
                        from, from + newLengths[i]);
                // newStarts is 0.
            }
        }
        return new VarcharArrayBlock(length, newValues, newStarts, newLengths, newHasNull, newValueIsNull);
    }

    protected Slice getRawSlice(int position)
    {
        // DO NOT specify the offset and length for wrappedBuffer,
        // a raw slice should contain the whole bytes of value at the position.
        if (hasNull && valueIsNull[position + arrayOffset])
        {
            return Slices.EMPTY_SLICE;
        }
        return Slices.wrappedBuffer(values[position + arrayOffset]);
    }

    protected byte[] getRawValue(int position)
    {
        if (hasNull && valueIsNull[position + arrayOffset])
        {
            return null;
        }
        return values[position + arrayOffset];
    }

    /**
     * Returns a block starting at the specified position and extends for the
     * specified length. The specified region must be entirely contained
     * within this block.
     * <p>
     * The region can be a view over this block.  If this block is released
     * the region block may also be released.  If the region block is released
     * this block may also be released.
     */
    @Override
    public VarcharArrayBlock getRegion(int positionOffset, int length)
    {
        BlockUtil.checkValidRegion(getPositionCount(), positionOffset, length);

        boolean newHasNull = false;
        if (hasNull)
        {
            for (int i = 0; i < length; ++i)
            {
                if (valueIsNull[i + arrayOffset])
                {
                    newHasNull = true;
                    break;
                }
            }
        }
        return new VarcharArrayBlock(positionOffset + arrayOffset, length, values, offsets, lengths, newHasNull, valueIsNull);
    }

    /**
     * Gets the value at the specified position as a single element block.  The method
     * must copy the data into a new block.
     * <p>
     * This method is useful for operators that hold on to a single value without
     * holding on to the entire block.
     *
     * @throws IllegalArgumentException if this position is not valid
     */
    @Override
    public VarcharArrayBlock getSingleValueBlock(int position)
    {
        checkReadablePosition(position);
        byte[][] copy = new byte[1][];
        if (isNull(position))
        {
            return new VarcharArrayBlock(1, copy, new int[]{0}, new int[]{0}, true, new boolean[]{true});
        }

        int offset = offsets[position + arrayOffset];
        int entrySize = lengths[position + arrayOffset];
        copy[0] = Arrays.copyOfRange(values[position + arrayOffset],
                offset, offset + entrySize);

        return new VarcharArrayBlock(1, copy, new int[]{0}, new int[]{entrySize}, false, new boolean[]{false});
    }

    /**
     * Returns a block starting at the specified position and extends for the
     * specified length.  The specified region must be entirely contained
     * within this block.
     * <p>
     * The region returned must be a compact representation of the original block, unless their internal
     * representation will be exactly the same. This method is useful for
     * operators that hold on to a range of values without holding on to the
     * entire block.
     */
    @Override
    public VarcharArrayBlock copyRegion(int positionOffset, int length)
    {
        BlockUtil.checkValidRegion(getPositionCount(), positionOffset, length);
        positionOffset += arrayOffset;

        byte[][] newValues = new byte[length][];
        int[] newStarts = new int[length];
        int[] newLengths = new int[length];
        boolean newHasNull = false;
        boolean[] newValueIsNull = new boolean[length];

        for (int i = 0; i < length; i++)
        {
            if (hasNull && valueIsNull[positionOffset + i])
            {
                newValueIsNull[i] = true;
                newHasNull = true;
            } else
            {
                // we only copy the valid part of each value.
                newLengths[i] = lengths[positionOffset + i];
                newValues[i] = Arrays.copyOfRange(values[positionOffset + i],
                        offsets[positionOffset + i], offsets[positionOffset + i] + newLengths[i]);
                // newStarts is 0.
            }
        }
        return new VarcharArrayBlock(length, newValues, newStarts, newLengths, newHasNull, newValueIsNull);
    }

    @Override
    public String getEncodingName()
    {
        return VarcharArrayBlockEncoding.NAME;
    }

    @Override
    public boolean isNull(int position)
    {
        checkReadablePosition(position);
        return hasNull && valueIsNull[position + arrayOffset];
    }

    /**
     * Returns a block that contains a copy of the contents of the current block, and an appended null at the end. The
     * original block will not be modified. The purpose of this method is to leverage the contents of a block and the
     * structure of the implementation to efficiently produce a copy of the block with a NULL element inserted - so that
     * it can be used as a dictionary. This method is expected to be invoked on completely built {@link Block} instances
     * i.e. not on in-progress block builders.
     */
    @Override
    public VarcharArrayBlock copyWithAppendedNull()
    {
        boolean[] newValueIsNull = copyIsNullAndAppendNull(valueIsNull, arrayOffset, positionCount);
        int[] newOffsets = copyOffsetsAndAppendNull(offsets, arrayOffset, positionCount);
        int[] newLengths = copyOffsetsAndAppendNull(lengths, arrayOffset, positionCount);

        return new VarcharArrayBlock(arrayOffset, positionCount + 1, values, newOffsets, newLengths, true, newValueIsNull);
    }

    @Override
    public ValueBlock getUnderlyingValueBlock()
    {
        return this;
    }

    @Override
    public int getUnderlyingValuePosition(int position)
    {
        return position;
    }

    @Override
    public Optional<ByteArrayBlock> getNulls()
    {
        return BlockUtil.getNulls(valueIsNull, arrayOffset, positionCount);
    }

    @Override
    public boolean mayHaveNull()
    {
        return this.hasNull;
    }

    @Override
    public Block getPositions(int[] positions, int offset, int length)
    {
        return ValueBlock.super.getPositions(positions, offset, length);
    }

    @Override
    public boolean isLoaded()
    {
        return true;
    }

    @Override
    public Block getLoadedBlock()
    {
        return this;
    }

    protected void checkReadablePosition(int position)
    {
        BlockUtil.checkValidPosition(position, getPositionCount());
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder("VarcharArrayBlock{");
        sb.append("positionCount=").append(getPositionCount());
        sb.append(", size=").append(sizeInBytes);
        sb.append(", retainedSize=").append(retainedSizeInBytes);
        sb.append('}');
        return sb.toString();
    }
}
