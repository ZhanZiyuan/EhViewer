package com.ehviewer.core.mainthread

import platform.Foundation.NSThread

internal actual val isMainThread: Boolean get() = NSThread.isMainThread
