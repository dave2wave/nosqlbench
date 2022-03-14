/*
 * Copyright (c) 2022 nosqlbench
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.nosqlbench.virtdata.library.basics.shared.from_long.to_unset;

import io.nosqlbench.virtdata.api.annotations.Categories;
import io.nosqlbench.virtdata.api.annotations.Category;
import io.nosqlbench.virtdata.api.annotations.ThreadSafeMapper;
import io.nosqlbench.virtdata.api.bindings.VALUE;

import java.util.function.LongFunction;

/**
 * Yield UNSET.vale if the input value is equal to the
 * specified value. Otherwise, pass the input value along.
 */
@ThreadSafeMapper
@Categories(Category.nulls)
public class UnsetIfEq implements LongFunction<Object> {

    private final long compareto;

    public UnsetIfEq(long compareto) {
        this.compareto = compareto;
    }

    @Override
    public Object apply(long value) {
        if (value == compareto) return VALUE.unset;
        return value;
    }
}
