/*
 * Copyright 2024 PixelsDB.
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
package io.pixelsdb.pixels.trino.vector.exactnns;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.function.*;
import io.trino.spi.type.StandardTypes;

import java.util.Arrays;

@AggregationFunction("exact_nns_agg")
@Description("Returns the closest vectors of the input vector")
public class ExactNNSAggFunc
{
    private ExactNNSAggFunc() {}

    /**
     * update a state from a single row, i.e. a single vector
     * @param state the state that is used for aggregate many rows into one state
     * @param inputVecBlock a block representing the input vector for which we want to find the k nearest neighbours
     * @param k we want the k nearest neighbours
     */
    @InputFunction
    public static void input(@AggregationState ExactNNSState state,
                             @SqlType("array(double)") Block vecFromFile,
                             @SqlType("array(double)") Block inputVecBlock,
                             @SqlType(StandardTypes.VARCHAR) Slice sliceForVectorDistFunc,
                             @SqlType("integer") long k)
    {
        // check input vector block
        if (inputVecBlock==null || vecFromFile==null || k<=0)
        {
            return;
        }

        // initialize the state if this is an uninitialized state handling the first row
        if (state.getNearestVecs()==null)
        {
            state.init(inputVecBlock, vecFromFile.getPositionCount(), sliceForVectorDistFunc, (int) k);
        }

        // use the input row, i.e. a vector to update the priority queue in the state
        state.updateNearestVecs(ExactNNSUtil.blockToVec(vecFromFile));
    }


    @CombineFunction
    public static void combine(@AggregationState ExactNNSState state, @AggregationState ExactNNSState otherState)
    {
        // TODO: update the PriorityQueue that stores nearest vectors in state, using the PriorityQueue in otherState
        state.combineWithOtherState(otherState);
    }

    @OutputFunction(StandardTypes.JSON)
    public static void output(@AggregationState ExactNNSState state, BlockBuilder out)
    {
        int numVecs = state.getNearestVecs().size();
        double[][] nearestVecs = new double[numVecs][];
        for (int i=0; i<numVecs; i++)
        {
            nearestVecs[i] = state.getNearestVecs().poll();
        }
        // sort the nearest vecs from smaller dist to larger dist
        Arrays.sort(nearestVecs, state.getNearestVecs().comparator().reversed());
        try
        {
            ObjectMapper objectMapper = new ObjectMapper();
            String jsonResult = objectMapper.writeValueAsString(nearestVecs);
            out.writeBytes(Slices.utf8Slice(jsonResult), 0, jsonResult.length());
            out.closeEntry();
        } catch (JsonProcessingException e)
        {
            e.printStackTrace();
        }
    }
}
