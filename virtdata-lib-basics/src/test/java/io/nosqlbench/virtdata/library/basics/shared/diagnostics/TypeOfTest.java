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

package io.nosqlbench.virtdata.library.basics.shared.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

public class TypeOfTest {

    @Test
    public void testGenericSignature() {
        TypeOf ft = new TypeOf();

        HashMap hm = new HashMap();
        String hmType = ft.apply(hm);
        assertThat(hmType).isEqualTo("java.util.HashMap");

        HashMap<Long,String> lshm = new HashMap<Long,String>();
        String typedType = ft.apply(lshm);
        assertThat(typedType).isEqualTo("java.util.HashMap");

    }

}
