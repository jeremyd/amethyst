/**
 * Copyright (c) 2024 Vitor Pamplona
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.vitorpamplona.quartz.nip46RemoteSigner

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import java.util.UUID

class BunkerRequestConnect(
    id: String = UUID.randomUUID().toString(),
    val remoteKey: HexKey,
    val secret: HexKey? = null,
    val permissions: String? = null,
) : BunkerRequest(id, METHOD_NAME, listOfNotNull(remoteKey, secret, permissions).toTypedArray()) {
    companion object {
        val METHOD_NAME = "connect"

        fun parse(
            id: String,
            params: Array<String>,
        ): BunkerRequestConnect = BunkerRequestConnect(id, params[0], params.getOrNull(1), params.getOrNull(2))
    }
}

public fun <T> Array<T>.getOrNull(index: Int): T? = if (index in 0..<size) get(index) else null
