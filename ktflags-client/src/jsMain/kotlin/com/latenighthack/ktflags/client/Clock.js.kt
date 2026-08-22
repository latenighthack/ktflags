package com.latenighthack.ktflags.client

import kotlin.js.Date

internal actual fun epochMillis(): Long = Date.now().toLong()
