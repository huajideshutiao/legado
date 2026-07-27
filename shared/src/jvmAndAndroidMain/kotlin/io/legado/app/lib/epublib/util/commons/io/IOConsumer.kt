/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.legado.app.lib.epublib.util.commons.io

import java.io.IOException
import java.util.Objects

/**
 * Like [Consumer] but throws [IOException].
 * 
 * @param <T> the type of the input to the operations.
 * @since 2.7
</T> */
fun interface IOConsumer<T> {
    /**
     * Performs this operation on the given argument.
     * 
     * @param t the input argument
     * @throws IOException if an I/O error occurs.
     */
    @Throws(IOException::class)
    fun accept(t: T?)

    /**
     * Returns a composed `IoConsumer` that performs, in sequence, this operation followed by the `after`
     * operation. If performing either operation throws an exception, it is relayed to the caller of the composed
     * operation. If performing this operation throws an exception, the `after` operation will not be performed.
     * 
     * @param after the operation to perform after this operation
     * @return a composed `Consumer` that performs in sequence this operation followed by the `after`
     * operation
     * @throws NullPointerException if `after` is null
     */
    @Suppress("unused")
    fun andThen(after: IOConsumer<in T?>?): IOConsumer<T?> {
        Objects.requireNonNull(after)
        return IOConsumer { t: T? ->
            accept(t)
            after!!.accept(t)
        }
    }
}
