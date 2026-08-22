package com.latenighthack.ktflags.client

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

internal actual fun epochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
